//! `Session`: machinery shared by every transport -- hello validation and
//! the serialized call/response cycle. `Client` and `SocketClient` each
//! wrap a `Session`; neither exposes it publicly.
//!
//! The protocol has no request id, so responses can only be matched to
//! requests by strict ordering. Go needs `sync.Mutex`, Python
//! `threading.Lock`, TypeScript a promise-chain queue, all three to keep a
//! second request from being written before the first response is read.
//! Here, every call takes `&mut self`, so the borrow checker makes a
//! second concurrent call unrepresentable at compile time -- there is
//! nothing to lock.

use std::time::Duration;

use crate::CLOSE_TIMEOUT;
use crate::codec::{
    DumpResult, ExecResult, Hello, InspectResult, Json, PROTOCOL, QueryResult, SchemaResult,
    SnapshotResult, TablesResult, decode_dump_response, decode_exec_response, decode_hello,
    decode_inspect_response, decode_query_response, decode_response_line, decode_schema_response,
    decode_snapshot_response, decode_tables_response, encode_params, encode_request_line,
    response_error,
};
use crate::error::Error;
use crate::transport::Transport;
use crate::value::Value;

pub(crate) struct Session<T: Transport> {
    pub(crate) hello: Hello,
    pub(crate) transport: T,
    default_timeout: Option<Duration>,
    closed: bool,
}

impl<T: Transport> Session<T> {
    pub(crate) fn new(mut transport: T, timeout: Option<Duration>) -> Result<Self, Error> {
        let hello = match transport
            .read_line(timeout)
            .and_then(|line| decode_hello(&line))
        {
            Ok(h) => h,
            Err(e) => {
                transport.close(CLOSE_TIMEOUT);
                return Err(e);
            }
        };
        if hello.protocol != PROTOCOL {
            transport.close(CLOSE_TIMEOUT);
            return Err(Error::Protocol(format!(
                "unsupported protocol {} (this driver speaks {PROTOCOL})",
                hello.protocol
            )));
        }
        Ok(Session {
            hello,
            transport,
            default_timeout: timeout,
            closed: false,
        })
    }

    pub(crate) fn set_timeout(&mut self, t: Option<Duration>) {
        self.default_timeout = t;
    }

    pub(crate) fn timeout(&self) -> Option<Duration> {
        self.default_timeout
    }

    /// Sends one request and returns its decoded field set (or the
    /// response's own error, typed).
    ///
    /// A timeout is the one failure this driver cannot recover from
    /// gracefully: a single stuck read cannot be cancelled without
    /// tearing down the connection, so on timeout this closes the
    /// transport itself (bounded by `CLOSE_TIMEOUT`, which the direct
    /// transport's staged shutdown -- stdin close, SIGTERM, SIGKILL --
    /// guarantees terminates even a hung child) and marks the session
    /// permanently closed. Every other read/write error is returned as-is
    /// without an explicit close: the transport's own terminal-error
    /// memoization already makes it return the same error on every
    /// subsequent call.
    pub(crate) fn call(&mut self, fields: Vec<(&'static str, Json)>) -> Result<Json, Error> {
        if self.closed {
            return Err(Error::Closed);
        }
        self.transport.write_line(&encode_request_line(fields))?;
        let line = match self.transport.read_line(self.default_timeout) {
            Ok(line) => line,
            Err(e @ Error::Timeout(_)) => {
                self.closed = true;
                self.transport.close(CLOSE_TIMEOUT);
                return Err(e);
            }
            Err(e) => return Err(e),
        };
        let resp = decode_response_line(&line)?;
        if resp.ok {
            Ok(resp.fields)
        } else if let Some(e) = response_error(&resp.fields) {
            Err(e)
        } else {
            Err(Error::Protocol(
                "response has ok=false with no error field".to_string(),
            ))
        }
    }

    pub(crate) fn query(&mut self, sql: &str, params: &[Value]) -> Result<QueryResult, Error> {
        let mut fields = vec![("op", Json::str("query")), ("sql", Json::str(sql))];
        if let Some(p) = encode_params(params)? {
            fields.push(("params", p));
        }
        decode_query_response(&self.call(fields)?)
    }

    pub(crate) fn exec(&mut self, sql: &str, params: &[Value]) -> Result<ExecResult, Error> {
        let mut fields = vec![("op", Json::str("exec")), ("sql", Json::str(sql))];
        if let Some(p) = encode_params(params)? {
            fields.push(("params", p));
        }
        decode_exec_response(&self.call(fields)?)
    }

    pub(crate) fn snapshot(&mut self, opts: SnapshotOptions) -> Result<SnapshotResult, Error> {
        let mut fields = vec![("op", Json::str("snapshot"))];
        if let Some(filename) = &opts.filename {
            fields.push(("filename", Json::str(filename.as_str())));
        }
        if opts.sqlite {
            fields.push(("sqlite", Json::Bool(true)));
        }
        if opts.timestamp {
            fields.push(("timestamp", Json::Bool(true)));
        }
        decode_snapshot_response(&self.call(fields)?)
    }

    pub(crate) fn load(&mut self, path: &str) -> Result<(), Error> {
        self.call(vec![("op", Json::str("load")), ("path", Json::str(path))])
            .map(|_| ())
    }

    pub(crate) fn inspect(&mut self) -> Result<InspectResult, Error> {
        decode_inspect_response(&self.call(vec![("op", Json::str("inspect"))])?)
    }

    pub(crate) fn tables(&mut self) -> Result<TablesResult, Error> {
        decode_tables_response(&self.call(vec![("op", Json::str("tables"))])?)
    }

    pub(crate) fn schema(&mut self, table: Option<&str>) -> Result<SchemaResult, Error> {
        let mut fields = vec![("op", Json::str("schema"))];
        if let Some(t) = table {
            fields.push(("table", Json::str(t)));
        }
        decode_schema_response(&self.call(fields)?)
    }

    pub(crate) fn dump(&mut self, pattern: Option<&str>) -> Result<DumpResult, Error> {
        let mut fields = vec![("op", Json::str("dump"))];
        if let Some(p) = pattern {
            fields.push(("pattern", Json::str(p)));
        }
        decode_dump_response(&self.call(fields)?)
    }

    /// Gracefully ends the connection: sends the close op best-effort,
    /// then follows through with the transport's own shutdown regardless
    /// of whether a response arrives. Idempotent.
    pub(crate) fn close(&mut self) {
        if self.closed {
            return;
        }
        self.closed = true;
        let sent = self
            .transport
            .write_line(&encode_request_line(vec![("op", Json::str("close"))]))
            .is_ok();
        if sent {
            // Best-effort: read (and discard) the close op's own response
            // line before tearing the transport down. Whether this
            // succeeds, times out, or errors doesn't change what happens
            // next -- the shutdown below ends the connection either way --
            // but skipping this read would leave the direct transport's
            // background reader thread trying to deliver that line
            // through its 1-slot channel with nothing left to ever call
            // read_line() and drain it (see transport::reader's doc
            // comment on ThreadedReader::join for what that would cost).
            let _ = self.transport.read_line(Some(CLOSE_TIMEOUT));
        }
        self.transport.close(CLOSE_TIMEOUT);
    }
}

impl<T: Transport> Drop for Session<T> {
    fn drop(&mut self) {
        // Best-effort, idempotent: this is Rust's answer to Python's
        // `with` / TypeScript's `await using` / Go's `defer c.Close()` --
        // and it cannot be forgotten, since it runs automatically.
        self.close();
    }
}

/// Options for `snapshot()`. All fields default to "let the server
/// decide" (an omitted filename, `sqlite: false`, `timestamp: false`).
#[derive(Debug, Clone, Default)]
pub struct SnapshotOptions {
    pub filename: Option<String>,
    pub sqlite: bool,
    pub timestamp: bool,
}
