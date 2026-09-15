//! `ThreadedReader`: a background thread that reads raw bytes from a
//! child process's stdout, frames them into lines via [`LineFramer`], and
//! hands each completed line to the caller through a 1-slot channel.
//!
//! A background thread is the only way in `std` to put a deadline on
//! reading from an anonymous pipe (`ChildStdout` has no read-timeout
//! mechanism on any platform) -- `testing.md` requires a timeout on every
//! read, so this exists specifically to make that possible for the direct
//! transport. The socket transport does not use this: see
//! `transport::socket` for why (the TLS seam needs an inline framer
//! instead).

use std::io::Read;
use std::sync::mpsc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::error::Error;

use super::LineFramer;

enum Item {
    Line(Vec<u8>),
    Terminal(Error),
}

pub(crate) struct ThreadedReader {
    rx: mpsc::Receiver<Item>,
    /// The terminal error (EOF, line-cap exceeded, or I/O error), once
    /// seen, is memoized and re-delivered on every subsequent call --
    /// mirrors Python's `_LineReader._terminal` and TypeScript's
    /// `LineReader#terminal`.
    terminal: Option<Error>,
    handle: Option<JoinHandle<()>>,
}

impl ThreadedReader {
    pub(crate) fn spawn(mut source: impl Read + Send + 'static) -> Self {
        // A 1-slot channel reproduces the backpressure of Python's
        // `Queue(maxsize=1)` and TypeScript's single-slot pause/resume:
        // responses always arrive in order, so there is never a reason
        // for the reader thread to race ahead of the caller.
        let (tx, rx) = mpsc::sync_channel::<Item>(1);
        let handle = thread::spawn(move || {
            let mut framer = LineFramer::new();
            let mut buf = [0u8; 64 * 1024];
            loop {
                match source.read(&mut buf) {
                    Ok(0) => {
                        let _ = tx.send(Item::Terminal(Error::Protocol(
                            "connection closed (EOF)".to_string(),
                        )));
                        return;
                    }
                    Ok(n) => {
                        framer.push(&buf[..n]);
                        loop {
                            match framer.take_line() {
                                Ok(Some(line)) => {
                                    if tx.send(Item::Line(line)).is_err() {
                                        return; // receiver gone; nothing left to do
                                    }
                                }
                                Ok(None) => break,
                                Err(e) => {
                                    let _ = tx.send(Item::Terminal(e));
                                    return;
                                }
                            }
                        }
                    }
                    Err(e) => {
                        let _ = tx.send(Item::Terminal(Error::from(e)));
                        return;
                    }
                }
            }
        });
        ThreadedReader {
            rx,
            terminal: None,
            handle: Some(handle),
        }
    }

    pub(crate) fn read_line(&mut self, timeout: Option<Duration>) -> Result<Vec<u8>, Error> {
        if let Some(e) = &self.terminal {
            return Err(e.clone());
        }
        let deadline = timeout.map(|t| Instant::now() + t);
        loop {
            // recv_timeout is documented as possibly returning early, so
            // this loops against a monotonic deadline rather than trusting
            // a single call to have actually waited the full duration.
            let outcome = match deadline {
                None => self
                    .rx
                    .recv()
                    .map_err(|_| mpsc::RecvTimeoutError::Disconnected),
                Some(dl) => self
                    .rx
                    .recv_timeout(dl.saturating_duration_since(Instant::now())),
            };
            match outcome {
                Ok(Item::Line(line)) => return Ok(line),
                Ok(Item::Terminal(e)) => {
                    self.terminal = Some(e.clone());
                    return Err(e);
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    let e = Error::Protocol("reader thread ended unexpectedly".to_string());
                    self.terminal = Some(e.clone());
                    return Err(e);
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    if deadline.is_none_or(|dl| Instant::now() < dl) {
                        continue;
                    }
                    return Err(Error::Timeout(
                        "timed out waiting for a response line".to_string(),
                    ));
                }
            }
        }
    }

    /// Joins the background thread, if it finishes within `timeout`.
    ///
    /// This is a *bounded* wait, not a guarantee: `close()` sends the
    /// close op but (matching every other driver in this repository)
    /// never reads its response, since the response -- or the lack of one
    /// -- doesn't change what happens next. That response line still gets
    /// read by this thread, though, and with nothing left to call
    /// `read_line()` and drain it, the thread can be parked indefinitely
    /// trying to deliver it through the 1-slot channel. std has no timed
    /// `JoinHandle::join`, so a bounded busy-wait on `is_finished` is used
    /// to detect the common case (thread already exited) cheaply; if the
    /// bound expires with the thread still blocked, this gives up rather
    /// than joining unconditionally (which would hang forever) -- the
    /// `Receiver` this thread is blocked sending to is dropped shortly
    /// after this returns (by the owning transport), which unblocks the
    /// send with a disconnect error and lets the thread exit for real
    /// moments later. This mirrors what Python's `Thread.join(timeout=)`
    /// achieves for the identical scenario: a bounded wait that leaves a
    /// harmless, short-lived daemon-like thread behind in the rare case
    /// where the bound is actually hit.
    pub(crate) fn join(&mut self, timeout: Duration) {
        if let Some(h) = &self.handle {
            let deadline = Instant::now() + timeout;
            while !h.is_finished() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(1));
            }
            if !h.is_finished() {
                return; // give up -- see the doc comment above
            }
        }
        if let Some(h) = self.handle.take() {
            let _ = h.join(); // already finished: reaps instantly
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn terminal_error_is_memoized_after_eof() {
        let mut r = ThreadedReader::spawn(Cursor::new(Vec::<u8>::new()));
        let first = r.read_line(Some(Duration::from_secs(5))).unwrap_err();
        assert!(matches!(first, Error::Protocol(_)));
        for _ in 0..10 {
            let e = r.read_line(Some(Duration::from_secs(5))).unwrap_err();
            assert!(matches!(e, Error::Protocol(_)));
        }
        r.join(Duration::from_secs(5));
    }

    #[test]
    fn reads_a_line_terminated_by_newline() {
        let mut r = ThreadedReader::spawn(Cursor::new(b"hello\n".to_vec()));
        let line = r.read_line(Some(Duration::from_secs(5))).unwrap();
        assert_eq!(line, b"hello");
        r.join(Duration::from_secs(5));
    }
}
