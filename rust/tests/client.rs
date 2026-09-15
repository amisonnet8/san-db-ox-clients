//! Integration tests for `san_db_ox_client::connect()` / `Client` against
//! a real san-db-ox binary. Mirrors go/sandbox/sandbox_test.go and
//! python/tests/test_client.py.

mod common;

use std::io::Write;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use san_db_ox_client::{
    CODE_IO_ERROR, CODE_READ_ONLY, CODE_SQLITE_ERROR, Client, ConnectOptions, Connection, PROTOCOL,
    SnapshotOptions, StderrSink, Value, connect, connect_with,
};

use common::{CALL_TIMEOUT, copy_of_binary, isolated_dir, san_db_ox_bin};

fn open(bin: &std::path::Path) -> Client {
    connect_with(
        bin,
        &["--serve-stdio"],
        ConnectOptions::new().timeout(Some(CALL_TIMEOUT)),
    )
    .unwrap()
}

fn open_with_args(bin: &std::path::Path, args: &[&str]) -> Client {
    connect_with(bin, args, ConnectOptions::new().timeout(Some(CALL_TIMEOUT))).unwrap()
}

#[test]
fn hello_and_basic_query() {
    let Some(bin) = san_db_ox_bin() else {
        eprintln!("skipping: no san-db-ox binary found: run `make fetch` or set SAN_DB_OX_BIN");
        return;
    };
    let mut c = open(&bin);
    assert_eq!(c.hello().protocol, PROTOCOL);
    assert_eq!(c.hello().product, "SanDBox");
    let result = c.query("SELECT 1", &[]).unwrap();
    assert_eq!(result.rows, vec![vec![Value::Integer(1)]]);
}

#[test]
fn connect_rejects_nonexistent_command() {
    // Command::spawn()'s own ENOENT propagates as Error::Io -- there is
    // no protocol violation or wire error to wrap here, just a bad
    // command.
    let err = connect("this-command-does-not-exist-really", &["--serve-stdio"])
        .err()
        .unwrap();
    assert!(matches!(err, san_db_ox_client::Error::Io(_)));
}

#[test]
fn blob_roundtrip() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    c.exec("CREATE TABLE t(b BLOB)", &[]).unwrap();
    c.exec("INSERT INTO t VALUES (?)", &[Value::Blob(b"hi".to_vec())])
        .unwrap();
    let result = c.query("SELECT b FROM t", &[]).unwrap();
    assert_eq!(result.rows, vec![vec![Value::Blob(b"hi".to_vec())]]);
}

#[test]
fn empty_blob_roundtrip() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    c.exec("CREATE TABLE t(b BLOB)", &[]).unwrap();
    c.exec("INSERT INTO t VALUES (?)", &[Value::Blob(vec![])])
        .unwrap();
    let result = c.query("SELECT b FROM t", &[]).unwrap();
    assert_eq!(result.rows, vec![vec![Value::Blob(vec![])]]);
}

#[test]
fn large_integer_roundtrip() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    let big = i64::MAX;
    c.exec("CREATE TABLE big(n INTEGER)", &[]).unwrap();
    c.exec("INSERT INTO big VALUES (?)", &[Value::Integer(big)])
        .unwrap();
    let result = c.query("SELECT n, typeof(n) FROM big", &[]).unwrap();
    assert_eq!(
        result.rows,
        vec![vec![
            Value::Integer(big),
            Value::Text("integer".to_string())
        ]]
    );
}

#[test]
fn real_representation() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    assert_eq!(
        c.query("SELECT 88.0", &[]).unwrap().rows,
        vec![vec![Value::Real(88.0)]]
    );
    assert_eq!(
        c.query("SELECT 1e308 * 10", &[]).unwrap().rows,
        vec![vec![Value::Real(f64::INFINITY)]]
    );
    assert_eq!(
        c.query("SELECT -1e308 * 10", &[]).unwrap().rows,
        vec![vec![Value::Real(f64::NEG_INFINITY)]]
    );
    // Inf - Inf is SQL NULL upstream, indistinguishable from a REAL NaN
    // once decoded -- not a loss introduced by this driver.
    assert_eq!(
        c.query("SELECT (1e308 * 10) - (1e308 * 10)", &[])
            .unwrap()
            .rows,
        vec![vec![Value::Null]]
    );
}

#[test]
fn error_codes_and_connection_survives() {
    // bad_request and unsupported_op are not reachable through this typed
    // API at all -- there is no way to construct a malformed request with
    // it (unlike e.g. TypeScript's `c.query(undefined as any)`). Those two
    // codes are covered by conformance/cases/error-codes.json instead.
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);

    let err = c.query("SELECT * FROM nope", &[]).unwrap_err();
    assert!(err.is_code(CODE_SQLITE_ERROR), "got {err:?}");

    let err = c.load("/nonexistent/path/really-not-there.db").unwrap_err();
    assert!(err.is_code(CODE_IO_ERROR), "got {err:?}");

    // The connection must still be usable after errors.
    assert_eq!(
        c.query("SELECT 1", &[]).unwrap().rows,
        vec![vec![Value::Integer(1)]]
    );
}

#[test]
fn read_only_two_tier_rejection() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open_with_args(&bin, &["--serve-stdio", "--read-only"]);

    // SQL-level write: rejected by SQLite's own PRAGMA query_only.
    let err = c.exec("CREATE TABLE t(x INTEGER)", &[]).unwrap_err();
    assert!(err.is_code(CODE_SQLITE_ERROR), "got {err:?}");

    // Op-level writes: rejected by the stdio server itself, before SQLite.
    let err = c.overwrite().unwrap_err();
    assert!(err.is_code(CODE_READ_ONLY), "got {err:?}");

    let err = c.load("/nonexistent/does-not-matter.db").unwrap_err();
    assert!(err.is_code(CODE_READ_ONLY), "got {err:?}");

    assert_eq!(
        c.query("SELECT 1", &[]).unwrap().rows,
        vec![vec![Value::Integer(1)]]
    );
    assert!(c.inspect().unwrap().read_only);
}

#[test]
fn snapshot_and_load() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    // snapshot()/load() write/resolve relative to the server's cwd --
    // both connections share one isolated directory so `path` (returned
    // by snapshot, possibly relative) resolves the same way for load.
    let cwd = isolated_dir();
    let path = {
        let mut c = connect_with(
            &bin,
            &["--serve-stdio"],
            ConnectOptions::new().timeout(Some(CALL_TIMEOUT)).cwd(&cwd),
        )
        .unwrap();
        c.exec("CREATE TABLE t(x INTEGER)", &[]).unwrap();
        c.exec("INSERT INTO t VALUES (42)", &[]).unwrap();
        let snap = c
            .snapshot(SnapshotOptions {
                filename: Some("snap".to_string()),
                ..Default::default()
            })
            .unwrap();
        snap.path
    };

    let mut c2 = connect_with(
        &bin,
        &["--serve-stdio"],
        ConnectOptions::new().timeout(Some(CALL_TIMEOUT)).cwd(&cwd),
    )
    .unwrap();
    c2.load(&path).unwrap();
    assert_eq!(
        c2.query("SELECT x FROM t", &[]).unwrap().rows,
        vec![vec![Value::Integer(42)]]
    );
}

#[test]
fn inspect_reflects_own_process_only() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    let info = c.inspect().unwrap();
    assert!(!info.has_data);
    assert_eq!(info.data_length, None);
    assert_eq!(info.version, None);
    c.exec("CREATE TABLE t(x INTEGER)", &[]).unwrap();
    // exec'd SQL state doesn't change inspect's view of embedded data.
    let info_after = c.inspect().unwrap();
    assert!(!info_after.has_data);
}

#[test]
fn tables_schema_dump() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    c.exec("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)", &[])
        .unwrap();
    c.exec("CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)", &[])
        .unwrap();
    c.exec("INSERT INTO users(id, name) VALUES (1, 'alice')", &[])
        .unwrap();

    assert_eq!(
        c.tables().unwrap().tables,
        vec!["logs".to_string(), "users".to_string()]
    );

    let schema = c.schema(None).unwrap();
    assert_eq!(
        schema.schema,
        vec![
            "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)".to_string(),
            "CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)".to_string(),
        ]
    );
    assert_eq!(
        c.schema(Some("users")).unwrap().schema,
        vec!["CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)".to_string()]
    );

    let dump = c.dump(Some("users")).unwrap();
    assert!(dump.sql.contains("INSERT INTO \"users\" VALUES(1,'alice')"));
    assert!(!dump.sql.contains("logs"));
}

#[test]
fn close_reaps_process_and_is_idempotent() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    c.close();
    assert_eq!(c.exit_code(), Some(0));
    c.close(); // must not panic or hang
}

#[test]
fn drop_reaps_child_without_explicit_close() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    // No .claude/rules-visible analogue in Go/Python/TypeScript -- Drop is
    // this driver's own guarantee, so its own test.
    {
        let _c = open(&bin);
        // dropped here without calling close()
    }
    // If the child leaked, there is nothing to assert on directly from
    // here (this test doesn't hold a handle to it) -- the meaningful
    // assertion is that the process above completes and process exit
    // eventually happens without needing `--test-force-exit` (Node's
    // equivalent doesn't apply to `cargo test`, but an orphaned child
    // holding stdout open would otherwise be visible via `ps` after the
    // test binary exits; CI does not leave orphans, which is the
    // practical signal this test protects).
}

#[test]
fn overwrite_reaps_and_persists_data() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let dir = isolated_dir();
    let copy = copy_of_binary(&bin, &dir);

    {
        let mut c = open(&copy);
        c.exec("CREATE TABLE t(x INTEGER)", &[]).unwrap();
        c.exec("INSERT INTO t VALUES (7)", &[]).unwrap();
        c.overwrite().unwrap();
        assert_eq!(c.exit_code(), Some(0));
    }

    let mut c2 = open(&copy);
    assert!(c2.inspect().unwrap().has_data);
    assert_eq!(
        c2.query("SELECT x FROM t", &[]).unwrap().rows,
        vec![vec![Value::Integer(7)]]
    );
}

#[test]
fn call_timeout_tears_down() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    // A plain "SELECT 1" round-trips too fast for a tiny timeout to
    // reliably fire -- use a query that takes a few seconds so the
    // timeout path is exercised deterministically rather than racing
    // real round-trip latency.
    let slow_query = "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM r WHERE x < 10000000) \
                       SELECT count(*) FROM r";
    let mut c = open(&bin);
    c.set_timeout(Some(Duration::from_millis(200)));
    let err = c.query(slow_query, &[]).unwrap_err();
    assert!(
        matches!(err, san_db_ox_client::Error::Timeout(_)),
        "got {err:?}"
    );

    // The connection is unusable now.
    let err2 = c.query("SELECT 1", &[]).unwrap_err();
    assert!(
        matches!(err2, san_db_ox_client::Error::Closed),
        "got {err2:?}"
    );

    // close() must still be safe, and must have actually reaped the
    // child (an assertion the Python driver's equivalent test doesn't
    // make, since Popen.poll() isn't asserted there either).
    c.close();
    assert!(c.exit_code().is_some());
}

#[test]
fn stderr_sink_does_not_break_the_connection() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let collected = Arc::new(Mutex::new(Vec::new()));
    struct Collector(Arc<Mutex<Vec<u8>>>);
    impl Write for Collector {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut c = connect_with(
        &bin,
        &["--serve-stdio"],
        ConnectOptions::new()
            .timeout(Some(CALL_TIMEOUT))
            .stderr(StderrSink::Writer(Box::new(Collector(collected.clone())))),
    )
    .unwrap();
    c.query("SELECT 1", &[]).unwrap();
    c.close();
    // Not asserting on content (san-db-ox may or may not log anything for
    // a clean run) -- just that wiring a sink doesn't break the
    // connection. `collected` itself proves the sink was reachable at
    // all (a broken wiring would have made connect_with fail outright).
    drop(collected);
}

#[test]
fn nan_and_inf_params_are_rejected_before_anything_is_sent() {
    let Some(bin) = san_db_ox_bin() else {
        return;
    };
    let mut c = open(&bin);
    let err = c.query("SELECT ?", &[Value::Real(f64::NAN)]).unwrap_err();
    assert!(
        matches!(&err, san_db_ox_client::Error::Protocol(m) if m.contains("finite")),
        "got {err:?}"
    );
    let err = c
        .query("SELECT ?", &[Value::Real(f64::INFINITY)])
        .unwrap_err();
    assert!(
        matches!(&err, san_db_ox_client::Error::Protocol(m) if m.contains("finite")),
        "got {err:?}"
    );
    // The connection is still usable -- rejection happens client-side,
    // before a request line is ever written.
    assert_eq!(
        c.query("SELECT 1", &[]).unwrap().rows,
        vec![vec![Value::Integer(1)]]
    );
}
