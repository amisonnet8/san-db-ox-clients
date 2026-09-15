//! Pure encode/decode layer for SanDBox's stdio protocol.
//!
//! This layer knows nothing about I/O or process/socket lifetimes -- it
//! only converts between this crate's `Value`/result types and the wire
//! representation (protocol.md). Keeping it pure is what lets
//! `rust-netcheck` mechanically confirm it never pulls in networking or
//! process machinery (architecture.md's codec/transport split).
#![forbid(unsafe_code)]

mod base64;
mod decode;
mod encode;
mod json;

pub(crate) use decode::{
    decode_dump_response, decode_exec_response, decode_hello, decode_inspect_response,
    decode_query_response, decode_response_line, decode_schema_response, decode_snapshot_response,
    decode_tables_response, response_error,
};
pub(crate) use encode::{encode_params, encode_request_line};
pub(crate) use json::Json;

pub use decode::{
    DumpResult, ExecResult, Hello, InspectResult, QueryResult, SchemaResult, SnapshotResult,
    TablesResult,
};

/// The stdio protocol version this driver speaks (protocol.md).
pub const PROTOCOL: i64 = 1;

// json/base64/encode/decode's own #[cfg(test)] modules exercise this
// layer's behavior in detail; the conformance suite (src/tests/) is what
// exercises it against real protocol traffic byte-for-byte.
#[cfg(test)]
pub(crate) use json::parse as parse_json;
#[cfg(test)]
pub(crate) use json::write as write_json;
