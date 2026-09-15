//! `SocketTransport`: a TCP/UNIX socket, typically fronted by something
//! like socat. Mirrors go/sandbox/internal/transport/socket.go and
//! Python's `_transport.SocketTransport`.
//!
//! Unlike `DirectTransport`, this reads inline on the caller's own
//! thread -- no background reader thread. The deciding factor is the TLS
//! seam (`connect_socket`, in `client.rs`): synchronous TLS streams
//! (`rustls::StreamOwned`, `native_tls::TlsStream`) share record state
//! between reads and writes and support neither splitting nor cloning, so
//! a threaded reader would force the public seam into either
//! `from_halves(r, w)` (useless for TLS) or `Arc<Mutex<S>>` (deadlocks,
//! since a blocked reader thread would hold the lock a writer needs).
//! Sockets are also the one place `std` gives a real per-read deadline
//! (`set_read_timeout`), including through a TLS wrapper: a timeout set on
//! the underlying `TcpStream` before wrapping still bounds the TLS
//! stream's reads, since they delegate to it.

use std::io::{ErrorKind, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

use crate::error::Error;

use super::LineFramer;
use super::Transport;

/// How often an inline read is allowed to block before this driver
/// re-checks the caller's own deadline. Set on every stream this crate
/// connects itself (`connect_tcp`/`connect_unix`); irrelevant to
/// `connect_socket`, whose caller is responsible for the underlying
/// stream's read timeout (see `client::connect_socket`'s doc comment).
/// Kept short so a call's own (possibly much shorter than this) deadline
/// is still honored promptly -- `Box<dyn Read + Write>` has no
/// `set_read_timeout` to narrow per call, so this is the bound on how
/// late a timeout can be detected.
const POLL_INTERVAL: Duration = Duration::from_millis(20);

pub(crate) trait ReadWrite: Read + Write + Send {}
impl<T: Read + Write + Send> ReadWrite for T {}

pub(crate) struct SocketTransport {
    stream: Box<dyn ReadWrite>,
    framer: LineFramer,
    /// Memoized once EOF, a framing error, or an I/O error has been seen,
    /// and re-delivered on every subsequent read (mirrors
    /// `transport::reader::ThreadedReader`'s `terminal` field).
    terminal: Option<Error>,
}

impl SocketTransport {
    /// Wraps an already-connected stream. The caller must have already
    /// set a read timeout on it (or on whatever it wraps -- e.g. the
    /// `TcpStream` underneath a TLS stream) for `read_line`'s timeouts to
    /// actually be enforced; this is the one contract this driver cannot
    /// set on an arbitrary `Read + Write` itself.
    pub(crate) fn from_stream(stream: impl Read + Write + Send + 'static) -> Self {
        SocketTransport {
            stream: Box::new(stream),
            framer: LineFramer::new(),
            terminal: None,
        }
    }

    pub(crate) fn connect_tcp(
        addr: impl ToSocketAddrs,
        timeout: Option<Duration>,
    ) -> Result<Self, Error> {
        let stream = dial_tcp(addr, timeout)?;
        stream.set_read_timeout(Some(POLL_INTERVAL))?;
        Ok(Self::from_stream(stream))
    }

    #[cfg(unix)]
    pub(crate) fn connect_unix(path: impl AsRef<std::path::Path>) -> Result<Self, Error> {
        use std::os::unix::net::UnixStream;
        let stream = UnixStream::connect(path.as_ref())?;
        stream.set_read_timeout(Some(POLL_INTERVAL))?;
        Ok(Self::from_stream(stream))
    }
}

fn dial_tcp(addr: impl ToSocketAddrs, timeout: Option<Duration>) -> Result<TcpStream, Error> {
    let addrs: Vec<_> = addr.to_socket_addrs()?.collect();
    if addrs.is_empty() {
        return Err(Error::from(std::io::Error::new(
            ErrorKind::InvalidInput,
            "no addresses to connect to",
        )));
    }
    match timeout {
        None => TcpStream::connect(addrs.as_slice()).map_err(Error::from),
        Some(t) => {
            let deadline = Instant::now() + t;
            let mut last_err = None;
            for a in &addrs {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    break;
                }
                match TcpStream::connect_timeout(a, remaining) {
                    Ok(s) => return Ok(s),
                    Err(e) => last_err = Some(e),
                }
            }
            Err(last_err
                .map(Error::from)
                .unwrap_or_else(|| Error::Timeout("connect timed out".to_string())))
        }
    }
}

impl Transport for SocketTransport {
    fn write_line(&mut self, line: &[u8]) -> Result<(), Error> {
        self.stream.write_all(line)?;
        self.stream.flush()?;
        Ok(())
    }

    fn read_line(&mut self, timeout: Option<Duration>) -> Result<Vec<u8>, Error> {
        if let Some(e) = &self.terminal {
            return Err(e.clone());
        }
        if let Some(line) = self.take_or_terminal()? {
            return Ok(line);
        }
        let deadline = timeout.map(|t| Instant::now() + t);
        let mut buf = [0u8; 64 * 1024];
        loop {
            match self.stream.read(&mut buf) {
                Ok(0) => {
                    let e = Error::Protocol("connection closed (EOF)".to_string());
                    self.terminal = Some(e.clone());
                    return Err(e);
                }
                Ok(n) => {
                    self.framer.push(&buf[..n]);
                    if let Some(line) = self.take_or_terminal()? {
                        return Ok(line);
                    }
                    // A partial line survives across a timeout in
                    // `framer` -- stricter than a threaded reader would
                    // allow, which would have to discard or block.
                    if timed_out(deadline) {
                        return Err(Error::Timeout(
                            "timed out waiting for a response line".to_string(),
                        ));
                    }
                }
                Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    if timed_out(deadline) {
                        return Err(Error::Timeout(
                            "timed out waiting for a response line".to_string(),
                        ));
                    }
                }
                Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(e) => {
                    let err = Error::from(e);
                    self.terminal = Some(err.clone());
                    return Err(err);
                }
            }
        }
    }

    /// No staged escalation here, unlike `DirectTransport` -- there is no
    /// child process to signal or reap. `&mut self` on every call already
    /// makes "a pending read concurrent with close" unrepresentable, so
    /// unlike Go/Python's socket transports there is no live reader to
    /// unblock; marking the transport terminal is enough. The underlying
    /// stream's `Drop` (`TcpStream`/`UnixStream`/the caller's own stream
    /// type) releases the OS resource.
    fn close(&mut self, _timeout: Duration) {
        self.terminal = Some(Error::Closed);
    }
}

impl SocketTransport {
    fn take_or_terminal(&mut self) -> Result<Option<Vec<u8>>, Error> {
        match self.framer.take_line() {
            Ok(v) => Ok(v),
            Err(e) => {
                self.terminal = Some(e.clone());
                Err(e)
            }
        }
    }
}

fn timed_out(deadline: Option<Instant>) -> bool {
    deadline.is_some_and(|dl| Instant::now() >= dl)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::thread;

    fn tcp_echo_server() -> u16 {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        thread::spawn(move || {
            if let Ok((mut conn, _)) = listener.accept() {
                let mut buf = [0u8; 4096];
                loop {
                    match conn.read(&mut buf) {
                        Ok(0) | Err(_) => return,
                        Ok(n) => {
                            if conn.write_all(&buf[..n]).is_err() {
                                return;
                            }
                        }
                    }
                }
            }
        });
        port
    }

    #[test]
    fn tcp_echo_round_trip() {
        let port = tcp_echo_server();
        let mut t = SocketTransport::connect_tcp(("127.0.0.1", port), Some(Duration::from_secs(5)))
            .unwrap();
        t.write_line(b"hello\n").unwrap();
        let line = t.read_line(Some(Duration::from_secs(5))).unwrap();
        assert_eq!(line, b"hello");
    }

    #[cfg(unix)]
    #[test]
    fn unix_echo_round_trip() {
        use std::os::unix::net::UnixListener;
        let dir = std::env::temp_dir().join(format!("san-db-ox-rs-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("echo.sock");
        let _ = std::fs::remove_file(&path);
        let listener = UnixListener::bind(&path).unwrap();
        thread::spawn(move || {
            if let Ok((mut conn, _)) = listener.accept() {
                let mut buf = [0u8; 4096];
                loop {
                    match conn.read(&mut buf) {
                        Ok(0) | Err(_) => return,
                        Ok(n) => {
                            if conn.write_all(&buf[..n]).is_err() {
                                return;
                            }
                        }
                    }
                }
            }
        });
        let mut t = SocketTransport::connect_unix(&path).unwrap();
        t.write_line(b"hello\n").unwrap();
        let line = t.read_line(Some(Duration::from_secs(5))).unwrap();
        assert_eq!(line, b"hello");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[cfg(unix)]
    #[test]
    fn from_stream_over_a_unix_stream_pair_is_the_tls_seam_without_tls() {
        use std::os::unix::net::UnixStream;
        let (a, b) = UnixStream::pair().unwrap();
        a.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        b.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let mut ta = SocketTransport::from_stream(a);
        thread::spawn(move || {
            let mut b = b;
            let mut buf = [0u8; 64];
            let n = b.read(&mut buf).unwrap();
            b.write_all(&buf[..n]).unwrap();
        });
        ta.write_line(b"ping\n").unwrap();
        let line = ta.read_line(Some(Duration::from_secs(5))).unwrap();
        assert_eq!(line, b"ping");
    }

    #[test]
    fn read_timeout_fires() {
        let port = tcp_echo_server();
        let mut t = SocketTransport::connect_tcp(("127.0.0.1", port), Some(Duration::from_secs(5)))
            .unwrap();
        let err = t.read_line(Some(Duration::from_millis(50))).unwrap_err();
        assert!(matches!(err, Error::Timeout(_)));
    }

    #[test]
    fn partial_line_survives_a_timeout_and_completes_next_read() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        thread::spawn(move || {
            if let Ok((mut conn, _)) = listener.accept() {
                conn.write_all(b"par").unwrap();
                thread::sleep(Duration::from_millis(150));
                conn.write_all(b"tial\n").unwrap();
            }
        });
        let mut t = SocketTransport::connect_tcp(("127.0.0.1", port), Some(Duration::from_secs(5)))
            .unwrap();
        let err = t.read_line(Some(Duration::from_millis(50))).unwrap_err();
        assert!(matches!(err, Error::Timeout(_)));
        let line = t.read_line(Some(Duration::from_secs(5))).unwrap();
        assert_eq!(line, b"partial");
    }

    #[test]
    fn connect_tcp_bounds_the_connection_attempt_by_its_own_timeout() {
        // 192.0.2.0/24 is reserved for documentation (RFC 5737) and
        // routes nowhere real, so this either times out or fails fast --
        // either way it must return well within the bound given.
        let start = Instant::now();
        let result =
            SocketTransport::connect_tcp(("192.0.2.1", 1u16), Some(Duration::from_millis(300)));
        assert!(result.is_err());
        assert!(start.elapsed() < Duration::from_secs(5));
    }
}
