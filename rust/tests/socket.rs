//! `SocketClient` integration tests, over an in-process bridge standing
//! in for `socat` (plus a real-`socat` test that runs only when `socat`
//! is installed). Mirrors go/sandbox/socket_test.go and
//! python/tests/test_socket.py.
//!
//! `DirectTransport` can't be reused as the bridge's byte pipe here: it
//! already commits incoming bytes to line framing via its own background
//! reader, so a second consumer racing it for the same stdout would
//! corrupt data. The bridge below talks to the child process with a bare
//! `std::process::Command` instead, exactly mirroring what an external
//! `socat` does. POSIX-only (UNIX sockets, socat).
#![cfg(unix)]

mod common;

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::Path;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use san_db_ox_client::{
    Connection, PROTOCOL, SocketOptions, Value, connect_tcp_with, connect_unix_with,
};

use common::{CALL_TIMEOUT, isolated_dir, san_db_ox_bin};

fn tcp_echo_bridge(bin: &Path, args: Vec<String>) -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let bin = bin.to_path_buf();
    thread::spawn(move || {
        if let Ok((conn, _)) = listener.accept() {
            spawn_bridge_tcp(conn, &bin, &args);
        }
    });
    port
}

fn spawn_bridge_tcp(conn: TcpStream, bin: &Path, args: &[String]) {
    let mut child = Command::new(bin)
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let mut child_stdin = child.stdin.take().unwrap();
    let mut child_stdout = child.stdout.take().unwrap();
    let mut to_process = conn.try_clone().unwrap();
    let mut to_client = conn;

    let t1 = thread::spawn(move || {
        let mut buf = [0u8; 65536];
        loop {
            match to_process.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if child_stdin.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
            }
        }
    });
    let t2 = thread::spawn(move || {
        let mut buf = [0u8; 65536];
        loop {
            match child_stdout.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if to_client.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
            }
        }
    });
    let _ = t1.join();
    let _ = t2.join();
    let _ = child.wait();
}

fn unix_echo_bridge(bin: &Path, args: Vec<String>) -> std::path::PathBuf {
    let dir = isolated_dir();
    let path = dir.join("bridge.sock");
    let listener = UnixListener::bind(&path).unwrap();
    let bin = bin.to_path_buf();
    thread::spawn(move || {
        if let Ok((conn, _)) = listener.accept() {
            spawn_bridge_unix(conn, &bin, &args);
        }
    });
    path
}

fn spawn_bridge_unix(conn: UnixStream, bin: &Path, args: &[String]) {
    let mut child = Command::new(bin)
        .args(args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let mut child_stdin = child.stdin.take().unwrap();
    let mut child_stdout = child.stdout.take().unwrap();
    let mut to_process = conn.try_clone().unwrap();
    let mut to_client = conn;

    let t1 = thread::spawn(move || {
        let mut buf = [0u8; 65536];
        loop {
            match to_process.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if child_stdin.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
            }
        }
    });
    let t2 = thread::spawn(move || {
        let mut buf = [0u8; 65536];
        loop {
            match child_stdout.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    if to_client.write_all(&buf[..n]).is_err() {
                        break;
                    }
                }
            }
        }
    });
    let _ = t1.join();
    let _ = t2.join();
    let _ = child.wait();
}

#[test]
fn socket_client_over_tcp_bridge() {
    let Some(bin) = san_db_ox_bin() else {
        eprintln!("skipping: no san-db-ox binary found: run `make fetch` or set SAN_DB_OX_BIN");
        return;
    };
    let port = tcp_echo_bridge(&bin, vec!["--serve-stdio".to_string()]);
    let mut c = connect_tcp_with(
        ("127.0.0.1", port),
        SocketOptions::new().timeout(Some(CALL_TIMEOUT)),
    )
    .unwrap();
    assert_eq!(c.hello().protocol, PROTOCOL);
    c.exec("CREATE TABLE t(x INTEGER)", &[]).unwrap();
    c.exec("INSERT INTO t VALUES (1)", &[]).unwrap();
    assert_eq!(
        c.query("SELECT x FROM t", &[]).unwrap().rows,
        vec![vec![Value::Integer(1)]]
    );
}

#[test]
fn socket_client_over_unix_bridge() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let path = unix_echo_bridge(&bin, vec!["--serve-stdio".to_string()]);
    let mut c = connect_unix_with(&path, SocketOptions::new().timeout(Some(CALL_TIMEOUT))).unwrap();
    assert_eq!(c.hello().protocol, PROTOCOL);
    c.exec("CREATE TABLE t(x INTEGER)", &[]).unwrap();
    assert_eq!(
        c.query("SELECT 1", &[]).unwrap().rows,
        vec![vec![Value::Integer(1)]]
    );
}

#[test]
fn socket_client_close_reaches_bridged_process_and_is_idempotent() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let path = unix_echo_bridge(&bin, vec!["--serve-stdio".to_string()]);
    let mut c = connect_unix_with(&path, SocketOptions::new().timeout(Some(CALL_TIMEOUT))).unwrap();
    c.close();
    c.close(); // must not panic or hang
}

#[test]
fn socket_client_via_real_socat() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    if Command::new("which")
        .arg("socat")
        .stdout(Stdio::null())
        .status()
        .map(|s| !s.success())
        .unwrap_or(true)
    {
        eprintln!("skipping: socat is not installed");
        return;
    }
    let dir = isolated_dir();
    let path = dir.join("socat.sock");
    let mut socat = Command::new("socat")
        .arg(format!("UNIX-LISTEN:{},fork", path.display()))
        .arg(format!("EXEC:{} --serve-stdio", bin.display()))
        .spawn()
        .unwrap();

    let deadline = Instant::now() + Duration::from_secs(5);
    while !path.exists() {
        if Instant::now() > deadline {
            let _ = socat.kill();
            panic!("socat did not create the socket in time");
        }
        thread::sleep(Duration::from_millis(50));
    }

    let mut c = connect_unix_with(&path, SocketOptions::new().timeout(Some(CALL_TIMEOUT))).unwrap();
    assert_eq!(c.hello().protocol, PROTOCOL);
    assert_eq!(
        c.query("SELECT 1", &[]).unwrap().rows,
        vec![vec![Value::Integer(1)]]
    );
    drop(c);

    let _ = socat.kill();
    let _ = socat.wait();
}
