//! A hand-written, dependency-free JSON parser and writer.
//!
//! This is the one place Go/Python/TypeScript could each delegate to a
//! battle-tested standard-library parser and Rust cannot (no JSON in
//! `std`). Numbers keep their exact source token in [`NumberToken`] rather
//! than being converted to a Rust number type here -- that conversion (and
//! the INTEGER-vs-REAL classification it drives) is `codec::decode`'s job,
//! not the parser's, matching how upstream's own 64-bit integer fidelity
//! requirement is handled in the other three drivers in this repository.
//!
//! Self-contained on purpose: no `use crate::...`, so this module can be
//! reasoned about (and netchecked) in isolation from the rest of the
//! codec, let alone the transport layer.

use std::fmt;

/// A parsed JSON value.
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum Json {
    Null,
    Bool(bool),
    Number(NumberToken),
    String(String),
    Array(Vec<Json>),
    /// A `Vec`, not a map: preserves the key order values arrived in (or
    /// were constructed in), so a request can be re-emitted byte-for-byte
    /// and a response's field order is never silently reshuffled.
    Object(Vec<(String, Json)>),
}

impl Json {
    pub(crate) fn str(s: impl Into<String>) -> Self {
        Json::String(s.into())
    }

    pub(crate) fn as_object(&self) -> Option<&[(String, Json)]> {
        match self {
            Json::Object(v) => Some(v),
            _ => None,
        }
    }

    /// Looks up a key in an object by linear scan (objects here are always
    /// small -- at most a handful of protocol fields -- so this is simpler
    /// than pulling in a map type for no real benefit).
    pub(crate) fn get(&self, key: &str) -> Option<&Json> {
        self.as_object()?
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v)
    }

    pub(crate) fn as_str(&self) -> Option<&str> {
        match self {
            Json::String(s) => Some(s.as_str()),
            _ => None,
        }
    }

    pub(crate) fn as_bool(&self) -> Option<bool> {
        match self {
            Json::Bool(b) => Some(*b),
            _ => None,
        }
    }

    pub(crate) fn as_array(&self) -> Option<&[Json]> {
        match self {
            Json::Array(v) => Some(v),
            _ => None,
        }
    }

    pub(crate) fn as_number(&self) -> Option<&NumberToken> {
        match self {
            Json::Number(n) => Some(n),
            _ => None,
        }
    }

    pub(crate) fn is_null(&self) -> bool {
        matches!(self, Json::Null)
    }
}

/// A JSON number exactly as it appeared on the wire (or exactly as it will
/// be written to the wire), preserved as text rather than parsed into a
/// Rust number type. This is what lets `88` and `88.0` compare unequal
/// (the conformance suite's byte-exactness requirement) and what lets an
/// integer outside `f64`'s exact range round-trip without loss.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct NumberToken(String);

impl NumberToken {
    pub(crate) fn new(raw: String) -> Self {
        NumberToken(raw)
    }

    pub(crate) fn as_str(&self) -> &str {
        &self.0
    }

    /// A token containing `.`, `e`, or `E` is REAL; otherwise it's INTEGER.
    /// Matches Go's `decodeNumber` and TypeScript's `valueNumberPolicy`.
    pub(crate) fn is_real(&self) -> bool {
        self.0.bytes().any(|b| matches!(b, b'.' | b'e' | b'E'))
    }

    pub(crate) fn as_i64(&self) -> Option<i64> {
        self.0.parse().ok()
    }

    /// `f64::from_str` saturates an out-of-range magnitude to `±inf`
    /// instead of erroring (unlike Go's `strconv.ParseFloat`, which returns
    /// `+Inf`/`-Inf` together with `ErrRange`) -- so `9e999`/`-9e999`
    /// (upstream's encoding of +Inf/-Inf, protocol.md) parse straight
    /// through with no special case needed here.
    pub(crate) fn as_f64(&self) -> Option<f64> {
        self.0.parse().ok()
    }
}

#[derive(Debug, Clone)]
pub(crate) struct JsonError(pub(crate) String);

impl fmt::Display for JsonError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for JsonError {}

/// Parses exactly one JSON value from `input`, which must be valid UTF-8.
/// Trailing non-whitespace after the value is an error -- callers always
/// hand this one already-newline-stripped protocol line.
pub(crate) fn parse(input: &[u8]) -> Result<Json, JsonError> {
    let s = std::str::from_utf8(input).map_err(|e| JsonError(format!("invalid UTF-8: {e}")))?;
    let mut p = Parser::new(s);
    p.skip_ws();
    let v = p.parse_value()?;
    p.skip_ws();
    if p.pos != p.b.len() {
        return Err(p.err("trailing data after JSON value"));
    }
    Ok(v)
}

/// Writes `value` as compact JSON (no extraneous whitespace). Number
/// tokens are written verbatim; object keys are written in the order they
/// appear in `Json::Object`'s `Vec`.
pub(crate) fn write(value: &Json) -> String {
    let mut out = String::new();
    write_value(value, &mut out);
    out
}

fn write_value(value: &Json, out: &mut String) {
    match value {
        Json::Null => out.push_str("null"),
        Json::Bool(true) => out.push_str("true"),
        Json::Bool(false) => out.push_str("false"),
        Json::Number(n) => out.push_str(n.as_str()),
        Json::String(s) => write_string(s, out),
        Json::Array(items) => {
            out.push('[');
            for (i, item) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_value(item, out);
            }
            out.push(']');
        }
        Json::Object(pairs) => {
            out.push('{');
            for (i, (k, v)) in pairs.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_string(k, out);
                out.push(':');
                write_value(v, out);
            }
            out.push('}');
        }
    }
}

/// Escapes only what JSON requires (`"`, `\`, and control characters
/// U+0000-U+001F); non-ASCII characters pass through as UTF-8 rather than
/// being escaped as `\uXXXX` (matching Python's `ensure_ascii=False`).
fn write_string(s: &str, out: &mut String) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000C}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

struct Parser<'a> {
    s: &'a str,
    b: &'a [u8],
    pos: usize,
}

impl<'a> Parser<'a> {
    fn new(s: &'a str) -> Self {
        Parser {
            s,
            b: s.as_bytes(),
            pos: 0,
        }
    }

    fn err(&self, msg: &str) -> JsonError {
        JsonError(format!("{msg} (at byte offset {})", self.pos))
    }

    fn peek_byte(&self) -> Option<u8> {
        self.b.get(self.pos).copied()
    }

    fn bump_byte(&mut self) -> Option<u8> {
        let b = self.peek_byte();
        if b.is_some() {
            self.pos += 1;
        }
        b
    }

    fn expect_byte(&mut self, want: u8) -> Result<(), JsonError> {
        match self.bump_byte() {
            Some(b) if b == want => Ok(()),
            _ => Err(self.err(&format!("expected {:?}", want as char))),
        }
    }

    fn skip_ws(&mut self) {
        while matches!(self.peek_byte(), Some(b' ' | b'\t' | b'\n' | b'\r')) {
            self.pos += 1;
        }
    }

    fn parse_value(&mut self) -> Result<Json, JsonError> {
        self.skip_ws();
        match self.peek_byte() {
            Some(b'{') => self.parse_object(),
            Some(b'[') => self.parse_array(),
            Some(b'"') => Ok(Json::String(self.parse_string()?)),
            Some(b't') => self.parse_literal("true", Json::Bool(true)),
            Some(b'f') => self.parse_literal("false", Json::Bool(false)),
            Some(b'n') => self.parse_literal("null", Json::Null),
            Some(b'-') | Some(b'0'..=b'9') => Ok(Json::Number(self.parse_number()?)),
            Some(c) => Err(self.err(&format!("unexpected character {:?}", c as char))),
            None => Err(self.err("unexpected end of input")),
        }
    }

    /// Also the mechanism by which bareword `NaN`/`Infinity`/`-Infinity`
    /// (upstream never emits these -- it uses `null`/`9e999`/`-9e999`,
    /// protocol.md) are rejected: none of them match "true"/"false"/"null",
    /// and none can complete as a number either (`-Infinity` fails in
    /// `parse_number` at the first non-digit after `-`).
    fn parse_literal(&mut self, lit: &str, val: Json) -> Result<Json, JsonError> {
        if self.s[self.pos..].starts_with(lit) {
            self.pos += lit.len();
            Ok(val)
        } else {
            Err(self.err(&format!("invalid literal, expected {lit:?}")))
        }
    }

    fn parse_number(&mut self) -> Result<NumberToken, JsonError> {
        let start = self.pos;
        if self.peek_byte() == Some(b'-') {
            self.pos += 1;
        }
        match self.peek_byte() {
            Some(b'0') => self.pos += 1,
            Some(b'1'..=b'9') => {
                while matches!(self.peek_byte(), Some(b'0'..=b'9')) {
                    self.pos += 1;
                }
            }
            _ => return Err(self.err("invalid number: expected a digit")),
        }
        if self.peek_byte() == Some(b'.') {
            self.pos += 1;
            let frac_start = self.pos;
            while matches!(self.peek_byte(), Some(b'0'..=b'9')) {
                self.pos += 1;
            }
            if self.pos == frac_start {
                return Err(self.err("invalid number: expected a digit after '.'"));
            }
        }
        if matches!(self.peek_byte(), Some(b'e' | b'E')) {
            self.pos += 1;
            if matches!(self.peek_byte(), Some(b'+' | b'-')) {
                self.pos += 1;
            }
            let exp_start = self.pos;
            while matches!(self.peek_byte(), Some(b'0'..=b'9')) {
                self.pos += 1;
            }
            if self.pos == exp_start {
                return Err(self.err("invalid number: expected a digit in the exponent"));
            }
        }
        // Every byte consumed above is ASCII, so this slice is always at a
        // char boundary and always valid UTF-8.
        Ok(NumberToken(self.s[start..self.pos].to_string()))
    }

    fn parse_string(&mut self) -> Result<String, JsonError> {
        self.expect_byte(b'"')?;
        let mut out = String::new();
        loop {
            match self.bump_byte() {
                None => return Err(self.err("unterminated string")),
                Some(b'"') => return Ok(out),
                Some(b'\\') => self.parse_escape(&mut out)?,
                Some(b) if b < 0x20 => {
                    return Err(self.err("unescaped control character in string"));
                }
                Some(b) if b < 0x80 => out.push(b as char),
                Some(_) => {
                    // A multi-byte UTF-8 sequence: back up to its first
                    // byte and decode the whole char at once via the
                    // validated &str (self.s is known-UTF-8, so this is
                    // always at a char boundary).
                    self.pos -= 1;
                    let ch = self.s[self.pos..]
                        .chars()
                        .next()
                        .expect("byte >= 0x80 starts a valid UTF-8 char in a validated &str");
                    out.push(ch);
                    self.pos += ch.len_utf8();
                }
            }
        }
    }

    fn parse_escape(&mut self, out: &mut String) -> Result<(), JsonError> {
        match self.bump_byte() {
            Some(b'"') => out.push('"'),
            Some(b'\\') => out.push('\\'),
            Some(b'/') => out.push('/'),
            Some(b'b') => out.push('\u{0008}'),
            Some(b'f') => out.push('\u{000C}'),
            Some(b'n') => out.push('\n'),
            Some(b'r') => out.push('\r'),
            Some(b't') => out.push('\t'),
            Some(b'u') => {
                let cp = self.parse_hex4()?;
                if (0xD800..=0xDBFF).contains(&cp) {
                    // High surrogate: a low surrogate must follow.
                    if self.bump_byte() != Some(b'\\') || self.bump_byte() != Some(b'u') {
                        return Err(self.err("unpaired UTF-16 surrogate"));
                    }
                    let low = self.parse_hex4()?;
                    if !(0xDC00..=0xDFFF).contains(&low) {
                        return Err(self.err("invalid low surrogate in \\u escape pair"));
                    }
                    let c =
                        0x10000u32 + ((u32::from(cp) - 0xD800) << 10) + (u32::from(low) - 0xDC00);
                    out.push(char::from_u32(c).ok_or_else(|| self.err("invalid surrogate pair"))?);
                } else if (0xDC00..=0xDFFF).contains(&cp) {
                    return Err(self.err("unpaired UTF-16 surrogate"));
                } else {
                    out.push(
                        char::from_u32(u32::from(cp))
                            .ok_or_else(|| self.err("invalid \\u escape"))?,
                    );
                }
            }
            _ => return Err(self.err("invalid escape sequence")),
        }
        Ok(())
    }

    fn parse_hex4(&mut self) -> Result<u16, JsonError> {
        let mut v: u16 = 0;
        for _ in 0..4 {
            let b = self
                .bump_byte()
                .ok_or_else(|| self.err("unexpected end of \\u escape"))?;
            let digit = match b {
                b'0'..=b'9' => b - b'0',
                b'a'..=b'f' => b - b'a' + 10,
                b'A'..=b'F' => b - b'A' + 10,
                _ => return Err(self.err("invalid hex digit in \\u escape")),
            };
            v = v * 16 + u16::from(digit);
        }
        Ok(v)
    }

    fn parse_array(&mut self) -> Result<Json, JsonError> {
        self.expect_byte(b'[')?;
        self.skip_ws();
        let mut items = Vec::new();
        if self.peek_byte() == Some(b']') {
            self.pos += 1;
            return Ok(Json::Array(items));
        }
        loop {
            items.push(self.parse_value()?);
            self.skip_ws();
            match self.bump_byte() {
                Some(b',') => {
                    self.skip_ws();
                    continue;
                }
                Some(b']') => return Ok(Json::Array(items)),
                _ => return Err(self.err("expected ',' or ']' in array")),
            }
        }
    }

    fn parse_object(&mut self) -> Result<Json, JsonError> {
        self.expect_byte(b'{')?;
        self.skip_ws();
        let mut pairs = Vec::new();
        if self.peek_byte() == Some(b'}') {
            self.pos += 1;
            return Ok(Json::Object(pairs));
        }
        loop {
            self.skip_ws();
            if self.peek_byte() != Some(b'"') {
                return Err(self.err("expected a string key in object"));
            }
            let key = self.parse_string()?;
            self.skip_ws();
            self.expect_byte(b':')?;
            self.skip_ws();
            let val = self.parse_value()?;
            pairs.push((key, val));
            self.skip_ws();
            match self.bump_byte() {
                Some(b',') => {
                    self.skip_ws();
                    continue;
                }
                Some(b'}') => return Ok(Json::Object(pairs)),
                _ => return Err(self.err("expected ',' or '}' in object")),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn num(s: &str) -> Json {
        Json::Number(NumberToken::new(s.to_string()))
    }

    #[test]
    fn parses_literals() {
        assert_eq!(parse(b"null").unwrap(), Json::Null);
        assert_eq!(parse(b"true").unwrap(), Json::Bool(true));
        assert_eq!(parse(b"false").unwrap(), Json::Bool(false));
    }

    #[test]
    fn parses_whitespace_around_value() {
        assert_eq!(parse(b"  \t\n 42 \r\n").unwrap(), num("42"));
    }

    #[test]
    fn rejects_trailing_garbage() {
        assert!(parse(b"42 43").is_err());
        assert!(parse(b"{}x").is_err());
    }

    #[test]
    fn number_tokens_are_kept_verbatim() {
        // The core fidelity property: 88 and 88.0 are different tokens.
        assert_eq!(parse(b"88").unwrap(), num("88"));
        assert_eq!(parse(b"88.0").unwrap(), num("88.0"));
        assert_ne!(parse(b"88").unwrap(), parse(b"88.0").unwrap());
    }

    #[test]
    fn integer_boundaries_round_trip_exactly() {
        let max = parse(b"9223372036854775807").unwrap();
        assert_eq!(max.as_number().unwrap().as_i64(), Some(i64::MAX));
        let min = parse(b"-9223372036854775808").unwrap();
        assert_eq!(min.as_number().unwrap().as_i64(), Some(i64::MIN));
    }

    #[test]
    fn out_of_range_integer_token_has_no_i64_value() {
        let v = parse(b"9223372036854775808").unwrap();
        assert_eq!(v.as_number().unwrap().as_i64(), None);
    }

    #[test]
    fn real_overflow_saturates_to_infinity_without_erroring() {
        // Rust's f64::from_str differs from Go's strconv.ParseFloat here:
        // Go returns +Inf *together with* ErrRange; Rust just saturates.
        // Pinned deliberately (see NumberToken::as_f64's doc comment).
        let v = parse(b"9e999").unwrap();
        assert_eq!(v.as_number().unwrap().as_f64(), Some(f64::INFINITY));
        let v = parse(b"-9e999").unwrap();
        assert_eq!(v.as_number().unwrap().as_f64(), Some(f64::NEG_INFINITY));
    }

    #[test]
    fn rejects_bareword_nan_and_infinity() {
        assert!(parse(b"NaN").is_err());
        assert!(parse(b"Infinity").is_err());
        assert!(parse(b"-Infinity").is_err());
    }

    #[test]
    fn rejects_malformed_numbers() {
        assert!(parse(b"01").is_err()); // leading zero
        assert!(parse(b"+1").is_err()); // leading plus
        assert!(parse(b"1.").is_err()); // no digit after '.'
        assert!(parse(b"1e").is_err()); // no digit in exponent
        assert!(parse(b".5").is_err()); // no leading digit
    }

    #[test]
    fn rejects_trailing_commas() {
        assert!(parse(b"[1,2,]").is_err());
        assert!(parse(br#"{"a":1,}"#).is_err());
    }

    #[test]
    fn rejects_comments() {
        assert!(parse(b"1 // comment").is_err());
        assert!(parse(b"/* c */ 1").is_err());
    }

    #[test]
    fn object_preserves_key_order() {
        let v = parse(br#"{"b":1,"a":2,"c":3}"#).unwrap();
        let obj = v.as_object().unwrap();
        let keys: Vec<&str> = obj.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(keys, vec!["b", "a", "c"]);
    }

    #[test]
    fn string_escapes_round_trip() {
        let v = parse(br#""a\"b\\c\/d\be\ff\ng\rh\ti""#).unwrap();
        assert_eq!(
            v,
            Json::String("a\"b\\c/d\u{8}e\u{c}f\ng\rh\ti".to_string())
        );
    }

    #[test]
    fn string_rejects_unescaped_control_char() {
        let mut s = b"\"a".to_vec();
        s.push(0x01);
        s.extend_from_slice(b"b\"");
        assert!(parse(&s).is_err());
    }

    #[test]
    fn string_u_escape_and_surrogate_pair() {
        let src = b"\"\\u00e9\""; // e with acute accent, as a \u escape
        let v = parse(src).unwrap();
        assert_eq!(v, Json::String("\u{e9}".to_string()));
        // U+1F600 (grinning face) as a UTF-16 surrogate pair.
        let src = b"\"\\ud83d\\ude00\"";
        let v = parse(src).unwrap();
        assert_eq!(v, Json::String("\u{1F600}".to_string()));
    }

    #[test]
    fn string_rejects_unpaired_surrogate() {
        assert!(parse(br#""\ud83d""#).is_err());
        assert!(parse(br#""\ude00""#).is_err());
    }

    #[test]
    fn non_ascii_passes_through_utf8() {
        let v = parse("\"日本語\"".as_bytes()).unwrap();
        assert_eq!(v, Json::String("日本語".to_string()));
        assert_eq!(write(&v), "\"日本語\"");
    }

    #[test]
    fn write_number_is_verbatim() {
        assert_eq!(write(&num("9223372036854775807")), "9223372036854775807");
        assert_eq!(write(&num("88.0")), "88.0");
    }

    #[test]
    fn write_escapes_control_characters_as_u_escape() {
        let v = Json::String("a\u{1}b".to_string());
        assert_eq!(write(&v), "\"a\\u0001b\"");
    }

    #[test]
    fn write_object_preserves_order_and_is_compact() {
        let v = Json::Object(vec![
            ("op".to_string(), Json::str("query")),
            ("sql".to_string(), Json::str("SELECT 1")),
        ]);
        assert_eq!(write(&v), r#"{"op":"query","sql":"SELECT 1"}"#);
    }

    #[test]
    fn parse_then_write_is_byte_exact_for_numbers() {
        for token in [
            "0", "-0", "88.0", "-88.0", "1e21", "5e-7", "9e999", "-9e999",
        ] {
            let v = parse(token.as_bytes()).unwrap();
            assert_eq!(write(&v), token, "round trip mismatch for {token}");
        }
    }

    #[test]
    fn nested_structures() {
        let src = br#"{"op":"query","params":[1,2.5,"x",null,true,[1,2]]}"#;
        let v = parse(src).unwrap();
        assert_eq!(write(&v), std::str::from_utf8(src).unwrap());
    }

    #[test]
    fn deeply_nested_array_does_not_stack_overflow_at_modest_depth() {
        let depth = 200;
        let mut s = String::new();
        for _ in 0..depth {
            s.push('[');
        }
        s.push('1');
        for _ in 0..depth {
            s.push(']');
        }
        assert!(parse(s.as_bytes()).is_ok());
    }
}
