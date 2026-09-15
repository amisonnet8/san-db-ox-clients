//! Client for [SanDBox](https://github.com/amisonnet8/san-db-ox): connect
//! to a running `san-db-ox --serve-stdio` process, either as a child
//! process (direct-connect) or over a TCP/UNIX socket exposed by
//! something like `socat`.
//!
//! A driver is a convenience, not a prerequisite -- the wire protocol is
//! plain JSON Lines, and nothing here does more than give it a typed Rust
//! API. Op names mirror the protocol's own op names 1:1: query, exec,
//! snapshot, load, inspect, tables, schema, dump, overwrite, close.
//!
//! Runtime dependencies: none, and no dev-dependencies either -- the JSON
//! codec and Base64 encoding are hand-written.
//!
//! # Quickstart
//!
//! ```no_run
//! use san_db_ox_client::{connect, Connection};
//!
//! # fn main() -> Result<(), san_db_ox_client::Error> {
//! let mut c = connect("san-db-ox", &["--serve-stdio"])?;
//! c.exec("CREATE TABLE t(x INTEGER)", &[])?;
//! let result = c.query("SELECT x FROM t", &[])?;
//! # let _ = result;
//! # Ok(())
//! # }
//! ```
//!
//! `connect` takes the command to launch, not a fixed "local" assumption,
//! so the same call works over SSH, through Docker, or via `kubectl exec`
//! just by changing the command and args.
//!
//! ## Connecting over a socket instead
//!
//! ```no_run
//! use san_db_ox_client::{connect_unix, Connection};
//!
//! # fn main() -> Result<(), san_db_ox_client::Error> {
//! let mut c = connect_unix("/tmp/sandbox.sock")?;
//! # let _ = c.tables()?;
//! # Ok(())
//! # }
//! ```
//!
//! [`Client`] (direct-connect) and [`SocketClient`] (socket) both
//! implement [`Connection`] (`query`/`exec`/`snapshot`/`load`/`inspect`/
//! `tables`/`schema`/`dump`/`close`). `overwrite` and `exit_code` are only
//! on `Client` -- they don't mean anything over a socket connection, so
//! they're simply not there to call (see the `compile_fail` examples
//! below).
//!
//! ## Values
//!
//! [`Value`] maps INTEGER to `i64`, REAL to `f64`, TEXT to `String`, and
//! BLOB to `Vec<u8>`. `bool` is not a supported param type -- SQLite (and
//! san-db-ox) has no boolean type, so `Value::from(true)` is a compile
//! error rather than a runtime one.
//!
//! Every connection is closed automatically on drop, so there is nothing
//! like a `with` block or `defer` to remember.
#![deny(unsafe_code)]

mod client;
mod codec;
mod error;
mod session;
mod transport;
mod value;

#[cfg(test)]
mod tests;

use std::time::Duration;

pub use client::{
    Client, ConnectOptions, Connection, SocketClient, SocketOptions, connect, connect_socket,
    connect_tcp, connect_tcp_with, connect_with,
};
#[cfg(unix)]
pub use client::{connect_unix, connect_unix_with};
pub use codec::{
    DumpResult, ExecResult, Hello, InspectResult, PROTOCOL, QueryResult, SchemaResult,
    SnapshotResult, TablesResult,
};
pub use error::{
    CODE_BAD_REQUEST, CODE_IO_ERROR, CODE_READ_ONLY, CODE_SQLITE_ERROR, CODE_UNSUPPORTED_OP, Error,
    Result,
};
pub use session::SnapshotOptions;
pub use transport::direct::StderrSink;
pub use value::{INT64_MAX, INT64_MIN, Row, Value};

/// This crate's own version (the crates.io package version). Not tied to
/// the upstream SanDBox version or to any other language driver in this
/// repository -- [`PROTOCOL`] is what expresses compatibility.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// The default timeout for every call, unless overridden via
/// [`ConnectOptions::timeout`] / [`SocketOptions::timeout`] /
/// [`Connection::set_timeout`].
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

/// How long each stage of `close()` waits before escalating (direct
/// connections: SIGTERM, then SIGKILL) or giving up on a graceful
/// shutdown (socket connections).
pub const CLOSE_TIMEOUT: Duration = Duration::from_secs(5);

/// `overwrite` is direct-connect only -- calling it on a [`SocketClient`]
/// is a compile error, not a runtime one:
///
/// ```compile_fail
/// # use san_db_ox_client::SocketClient;
/// fn f(c: &mut SocketClient) { let _ = c.overwrite(); }
/// ```
///
/// The same call is available on a direct-connect [`Client`]:
///
/// ```
/// # use san_db_ox_client::Client;
/// fn f(c: &mut Client) { let _ = c.overwrite(); }
/// ```
///
/// Likewise `exit_code` -- only [`Client`] has a child process to report
/// on:
///
/// ```compile_fail
/// # use san_db_ox_client::SocketClient;
/// fn f(c: &SocketClient) { let _ = c.exit_code(); }
/// ```
///
/// ```
/// # use san_db_ox_client::Client;
/// fn f(c: &Client) { let _ = c.exit_code(); }
/// ```
///
/// `bool` is not a supported param type -- san-db-ox has no boolean
/// SQLite type, and this driver rejects one at compile time rather than
/// at runtime:
///
/// ```compile_fail
/// # use san_db_ox_client::Value;
/// let _: Value = true.into();
/// ```
///
/// The equivalent conversion from an integer does compile:
///
/// ```
/// # use san_db_ox_client::Value;
/// let _: Value = 1i64.into();
/// ```
#[doc(hidden)]
pub struct _CompileFailExamples;
