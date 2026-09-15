//! Encoding: turning this crate's `Value` type and request fields into
//! wire JSON.

use crate::error::Error;
use crate::value::Value;

use super::base64;
use super::json::{self, Json, NumberToken};

/// Formats a finite `f64` as a token that always binds REAL rather than
/// INTEGER upstream.
///
/// `{:?}` (`Debug`) is shortest-round-trip and always contains a `.` or an
/// exponent (`88.0`, `-0.0`, `1e21`, `5e-7`); `{}` (`Display`) would print
/// `88.0` as `88` and `1e21` as a 22-digit integer literal, either of which
/// san-db-ox would bind as INTEGER -- exactly the pitfall Go's `formatReal`
/// and TypeScript's `realToken` exist to avoid. The trailing guard is
/// belt-and-braces: Rust's docs do not *guarantee* the "." suffix, so this
/// must not silently depend on an unspecified formatting detail.
pub(crate) fn real_token(v: f64) -> String {
    let mut s = format!("{v:?}");
    if !s.contains(['.', 'e', 'E']) {
        s.push_str(".0");
    }
    s
}

/// Encodes a single param/cell value. Non-finite REALs and integers
/// outside i64 (unrepresentable by construction here, since `Value::Integer`
/// already holds an `i64`) are rejected before anything reaches the wire.
pub(crate) fn encode_value(v: &Value) -> Result<Json, Error> {
    match v {
        Value::Null => Ok(Json::Null),
        Value::Integer(i) => Ok(Json::Number(NumberToken::new(i.to_string()))),
        Value::Real(f) => {
            if !f.is_finite() {
                return Err(Error::Protocol(format!(
                    "REAL param must be finite (san-db-ox rejects NaN/Inf in params): {f:?}"
                )));
            }
            Ok(Json::Number(NumberToken::new(real_token(*f))))
        }
        Value::Text(s) => Ok(Json::String(s.clone())),
        Value::Blob(b) => Ok(Json::Array(vec![Json::str(base64::encode(b))])),
    }
}

/// Encodes a params slice, or `None` if the `"params"` field should be
/// omitted entirely (an empty params list, matching the other three
/// drivers).
pub(crate) fn encode_params(params: &[Value]) -> Result<Option<Json>, Error> {
    if params.is_empty() {
        return Ok(None);
    }
    let mut items = Vec::with_capacity(params.len());
    for (i, v) in params.iter().enumerate() {
        let encoded = encode_value(v).map_err(|e| match e {
            Error::Protocol(m) => Error::Protocol(format!("params[{i}]: {m}")),
            other => other,
        })?;
        items.push(encoded);
    }
    Ok(Some(Json::Array(items)))
}

/// Encodes a request object as one JSON Lines record, newline included.
/// `fields` is written as a JSON object in the given order (the object's
/// key order has no protocol meaning, but a stable order makes requests
/// easy to eyeball in a log).
pub(crate) fn encode_request_line(fields: Vec<(&'static str, Json)>) -> Vec<u8> {
    let obj = Json::Object(
        fields
            .into_iter()
            .map(|(k, v)| (k.to_string(), v))
            .collect(),
    );
    let mut s = json::write(&obj);
    s.push('\n');
    s.into_bytes()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn real_token_table() {
        assert_eq!(real_token(88.0), "88.0");
        assert_eq!(real_token(-0.0), "-0.0");
        assert_eq!(real_token(1e21), "1e21");
        assert_eq!(real_token(5e-7), "5e-7");
        assert_eq!(real_token(0.1), "0.1");
    }

    #[test]
    fn real_token_round_trips_bit_exact() {
        for v in [
            0.0,
            -0.0,
            1.0,
            -1.0,
            f64::MIN_POSITIVE,
            f64::MAX,
            f64::MIN,
            1e308,
            -1e-308,
            3.5,
        ] {
            let token = real_token(v);
            let parsed: f64 = token.parse().unwrap();
            assert_eq!(
                parsed.to_bits(),
                v.to_bits(),
                "round trip mismatch for {v} -> {token}"
            );
        }
    }

    #[test]
    fn non_finite_real_is_rejected() {
        let err = encode_value(&Value::Real(f64::NAN)).unwrap_err();
        assert!(matches!(&err, Error::Protocol(m) if m.contains("finite")));
        assert!(encode_value(&Value::Real(f64::INFINITY)).is_err());
        assert!(encode_value(&Value::Real(f64::NEG_INFINITY)).is_err());
    }

    #[test]
    fn integer_encodes_as_bare_digits() {
        assert_eq!(
            json::write(&encode_value(&Value::Integer(i64::MAX)).unwrap()),
            "9223372036854775807"
        );
        assert_eq!(
            json::write(&encode_value(&Value::Integer(i64::MIN)).unwrap()),
            "-9223372036854775808"
        );
    }

    #[test]
    fn empty_blob_encodes_as_single_empty_string_element() {
        let j = encode_value(&Value::Blob(vec![])).unwrap();
        assert_eq!(json::write(&j), r#"[""]"#);
    }

    #[test]
    fn empty_params_omits_the_field() {
        assert!(encode_params(&[]).unwrap().is_none());
    }

    #[test]
    fn param_error_is_wrapped_with_index() {
        let params = [Value::Integer(1), Value::Real(f64::NAN)];
        let err = encode_params(&params).unwrap_err();
        assert!(matches!(&err, Error::Protocol(m) if m.starts_with("params[1]:")));
    }

    #[test]
    fn request_line_ends_with_exactly_one_newline() {
        let line = encode_request_line(vec![
            ("op", Json::str("query")),
            ("sql", Json::str("SELECT 1")),
        ]);
        let s = String::from_utf8(line).unwrap();
        assert_eq!(s, "{\"op\":\"query\",\"sql\":\"SELECT 1\"}\n");
    }
}
