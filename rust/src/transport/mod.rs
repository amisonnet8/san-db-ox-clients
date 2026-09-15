//! Transport layer: reading/writing bytes and managing a connection's
//! lifetime. Knows nothing about JSON Lines framing or the op vocabulary
//! -- that's `codec`'s job (architecture.md's codec/transport split).
//!
//! Exactly two transports, matching architecture.md: `direct` (the
//! default -- a child process, talked to over its stdin/stdout) and
//! `socket` (optional -- a TCP/UNIX socket, typically fronted by
//! something like socat).

pub(crate) mod direct;
mod reader;
pub(crate) mod signal;
pub(crate) mod socket;

use std::time::Duration;

use crate::error::Error;

/// Sized for upstream's stated 1 MiB line limit, with headroom above it
/// (matches `codec::MAX_LINE_BYTES`; duplicated as its own constant here
/// so `transport` never needs to depend on `codec`).
pub(crate) const MAX_LINE_BYTES: usize = 2 * 1024 * 1024;

/// What every connection to a SanDBox process can do at the byte level,
/// regardless of transport.
pub(crate) trait Transport {
    fn write_line(&mut self, line: &[u8]) -> Result<(), Error>;
    fn read_line(&mut self, timeout: Option<Duration>) -> Result<Vec<u8>, Error>;
    fn close(&mut self, timeout: Duration);
}

/// A pure byte -> line state machine, shared by both transports. Splits
/// only on `\n` (a lone `\r` never ends a line -- the same reason the
/// TypeScript driver avoids `node:readline`), and never re-scans bytes it
/// has already scanned for a newline, so a large multi-chunk response
/// (e.g. a 1 MiB `dump` result) doesn't become O(n^2).
pub(crate) struct LineFramer {
    buf: Vec<u8>,
    /// How much of `buf`, from the front, has already been scanned for a
    /// `\n` and found none.
    scanned: usize,
}

impl LineFramer {
    pub(crate) fn new() -> Self {
        LineFramer {
            buf: Vec::new(),
            scanned: 0,
        }
    }

    pub(crate) fn push(&mut self, chunk: &[u8]) {
        self.buf.extend_from_slice(chunk);
    }

    /// Pops one complete line (newline stripped), if the buffer holds
    /// one. `Ok(None)` means "keep reading"; `Err` means the still-open
    /// line has exceeded `MAX_LINE_BYTES` without a newline -- a terminal
    /// condition the caller must not call `push`/`take_line` again after.
    pub(crate) fn take_line(&mut self) -> Result<Option<Vec<u8>>, Error> {
        if let Some(rel) = self.buf[self.scanned..].iter().position(|&b| b == b'\n') {
            let pos = self.scanned + rel;
            let line = self.buf[..pos].to_vec();
            self.buf.drain(..=pos);
            self.scanned = 0;
            return Ok(Some(line));
        }
        self.scanned = self.buf.len();
        if self.buf.len() > MAX_LINE_BYTES {
            return Err(Error::Protocol(format!(
                "response line exceeds {MAX_LINE_BYTES} bytes without a newline"
            )));
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn multiple_lines_in_one_chunk() {
        let mut f = LineFramer::new();
        f.push(b"one\ntwo\nthr");
        assert_eq!(f.take_line().unwrap(), Some(b"one".to_vec()));
        assert_eq!(f.take_line().unwrap(), Some(b"two".to_vec()));
        assert_eq!(f.take_line().unwrap(), None); // "thr" is a partial line
    }

    #[test]
    fn one_line_split_across_chunks() {
        let mut f = LineFramer::new();
        f.push(b"hel");
        assert_eq!(f.take_line().unwrap(), None);
        f.push(b"lo\n");
        assert_eq!(f.take_line().unwrap(), Some(b"hello".to_vec()));
    }

    #[test]
    fn lone_cr_does_not_split_a_line() {
        let mut f = LineFramer::new();
        f.push(b"a\rb\n");
        assert_eq!(f.take_line().unwrap(), Some(b"a\rb".to_vec()));
    }

    #[test]
    fn exactly_max_line_bytes_is_accepted() {
        let mut f = LineFramer::new();
        let mut line = vec![b'x'; MAX_LINE_BYTES];
        line.push(b'\n');
        f.push(&line);
        let got = f.take_line().unwrap().unwrap();
        assert_eq!(got.len(), MAX_LINE_BYTES);
    }

    #[test]
    fn one_byte_over_max_line_bytes_without_newline_is_terminal() {
        let mut f = LineFramer::new();
        f.push(&vec![b'x'; MAX_LINE_BYTES + 1]);
        assert!(f.take_line().is_err());
    }

    #[test]
    fn trailing_partial_line_is_retained_not_emitted() {
        let mut f = LineFramer::new();
        f.push(b"complete\npartial");
        assert_eq!(f.take_line().unwrap(), Some(b"complete".to_vec()));
        assert_eq!(f.take_line().unwrap(), None);
        f.push(b" line\n");
        assert_eq!(f.take_line().unwrap(), Some(b"partial line".to_vec()));
    }
}
