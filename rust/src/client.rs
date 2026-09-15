//! `Connection`, `Client`, `SocketClient`, and the `connect*` constructors
//! -- the public connection objects.

use std::ffi::{OsStr, OsString};
use std::io::{Read, Write};
use std::net::ToSocketAddrs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::DEFAULT_TIMEOUT;
use crate::codec::{
    DumpResult, ExecResult, Hello, InspectResult, Json, QueryResult, SchemaResult, SnapshotResult,
    TablesResult,
};
use crate::error::Result;
use crate::session::{Session, SnapshotOptions};
use crate::transport::direct::{DirectTransport, StderrSink};
use crate::transport::socket::SocketTransport;
use crate::value::Value;

/// What every connection to a SanDBox process can do, regardless of
/// transport. `overwrite`/`exit_code` are deliberately not part of this --
/// they only mean something over a direct connection, and are only on
/// [`Client`] (architecture.md).
pub trait Connection {
    /// The connection's greeting, read once when it was established.
    fn hello(&self) -> &Hello;
    /// Runs a SQL query and returns its result set in full (the protocol
    /// has no cursor -- there is no way to fetch a result set
    /// incrementally).
    fn query(&mut self, sql: &str, params: &[Value]) -> Result<QueryResult>;
    /// Runs a SQL statement and returns rows affected / last insert id.
    fn exec(&mut self, sql: &str, params: &[Value]) -> Result<ExecResult>;
    /// Saves the current database and returns the path written.
    fn snapshot(&mut self, opts: SnapshotOptions) -> Result<SnapshotResult>;
    /// Replaces the running database with the one at `path`.
    fn load(&mut self, path: &str) -> Result<()>;
    /// Reports on the running process's own embedded data. Not a
    /// general-purpose "inspect any path" op -- it only ever describes
    /// this process.
    fn inspect(&mut self) -> Result<InspectResult>;
    /// Lists table names.
    fn tables(&mut self) -> Result<TablesResult>;
    /// Returns `CREATE` statements, optionally filtered to one table.
    fn schema(&mut self, table: Option<&str>) -> Result<SchemaResult>;
    /// Returns a SQL dump, optionally filtered by pattern (the server
    /// defaults to `%`, i.e. everything, when omitted).
    fn dump(&mut self, pattern: Option<&str>) -> Result<DumpResult>;
    /// Gracefully ends the connection. Safe to call more than once, and
    /// also happens automatically on drop.
    fn close(&mut self);
    /// Sets the timeout used by every call on this connection from now
    /// on. `None` means wait forever.
    fn set_timeout(&mut self, timeout: Option<Duration>);
    /// The timeout currently in effect.
    fn timeout(&self) -> Option<Duration>;
}

/// A direct-connect connection to a running SanDBox process (a child
/// process, talked to over its stdin/stdout).
///
/// Not safe for concurrent use by multiple threads without external
/// synchronization: every call takes `&mut self`, which is exactly what
/// makes a second concurrent call a compile error rather than a protocol
/// violation at runtime.
pub struct Client {
    session: Session<DirectTransport>,
}

impl Client {
    /// Replaces the running process's own executable with one embedding
    /// the current database, then exits. This only makes sense over a
    /// direct-connect process -- socat-fronted sockets can have several
    /// clients connect through the same listener, and multiple processes
    /// writing the same executable path at once is exactly what this op
    /// must avoid -- so it lives here on `Client`, not on `Connection`,
    /// and [`SocketClient`] has no `overwrite` method to call (see the
    /// `compile_fail` examples on this crate's root documentation).
    pub fn overwrite(&mut self) -> Result<()> {
        self.session.call(vec![("op", Json::str("overwrite"))])?;
        // A successful overwrite ends the connection from the server's
        // side; reap the child so no zombie is left behind.
        self.session.close();
        Ok(())
    }

    /// The child process's exit code. Only meaningful after `close()` or
    /// `overwrite()` has returned. Only available over a direct
    /// connection -- [`SocketClient`] has no child process to report on.
    /// A signal death is reported as `-signum` (matching the Python
    /// driver's `Popen.poll()` convention).
    pub fn exit_code(&self) -> Option<i32> {
        self.session.transport.exit_code()
    }
}

impl Connection for Client {
    fn hello(&self) -> &Hello {
        &self.session.hello
    }
    fn query(&mut self, sql: &str, params: &[Value]) -> Result<QueryResult> {
        self.session.query(sql, params)
    }
    fn exec(&mut self, sql: &str, params: &[Value]) -> Result<ExecResult> {
        self.session.exec(sql, params)
    }
    fn snapshot(&mut self, opts: SnapshotOptions) -> Result<SnapshotResult> {
        self.session.snapshot(opts)
    }
    fn load(&mut self, path: &str) -> Result<()> {
        self.session.load(path)
    }
    fn inspect(&mut self) -> Result<InspectResult> {
        self.session.inspect()
    }
    fn tables(&mut self) -> Result<TablesResult> {
        self.session.tables()
    }
    fn schema(&mut self, table: Option<&str>) -> Result<SchemaResult> {
        self.session.schema(table)
    }
    fn dump(&mut self, pattern: Option<&str>) -> Result<DumpResult> {
        self.session.dump(pattern)
    }
    fn close(&mut self) {
        self.session.close();
    }
    fn set_timeout(&mut self, timeout: Option<Duration>) {
        self.session.set_timeout(timeout);
    }
    fn timeout(&self) -> Option<Duration> {
        self.session.timeout()
    }
}

/// A socket connection to a SanDBox process, typically fronted by
/// something like socat. Has no `overwrite` or `exit_code` -- see
/// [`Client`].
pub struct SocketClient {
    session: Session<SocketTransport>,
}

impl Connection for SocketClient {
    fn hello(&self) -> &Hello {
        &self.session.hello
    }
    fn query(&mut self, sql: &str, params: &[Value]) -> Result<QueryResult> {
        self.session.query(sql, params)
    }
    fn exec(&mut self, sql: &str, params: &[Value]) -> Result<ExecResult> {
        self.session.exec(sql, params)
    }
    fn snapshot(&mut self, opts: SnapshotOptions) -> Result<SnapshotResult> {
        self.session.snapshot(opts)
    }
    fn load(&mut self, path: &str) -> Result<()> {
        self.session.load(path)
    }
    fn inspect(&mut self) -> Result<InspectResult> {
        self.session.inspect()
    }
    fn tables(&mut self) -> Result<TablesResult> {
        self.session.tables()
    }
    fn schema(&mut self, table: Option<&str>) -> Result<SchemaResult> {
        self.session.schema(table)
    }
    fn dump(&mut self, pattern: Option<&str>) -> Result<DumpResult> {
        self.session.dump(pattern)
    }
    fn close(&mut self) {
        self.session.close();
    }
    fn set_timeout(&mut self, timeout: Option<Duration>) {
        self.session.set_timeout(timeout);
    }
    fn timeout(&self) -> Option<Duration> {
        self.session.timeout()
    }
}

/// Options for [`connect_with`].
#[derive(Default)]
pub struct ConnectOptions {
    env: Option<Vec<(OsString, OsString)>>,
    cwd: Option<PathBuf>,
    stderr: Option<StderrSink>,
    timeout: Option<Duration>,
    timeout_set: bool,
}

impl ConnectOptions {
    pub fn new() -> Self {
        Self::default()
    }

    /// Replaces the child's environment entirely (rather than merging
    /// with this process's own), matching every other driver in this
    /// repository. Not calling this inherits the parent's environment.
    pub fn env(mut self, vars: impl IntoIterator<Item = (OsString, OsString)>) -> Self {
        self.env = Some(vars.into_iter().collect());
        self
    }

    pub fn cwd(mut self, dir: impl Into<PathBuf>) -> Self {
        self.cwd = Some(dir.into());
        self
    }

    /// Where the child's stderr goes. Defaults to `StderrSink::Null` --
    /// protocol.md requires stderr to always be drained so a chatty
    /// server can never block on a full pipe, and `Null` sidesteps that
    /// by never creating a pipe in the first place.
    pub fn stderr(mut self, sink: StderrSink) -> Self {
        self.stderr = Some(sink);
        self
    }

    /// The default timeout for every call on the resulting connection.
    /// `None` means wait forever. Not calling this uses `DEFAULT_TIMEOUT`.
    pub fn timeout(mut self, t: Option<Duration>) -> Self {
        self.timeout = t;
        self.timeout_set = true;
        self
    }
}

/// Launches `command` with `args` as a child process, reads its hello
/// line, and returns a ready-to-use [`Client`]. Equivalent to
/// `connect_with(command, args, ConnectOptions::new())`.
pub fn connect<S, A>(command: S, args: &[A]) -> Result<Client>
where
    S: AsRef<OsStr>,
    A: AsRef<OsStr>,
{
    connect_with(command, args, ConnectOptions::new())
}

/// Like [`connect`], with options controlling the child's environment,
/// working directory, stderr handling, and default call timeout.
///
/// `timeout` bounds the connection attempt (reading the hello line) and
/// becomes the default for every subsequent call
/// (`ConnectOptions::timeout`, or `DEFAULT_TIMEOUT` if not set).
pub fn connect_with<S, A>(command: S, args: &[A], opts: ConnectOptions) -> Result<Client>
where
    S: AsRef<OsStr>,
    A: AsRef<OsStr>,
{
    let timeout = if opts.timeout_set {
        opts.timeout
    } else {
        Some(DEFAULT_TIMEOUT)
    };
    let transport = DirectTransport::start(
        command,
        args,
        opts.env.as_deref(),
        opts.cwd.as_deref(),
        opts.stderr.unwrap_or(StderrSink::Null),
    )?;
    Ok(Client {
        session: Session::new(transport, timeout)?,
    })
}

/// Options for the socket constructors. The read timeout matters here in
/// a way it does not for [`ConnectOptions`]: see [`connect_socket`].
#[derive(Default)]
pub struct SocketOptions {
    timeout: Option<Duration>,
    timeout_set: bool,
}

impl SocketOptions {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn timeout(mut self, t: Option<Duration>) -> Self {
        self.timeout = t;
        self.timeout_set = true;
        self
    }

    fn resolve(&self) -> Option<Duration> {
        if self.timeout_set {
            self.timeout
        } else {
            Some(DEFAULT_TIMEOUT)
        }
    }
}

/// Dials a TCP socket exposing SanDBox's stdio protocol (e.g. via socat),
/// reads its hello line, and returns a ready-to-use [`SocketClient`].
pub fn connect_tcp(addr: impl ToSocketAddrs) -> Result<SocketClient> {
    connect_tcp_with(addr, SocketOptions::new())
}

pub fn connect_tcp_with(addr: impl ToSocketAddrs, opts: SocketOptions) -> Result<SocketClient> {
    let timeout = opts.resolve();
    let transport = SocketTransport::connect_tcp(addr, timeout)?;
    Ok(SocketClient {
        session: Session::new(transport, timeout)?,
    })
}

/// Dials a UNIX domain socket exposing SanDBox's stdio protocol (e.g. via
/// socat), reads its hello line, and returns a ready-to-use
/// [`SocketClient`].
#[cfg(unix)]
pub fn connect_unix(path: impl AsRef<Path>) -> Result<SocketClient> {
    connect_unix_with(path, SocketOptions::new())
}

#[cfg(unix)]
pub fn connect_unix_with(path: impl AsRef<Path>, opts: SocketOptions) -> Result<SocketClient> {
    let timeout = opts.resolve();
    let transport = SocketTransport::connect_unix(path)?;
    Ok(SocketClient {
        session: Session::new(transport, timeout)?,
    })
}

/// Wraps an already-connected stream, reads its hello line, and returns a
/// ready-to-use [`SocketClient`]. This is the seam for connections
/// [`connect_tcp`]/[`connect_unix`] can't build directly -- most notably a
/// TLS-wrapped stream (`rustls::StreamOwned`, `native_tls::TlsStream`) for
/// mutual-TLS authentication -- without this crate needing a
/// TLS-specific constructor or a dependency on any TLS crate.
///
/// **The caller must have already set a read timeout on the underlying
/// stream** (e.g. via `TcpStream::set_read_timeout` on the socket
/// underneath a TLS wrapper, before wrapping it) for `opts`'s timeout, and
/// every subsequent call's timeout, to actually be enforced -- this is the
/// one contract this crate cannot set on an arbitrary `Read + Write`
/// itself.
pub fn connect_socket(
    stream: impl Read + Write + Send + 'static,
    opts: SocketOptions,
) -> Result<SocketClient> {
    let timeout = opts.resolve();
    let transport = SocketTransport::from_stream(stream);
    Ok(SocketClient {
        session: Session::new(transport, timeout)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_send<T: Send>() {}

    #[test]
    fn client_and_socket_client_are_send() {
        assert_send::<Client>();
        assert_send::<SocketClient>();
    }
}
