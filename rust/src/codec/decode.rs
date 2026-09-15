//! Decoding: turning wire JSON (already parsed into a [`Json`] tree by
//! `codec::json`) into this crate's `Value`/result types.

use crate::error::Error;
use crate::value::{Row, Value};

use super::base64;
use super::json::{self, Json};

/// The connection's own greeting, read once before any request is sent.
#[derive(Debug, Clone, PartialEq)]
pub struct Hello {
    pub protocol: i64,
    pub version: String,
    pub product: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct QueryResult {
    pub columns: Vec<String>,
    pub rows: Vec<Row>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ExecResult {
    pub rows_affected: i64,
    pub last_insert_id: i64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SnapshotResult {
    pub path: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct InspectResult {
    pub has_data: bool,
    pub version: Option<i64>,
    pub data_length: Option<i64>,
    pub source: String,
    pub read_only: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TablesResult {
    pub tables: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SchemaResult {
    pub schema: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DumpResult {
    pub sql: String,
}

/// One decoded response line: whether it was `ok`, and the full parsed
/// object (every top-level field, including `"ok"` itself) -- op decoders
/// below only look at the fields relevant to them, but the conformance
/// runner's partial-match comparisons need the whole thing.
pub(crate) struct DecodedResponse {
    pub(crate) ok: bool,
    pub(crate) fields: Json,
}

/// Decodes a hello line. Does not validate the protocol number -- that's
/// the caller's job, since only it knows what happens next (protocol.md).
pub(crate) fn decode_hello(line: &[u8]) -> Result<Hello, Error> {
    let v = json::parse(line).map_err(|e| Error::Protocol(format!("invalid hello line: {e}")))?;
    if v.as_object().is_none() {
        return Err(Error::Protocol(
            "hello line is not a JSON object".to_string(),
        ));
    }
    Ok(Hello {
        protocol: required_int(&v, "protocol")?,
        version: required_str(&v, "version")?.to_string(),
        product: required_str(&v, "product")?.to_string(),
    })
}

/// Decodes one response line into (ok, the full field set). Callers use
/// [`response_error`] on `fields` when `ok` is false.
pub(crate) fn decode_response_line(line: &[u8]) -> Result<DecodedResponse, Error> {
    let v =
        json::parse(line).map_err(|e| Error::Protocol(format!("invalid response line: {e}")))?;
    if v.as_object().is_none() {
        return Err(Error::Protocol(
            "response line is not a JSON object".to_string(),
        ));
    }
    let ok = v.get("ok").and_then(Json::as_bool).unwrap_or(false);
    Ok(DecodedResponse { ok, fields: v })
}

/// Extracts `{"error":{"code":...,"message":...}}` from a response's
/// fields, if present and well-formed.
pub(crate) fn response_error(fields: &Json) -> Option<Error> {
    let err = fields.get("error")?;
    let code = err.get("code")?.as_str()?;
    let message = err.get("message")?.as_str()?;
    Some(Error::Response {
        code: code.to_string(),
        message: message.to_string(),
    })
}

pub(crate) fn decode_value(raw: &Json) -> Result<Value, Error> {
    match raw {
        Json::Null => Ok(Value::Null),
        Json::Bool(b) => Err(Error::Protocol(format!(
            "unexpected boolean value in response: {b}"
        ))),
        Json::Number(n) => {
            if n.is_real() {
                let f = n.as_f64().ok_or_else(|| {
                    Error::Protocol(format!("invalid REAL token in response: {}", n.as_str()))
                })?;
                Ok(Value::Real(f))
            } else {
                let i = n.as_i64().ok_or_else(|| {
                    Error::Protocol(format!(
                        "integer value out of int64 range in response: {}",
                        n.as_str()
                    ))
                })?;
                Ok(Value::Integer(i))
            }
        }
        Json::String(s) => Ok(Value::Text(s.clone())),
        Json::Array(items) => {
            if items.len() != 1 {
                return Err(Error::Protocol(format!(
                    "protocol violation: BLOB array must have exactly 1 element, got {}",
                    items.len()
                )));
            }
            let s = items[0].as_str().ok_or_else(|| {
                Error::Protocol(
                    "protocol violation: BLOB array element is not a string".to_string(),
                )
            })?;
            let bytes = base64::decode(s)
                .map_err(|e| Error::Protocol(format!("invalid BLOB base64: {e}")))?;
            Ok(Value::Blob(bytes))
        }
        Json::Object(_) => Err(Error::Protocol(
            "unexpected object value in response".to_string(),
        )),
    }
}

pub(crate) fn decode_rows(raw: &Json) -> Result<Vec<Row>, Error> {
    let arr = raw
        .as_array()
        .ok_or_else(|| Error::Protocol("rows field is not an array".to_string()))?;
    let mut rows = Vec::with_capacity(arr.len());
    for (i, row) in arr.iter().enumerate() {
        let row_arr = row
            .as_array()
            .ok_or_else(|| Error::Protocol(format!("rows[{i}] is not an array")))?;
        let mut decoded = Vec::with_capacity(row_arr.len());
        for v in row_arr {
            decoded.push(decode_value(v).map_err(|e| match e {
                Error::Protocol(m) => Error::Protocol(format!("rows[{i}]: {m}")),
                other => other,
            })?);
        }
        rows.push(decoded);
    }
    Ok(rows)
}

fn required<'a>(v: &'a Json, key: &str) -> Result<&'a Json, Error> {
    v.get(key)
        .ok_or_else(|| Error::Protocol(format!("response missing field {key:?}")))
}

fn required_str<'a>(v: &'a Json, key: &str) -> Result<&'a str, Error> {
    required(v, key)?
        .as_str()
        .ok_or_else(|| Error::Protocol(format!("response field {key:?} is not a string")))
}

fn required_bool(v: &Json, key: &str) -> Result<bool, Error> {
    required(v, key)?
        .as_bool()
        .ok_or_else(|| Error::Protocol(format!("response field {key:?} is not a bool")))
}

fn required_int(v: &Json, key: &str) -> Result<i64, Error> {
    let n = required(v, key)?
        .as_number()
        .ok_or_else(|| Error::Protocol(format!("response field {key:?} is not an integer")))?;
    n.as_i64().ok_or_else(|| {
        Error::Protocol(format!(
            "response field {key:?} is not a valid int64: {}",
            n.as_str()
        ))
    })
}

fn required_optional_int(v: &Json, key: &str) -> Result<Option<i64>, Error> {
    let f = required(v, key)?;
    if f.is_null() {
        return Ok(None);
    }
    let n = f.as_number().ok_or_else(|| {
        Error::Protocol(format!("response field {key:?} is not an integer or null"))
    })?;
    Ok(Some(n.as_i64().ok_or_else(|| {
        Error::Protocol(format!(
            "response field {key:?} is not a valid int64: {}",
            n.as_str()
        ))
    })?))
}

fn required_str_list(v: &Json, key: &str) -> Result<Vec<String>, Error> {
    let arr = required(v, key)?
        .as_array()
        .ok_or_else(|| Error::Protocol(format!("response field {key:?} is not an array")))?;
    let mut out = Vec::with_capacity(arr.len());
    for item in arr {
        let s = item.as_str().ok_or_else(|| {
            Error::Protocol(format!("response field {key:?} is not a string array"))
        })?;
        out.push(s.to_string());
    }
    Ok(out)
}

pub(crate) fn decode_query_response(fields: &Json) -> Result<QueryResult, Error> {
    Ok(QueryResult {
        columns: required_str_list(fields, "columns")?,
        rows: decode_rows(required(fields, "rows")?)?,
    })
}

pub(crate) fn decode_exec_response(fields: &Json) -> Result<ExecResult, Error> {
    Ok(ExecResult {
        rows_affected: required_int(fields, "rows_affected")?,
        last_insert_id: required_int(fields, "last_insert_id")?,
    })
}

pub(crate) fn decode_snapshot_response(fields: &Json) -> Result<SnapshotResult, Error> {
    Ok(SnapshotResult {
        path: required_str(fields, "path")?.to_string(),
    })
}

pub(crate) fn decode_inspect_response(fields: &Json) -> Result<InspectResult, Error> {
    Ok(InspectResult {
        has_data: required_bool(fields, "has_data")?,
        version: required_optional_int(fields, "version")?,
        data_length: required_optional_int(fields, "data_length")?,
        source: required_str(fields, "source")?.to_string(),
        read_only: required_bool(fields, "read_only")?,
    })
}

pub(crate) fn decode_tables_response(fields: &Json) -> Result<TablesResult, Error> {
    Ok(TablesResult {
        tables: required_str_list(fields, "tables")?,
    })
}

pub(crate) fn decode_schema_response(fields: &Json) -> Result<SchemaResult, Error> {
    Ok(SchemaResult {
        schema: required_str_list(fields, "schema")?,
    })
}

pub(crate) fn decode_dump_response(fields: &Json) -> Result<DumpResult, Error> {
    Ok(DumpResult {
        sql: required_str(fields, "sql")?.to_string(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hello_ok() {
        let h = decode_hello(br#"{"protocol":1,"version":"v0.1.1","product":"SanDBox"}"#).unwrap();
        assert_eq!(
            h,
            Hello {
                protocol: 1,
                version: "v0.1.1".into(),
                product: "SanDBox".into()
            }
        );
    }

    #[test]
    fn hello_does_not_validate_protocol_number() {
        // decode_hello only parses; comparing against PROTOCOL is the
        // caller's job (protocol.md, and Python's decode_hello docstring).
        let h = decode_hello(br#"{"protocol":999,"version":"x","product":"SanDBox"}"#).unwrap();
        assert_eq!(h.protocol, 999);
    }

    #[test]
    fn hello_missing_field_and_invalid_json() {
        assert!(decode_hello(br#"{"protocol":1,"version":"x"}"#).is_err());
        assert!(decode_hello(b"not json").is_err());
        assert!(decode_hello(b"[1,2,3]").is_err());
    }

    #[test]
    fn response_ok_true_carries_every_field() {
        let r =
            decode_response_line(br#"{"ok":true,"columns":["a"],"rows":[],"extra":"x"}"#).unwrap();
        assert!(r.ok);
        assert_eq!(r.fields.get("extra").and_then(Json::as_str), Some("x"));
    }

    #[test]
    fn response_ok_false_with_and_without_error_object() {
        let r = decode_response_line(
            br#"{"ok":false,"error":{"code":"bad_request","message":"nope"}}"#,
        )
        .unwrap();
        assert!(!r.ok);
        let e = response_error(&r.fields).unwrap();
        assert!(e.is_code("bad_request"));

        let r = decode_response_line(br#"{"ok":false}"#).unwrap();
        assert!(!r.ok);
        assert!(response_error(&r.fields).is_none());
    }

    #[test]
    fn blob_array_wrong_length_rejected() {
        assert!(decode_value(&Json::Array(vec![])).is_err());
        assert!(decode_value(&Json::Array(vec![Json::str("a"), Json::str("b")])).is_err());
    }

    #[test]
    fn bool_in_cell_rejected() {
        assert!(decode_value(&Json::Bool(true)).is_err());
    }

    #[test]
    fn rows_not_array_and_row_not_array_rejected_with_index() {
        assert!(decode_rows(&Json::Null).is_err());
        let err = decode_rows(&Json::Array(vec![Json::Null])).unwrap_err();
        assert!(matches!(err, Error::Protocol(m) if m.contains("rows[0]")));
    }

    #[test]
    fn decode_query_exec_snapshot() {
        let v = json::parse(br#"{"ok":true,"columns":["x"],"rows":[[1]]}"#).unwrap();
        let r = decode_query_response(&v).unwrap();
        assert_eq!(r.columns, vec!["x"]);
        assert_eq!(r.rows, vec![vec![Value::Integer(1)]]);

        let v = json::parse(br#"{"ok":true,"rows_affected":2,"last_insert_id":9}"#).unwrap();
        let r = decode_exec_response(&v).unwrap();
        assert_eq!(
            r,
            ExecResult {
                rows_affected: 2,
                last_insert_id: 9
            }
        );

        let v = json::parse(br#"{"ok":true,"path":"snap.bin"}"#).unwrap();
        assert_eq!(
            decode_snapshot_response(&v).unwrap(),
            SnapshotResult {
                path: "snap.bin".into()
            }
        );
    }

    #[test]
    fn decode_inspect_with_and_without_nulls() {
        let v = json::parse(
            br#"{"ok":true,"has_data":true,"version":3,"data_length":100,"source":"embedded","read_only":false}"#,
        )
        .unwrap();
        assert_eq!(
            decode_inspect_response(&v).unwrap(),
            InspectResult {
                has_data: true,
                version: Some(3),
                data_length: Some(100),
                source: "embedded".into(),
                read_only: false,
            }
        );

        let v = json::parse(
            br#"{"ok":true,"has_data":false,"version":null,"data_length":null,"source":"none","read_only":true}"#,
        )
        .unwrap();
        assert_eq!(
            decode_inspect_response(&v).unwrap(),
            InspectResult {
                has_data: false,
                version: None,
                data_length: None,
                source: "none".into(),
                read_only: true
            },
        );
    }

    #[test]
    fn decode_tables_schema_dump() {
        let v = json::parse(br#"{"ok":true,"tables":["a","b"]}"#).unwrap();
        assert_eq!(
            decode_tables_response(&v).unwrap(),
            TablesResult {
                tables: vec!["a".into(), "b".into()]
            }
        );

        let v = json::parse(br#"{"ok":true,"schema":["CREATE TABLE a(x)"]}"#).unwrap();
        assert_eq!(
            decode_schema_response(&v).unwrap(),
            SchemaResult {
                schema: vec!["CREATE TABLE a(x)".into()]
            }
        );

        let v = json::parse(br#"{"ok":true,"sql":"INSERT INTO a VALUES(1);"}"#).unwrap();
        assert_eq!(
            decode_dump_response(&v).unwrap(),
            DumpResult {
                sql: "INSERT INTO a VALUES(1);".into()
            }
        );
    }

    #[test]
    fn missing_required_field_is_protocol_error() {
        let v = json::parse(br#"{"ok":true,"columns":["x"]}"#).unwrap();
        assert!(decode_query_response(&v).is_err());
    }

    #[test]
    fn decode_value_large_integer_and_real() {
        let n = Json::Number(json::NumberToken::new("9223372036854775807".to_string()));
        assert_eq!(decode_value(&n).unwrap(), Value::Integer(i64::MAX));

        let n = Json::Number(json::NumberToken::new("88.0".to_string()));
        assert_eq!(decode_value(&n).unwrap(), Value::Real(88.0));

        let n = Json::Number(json::NumberToken::new("9e999".to_string()));
        assert_eq!(decode_value(&n).unwrap(), Value::Real(f64::INFINITY));
    }
}
