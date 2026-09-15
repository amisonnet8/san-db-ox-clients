//! Error types for the driver.
//!
//! `Error::Response` carries the wire's own `code`/`message` pair; branch on
//! `code()` (compare it against the `CODE_*` constants below), never on the
//! message text -- upstream's wording can change between releases without
//! notice.

use std::fmt;
use std::sync::Arc;

/// The 5 stable error codes a SanDBox response can carry.
pub const CODE_SQLITE_ERROR: &str = "sqlite_error";
pub const CODE_BAD_REQUEST: &str = "bad_request";
pub const CODE_IO_ERROR: &str = "io_error";
pub const CODE_UNSUPPORTED_OP: &str = "unsupported_op";
pub const CODE_READ_ONLY: &str = "read_only";

/// Every error this driver can return.
#[derive(Debug, Clone)]
#[non_exhaustive]
pub enum Error {
    /// A stdio protocol error response (`{"ok":false,"error":{...}}`).
    /// Compare `code` against the `CODE_*` constants; `message` is
    /// upstream's own text and can change wording between releases without
    /// notice, so never branch on it.
    Response { code: String, message: String },
    /// The driver saw something that violates the stdio contract.
    Protocol(String),
    /// A call did not receive a response within its timeout.
    Timeout(String),
    /// An I/O error from the underlying transport.
    ///
    /// Wrapped in `Arc`, not `Box`: transports memoize a terminal error and
    /// re-deliver it on every subsequent read, which needs this type to be
    /// `Clone`, and `std::io::Error` is not.
    Io(Arc<std::io::Error>),
    /// The connection is closed (or was made permanently unusable by a
    /// prior timeout) and can no longer be used.
    Closed,
}

impl Error {
    /// `Some(code)` only for a response error (`Error::Response`). Callers
    /// branch on this, never on the message text.
    pub fn code(&self) -> Option<&str> {
        match self {
            Error::Response { code, .. } => Some(code.as_str()),
            _ => None,
        }
    }

    /// Shorthand for `self.code() == Some(code)`.
    pub fn is_code(&self, code: &str) -> bool {
        self.code() == Some(code)
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Response { code, message } => write!(f, "{code}: {message}"),
            Error::Protocol(m) => write!(f, "protocol error: {m}"),
            Error::Timeout(m) => write!(f, "{m}"),
            Error::Io(e) => write!(f, "{e}"),
            Error::Closed => write!(f, "connection is closed"),
        }
    }
}

impl std::error::Error for Error {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Error::Io(e) => Some(&**e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(Arc::new(e))
    }
}

/// Convenience alias used throughout the crate's public API.
pub type Result<T> = std::result::Result<T, Error>;
