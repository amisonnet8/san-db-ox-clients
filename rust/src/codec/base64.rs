//! Hand-written Base64 (RFC 4648 standard alphabet, padded, no line
//! breaks) -- the wire encoding for BLOB values (protocol.md).
//!
//! Decoding is strict: any character outside the alphabet, misplaced or
//! wrong-count padding, or a length not a multiple of 4 is rejected. This
//! matters because at least one standard-library decoder in this
//! repository's other drivers is *not* strict by default -- TypeScript's
//! `Buffer.from(s, "base64")` silently drops invalid characters instead of
//! erroring, confirmed against a real build -- and a hand-written decoder
//! must not reproduce that failure mode.

use std::fmt;

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

#[derive(Debug, Clone)]
pub(crate) struct Base64Error(pub(crate) String);

impl fmt::Display for Base64Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for Base64Error {}

/// Encodes `data`. An empty slice encodes to `""` (so an empty BLOB is
/// `[""]` on the wire, per protocol.md).
pub(crate) fn encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0];
        let b1 = chunk.get(1).copied();
        let b2 = chunk.get(2).copied();
        let n =
            (u32::from(b0) << 16) | (u32::from(b1.unwrap_or(0)) << 8) | u32::from(b2.unwrap_or(0));
        out.push(ALPHABET[((n >> 18) & 0x3F) as usize] as char);
        out.push(ALPHABET[((n >> 12) & 0x3F) as usize] as char);
        out.push(match b1 {
            Some(_) => ALPHABET[((n >> 6) & 0x3F) as usize] as char,
            None => '=',
        });
        out.push(match b2 {
            Some(_) => ALPHABET[(n & 0x3F) as usize] as char,
            None => '=',
        });
    }
    out
}

/// Decodes `s`, rejecting anything not strictly well-formed standard
/// Base64 with padding.
pub(crate) fn decode(s: &str) -> Result<Vec<u8>, Base64Error> {
    let b = s.as_bytes();
    if b.len() % 4 != 0 {
        return Err(Base64Error(format!(
            "base64 length {} is not a multiple of 4",
            b.len()
        )));
    }
    let mut out = Vec::with_capacity(b.len() / 4 * 3);
    let n_groups = b.len() / 4;
    for (gi, group) in b.chunks(4).enumerate() {
        let is_last = gi + 1 == n_groups;
        let mut vals = [0u8; 4];
        let mut pad = 0usize;
        for (j, &c) in group.iter().enumerate() {
            if c == b'=' {
                if !is_last {
                    return Err(Base64Error(
                        "'=' padding is only allowed in the final block".into(),
                    ));
                }
                if j < 2 {
                    return Err(Base64Error(
                        "'=' padding cannot start this early in a block".into(),
                    ));
                }
                pad += 1;
            } else {
                if pad > 0 {
                    return Err(Base64Error("data character found after '=' padding".into()));
                }
                vals[j] = decode_char(c)?;
            }
        }
        let n = (u32::from(vals[0]) << 18)
            | (u32::from(vals[1]) << 12)
            | (u32::from(vals[2]) << 6)
            | u32::from(vals[3]);
        out.push((n >> 16) as u8);
        if pad < 2 {
            out.push((n >> 8) as u8);
        }
        if pad < 1 {
            out.push(n as u8);
        }
    }
    Ok(out)
}

fn decode_char(c: u8) -> Result<u8, Base64Error> {
    match c {
        b'A'..=b'Z' => Ok(c - b'A'),
        b'a'..=b'z' => Ok(c - b'a' + 26),
        b'0'..=b'9' => Ok(c - b'0' + 52),
        b'+' => Ok(62),
        b'/' => Ok(63),
        _ => Err(Base64Error(format!(
            "invalid base64 character: {:?}",
            c as char
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_round_trips() {
        assert_eq!(encode(b""), "");
        assert_eq!(decode("").unwrap(), Vec::<u8>::new());
    }

    #[test]
    fn known_vectors() {
        assert_eq!(encode(b"hi"), "aGk=");
        assert_eq!(decode("aGk=").unwrap(), b"hi");
        assert_eq!(encode(b"hello"), "aGVsbG8=");
        assert_eq!(decode("aGVsbG8=").unwrap(), b"hello");
        assert_eq!(encode(b"foobar"), "Zm9vYmFy");
        assert_eq!(decode("Zm9vYmFy").unwrap(), b"foobar");
    }

    #[test]
    fn round_trips_every_byte_value() {
        let data: Vec<u8> = (0..=255u8).collect();
        assert_eq!(decode(&encode(&data)).unwrap(), data);
    }

    #[test]
    fn rejects_bad_length() {
        assert!(decode("a").is_err());
        assert!(decode("ab").is_err());
        assert!(decode("abc").is_err());
        assert!(decode("abcde").is_err());
    }

    #[test]
    fn rejects_invalid_characters() {
        assert!(decode("a b=").is_err()); // whitespace
        assert!(decode("a\nGk=").is_err()); // embedded newline
        assert!(decode("a!k=").is_err()); // out-of-alphabet char
    }

    #[test]
    fn rejects_misplaced_padding() {
        assert!(decode("=Gk=").is_err()); // '=' too early in block
        assert!(decode("aG=k").is_err()); // data character after '='
    }

    #[test]
    fn rejects_padding_in_non_final_block() {
        // Two 4-char blocks; the first carries padding, which is illegal
        // even though the whole string is otherwise well-formed.
        assert!(decode("aGk=aGk=").is_err());
    }
}
