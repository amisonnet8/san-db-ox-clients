//! The `Value` type: SanDBox's value representation on the Rust side
//! (protocol.md). INTEGER maps to `i64`, REAL to `f64`, TEXT to `String`,
//! BLOB to `Vec<u8>`, and SQL NULL to `Value::Null`.

/// INTEGER's 64-bit range (SQLite/the protocol's own bounds).
pub const INT64_MIN: i64 = i64::MIN;
pub const INT64_MAX: i64 = i64::MAX;

/// A single cell value, either read out of a response or passed in as a
/// query/exec parameter.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Null,
    Integer(i64),
    Real(f64),
    Text(String),
    Blob(Vec<u8>),
}

/// A single result row: one `Value` per column, in column order.
pub type Row = Vec<Value>;

impl From<i64> for Value {
    fn from(v: i64) -> Self {
        Value::Integer(v)
    }
}

impl From<i32> for Value {
    fn from(v: i32) -> Self {
        Value::Integer(v as i64)
    }
}

impl From<u32> for Value {
    fn from(v: u32) -> Self {
        Value::Integer(v as i64)
    }
}

impl From<f64> for Value {
    fn from(v: f64) -> Self {
        Value::Real(v)
    }
}

impl From<f32> for Value {
    fn from(v: f32) -> Self {
        Value::Real(v as f64)
    }
}

impl From<&str> for Value {
    fn from(v: &str) -> Self {
        Value::Text(v.to_string())
    }
}

impl From<String> for Value {
    fn from(v: String) -> Self {
        Value::Text(v)
    }
}

impl From<&String> for Value {
    fn from(v: &String) -> Self {
        Value::Text(v.clone())
    }
}

impl From<Vec<u8>> for Value {
    fn from(v: Vec<u8>) -> Self {
        Value::Blob(v)
    }
}

impl From<&[u8]> for Value {
    fn from(v: &[u8]) -> Self {
        Value::Blob(v.to_vec())
    }
}

impl<const N: usize> From<&[u8; N]> for Value {
    fn from(v: &[u8; N]) -> Self {
        Value::Blob(v.to_vec())
    }
}

impl<T: Into<Value>> From<Option<T>> for Value {
    fn from(v: Option<T>) -> Self {
        match v {
            Some(v) => v.into(),
            None => Value::Null,
        }
    }
}

// Deliberately no `impl From<bool> for Value`: san-db-ox has no boolean
// SQLite type (protocol.md), and every other driver in this repository
// rejects `bool` as a param (Go's type switch, Python's explicit
// `isinstance(v, bool)` check ahead of `int` since `bool` subclasses `int`
// there, TypeScript's `ParamValue` union excluding it). Here the same
// decision is a compile error instead of a runtime one: `Value::from(true)`
// simply does not compile. See the compile_fail doctest in `lib.rs`.
