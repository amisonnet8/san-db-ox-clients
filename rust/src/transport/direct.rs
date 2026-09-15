//! `DirectTransport`: a child process, talked to over its stdin/stdout.
//! Mirrors go/sandbox/internal/transport/direct.go and Python's
//! `_transport.DirectTransport`.

use std::ffi::{OsStr, OsString};
use std::io::{Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, ExitStatus, Stdio};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crate::error::Error;

use super::Transport;
use super::reader::ThreadedReader;
use super::signal;

/// Where a child's stderr goes. `Null` (the default) means no pipe is
/// even created, so a chatty server's stderr can never fill a pipe and
/// block it (protocol.md); `Writer` drains stderr on a background thread
/// into whatever the caller supplies, and `Inherit` lets the OS forward it
/// directly (also pipe-free).
pub enum StderrSink {
    Null,
    Inherit,
    Writer(Box<dyn Write + Send>),
}

pub(crate) struct DirectTransport {
    child: Child,
    stdin: Option<ChildStdin>,
    reader: Option<ThreadedReader>,
    stderr_thread: Option<JoinHandle<()>>,
    exit_code: Option<i32>,
}

impl DirectTransport {
    pub(crate) fn start<S, A>(
        command: S,
        args: &[A],
        env: Option<&[(OsString, OsString)]>,
        cwd: Option<&Path>,
        stderr: StderrSink,
    ) -> Result<Self, Error>
    where
        S: AsRef<OsStr>,
        A: AsRef<OsStr>,
    {
        let mut cmd = Command::new(command);
        cmd.args(args.iter().map(AsRef::as_ref));
        cmd.stdin(Stdio::piped());
        cmd.stdout(Stdio::piped());
        match &stderr {
            StderrSink::Null => {
                cmd.stderr(Stdio::null());
            }
            StderrSink::Inherit => {
                cmd.stderr(Stdio::inherit());
            }
            StderrSink::Writer(_) => {
                cmd.stderr(Stdio::piped());
            }
        }
        // env=None means "inherit the parent's environment" (Command's own
        // default); env=Some(..) *replaces* it entirely rather than
        // merging, matching Python's `subprocess.Popen(env=dict(env))`.
        if let Some(env) = env {
            cmd.env_clear();
            cmd.envs(env.iter().map(|(k, v)| (k.clone(), v.clone())));
        }
        if let Some(cwd) = cwd {
            cmd.current_dir(cwd);
        }

        // Spawn failure (e.g. a nonexistent command, ENOENT) surfaces here
        // as Error::Io rather than hanging.
        let mut child = cmd.spawn()?;
        let stdin = child.stdin.take().expect("Stdio::piped() guarantees Some");
        let stdout = child.stdout.take().expect("Stdio::piped() guarantees Some");
        let reader = ThreadedReader::spawn(stdout);

        let stderr_thread = match stderr {
            StderrSink::Writer(mut sink) => {
                let mut child_stderr = child.stderr.take().expect("Stdio::piped() guarantees Some");
                Some(thread::spawn(move || {
                    let mut buf = [0u8; 64 * 1024];
                    loop {
                        match child_stderr.read(&mut buf) {
                            Ok(0) | Err(_) => return,
                            Ok(n) => {
                                if sink.write_all(&buf[..n]).is_err() {
                                    return;
                                }
                            }
                        }
                    }
                }))
            }
            StderrSink::Null | StderrSink::Inherit => None,
        };

        Ok(DirectTransport {
            child,
            stdin: Some(stdin),
            reader: Some(reader),
            stderr_thread,
            exit_code: None,
        })
    }

    /// The child's exit code. `None` while still running; once exited,
    /// a signal death is reported as `-signum` (matching Python's
    /// `Popen.poll()` convention), so the two "still running" and
    /// "killed by a signal with code 0" cases are never confused.
    pub(crate) fn exit_code(&self) -> Option<i32> {
        self.exit_code
    }

    fn wait_bounded(&mut self, deadline: Instant) -> Option<ExitStatus> {
        let mut backoff = Duration::from_millis(1);
        loop {
            if let Ok(Some(status)) = self.child.try_wait() {
                return Some(status);
            }
            let now = Instant::now();
            if now >= deadline {
                return None;
            }
            thread::sleep(backoff.min(deadline - now));
            backoff = (backoff * 2).min(Duration::from_millis(10));
        }
    }
}

impl Transport for DirectTransport {
    fn write_line(&mut self, line: &[u8]) -> Result<(), Error> {
        let stdin = self.stdin.as_mut().ok_or(Error::Closed)?;
        // protocol.md: flush after every request. Piped stdin is
        // buffered, so this is required, not optional.
        stdin.write_all(line)?;
        stdin.flush()?;
        Ok(())
    }

    fn read_line(&mut self, timeout: Option<Duration>) -> Result<Vec<u8>, Error> {
        match self.reader.as_mut() {
            Some(r) => r.read_line(timeout),
            None => Err(Error::Closed),
        }
    }

    /// Staged shutdown, matching protocol.md and go/sandbox's direct.go:
    /// close stdin -> wait -> SIGTERM -> wait -> SIGKILL. Idempotent --
    /// calling this more than once is safe and a no-op after the first
    /// call actually tears anything down.
    fn close(&mut self, timeout: Duration) {
        if self.stdin.is_none()
            && self.reader.is_none()
            && self.stderr_thread.is_none()
            && self.exit_code.is_some()
        {
            return; // already closed
        }

        self.stdin = None; // Drop closes the fd -- stage 1.

        let mut status = self.wait_bounded(Instant::now() + timeout);

        if status.is_none() {
            // Stage 2: SIGTERM. #[cfg(not(unix))] this is a no-op and
            // falls straight through to SIGKILL below, same as Go.
            signal::terminate(self.child.id());
            status = self.wait_bounded(Instant::now() + timeout);
        }

        if status.is_none() {
            // Stage 3: SIGKILL. Unbounded wait -- SIGKILL cannot be
            // ignored, so this is guaranteed to return.
            let _ = self.child.kill();
            status = self.child.wait().ok();
        }

        self.exit_code = status.map(exit_code_of);

        if let Some(t) = self.stderr_thread.take() {
            let _ = t.join();
        }
        if let Some(mut r) = self.reader.take() {
            r.join(timeout);
        }
    }
}

#[cfg(unix)]
fn exit_code_of(status: ExitStatus) -> i32 {
    use std::os::unix::process::ExitStatusExt;
    status
        .code()
        .unwrap_or_else(|| -status.signal().unwrap_or(0))
}

#[cfg(not(unix))]
fn exit_code_of(status: ExitStatus) -> i32 {
    status.code().unwrap_or(-1)
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    fn start(command: &str, args: &[&str]) -> DirectTransport {
        DirectTransport::start(command, args, None, None, StderrSink::Null).unwrap()
    }

    #[test]
    fn write_read_echo_via_cat() {
        let mut t = start("cat", &[]);
        t.write_line(b"hello\n").unwrap();
        let line = t.read_line(Some(Duration::from_secs(5))).unwrap();
        assert_eq!(line, b"hello");
        t.close(Duration::from_secs(5));
    }

    #[test]
    fn stderr_flood_does_not_deadlock() {
        // Bounded generator (20000 lines), not an infinite one -- an
        // earlier CPU-pinning incident in this repository's history came
        // from exactly this kind of test using an unbounded pipeline.
        let mut t = DirectTransport::start(
            "sh",
            &[
                "-c",
                "i=0; while [ $i -lt 20000 ]; do echo \"warning $i\" >&2; i=$((i+1)); done; cat",
            ],
            None,
            None,
            StderrSink::Null,
        )
        .unwrap();
        t.write_line(b"still alive\n").unwrap();
        let line = t.read_line(Some(Duration::from_secs(10))).unwrap();
        assert_eq!(line, b"still alive");
        t.close(Duration::from_secs(5));
    }

    #[test]
    fn stderr_drained_to_provided_sink() {
        struct Collector(Arc<Mutex<Vec<u8>>>);
        impl Write for Collector {
            fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
                self.0.lock().unwrap().extend_from_slice(buf);
                Ok(buf.len())
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        let collected = Arc::new(Mutex::new(Vec::new()));
        let mut t = DirectTransport::start(
            "sh",
            &["-c", "echo one >&2; echo two >&2; cat"],
            None,
            None,
            StderrSink::Writer(Box::new(Collector(collected.clone()))),
        )
        .unwrap();
        t.write_line(b"x\n").unwrap();
        t.read_line(Some(Duration::from_secs(5))).unwrap();
        t.close(Duration::from_secs(5));
        assert_eq!(&*collected.lock().unwrap(), b"one\ntwo\n");
    }

    #[test]
    fn close_via_stdin_reaps_promptly() {
        let mut t = start("cat", &[]);
        let start_time = Instant::now();
        t.close(Duration::from_secs(5));
        assert!(start_time.elapsed() < Duration::from_secs(1));
        assert_eq!(t.exit_code(), Some(0));
    }

    #[test]
    fn close_escalates_to_sigterm() {
        let mut t = start("sh", &["-c", "while true; do sleep 0.05; done"]);
        let start_time = Instant::now();
        t.close(Duration::from_millis(200));
        let elapsed = start_time.elapsed();
        assert!(
            elapsed >= Duration::from_millis(150),
            "closed too fast: {elapsed:?}"
        );
        assert!(
            elapsed < Duration::from_secs(2),
            "closed too slow: {elapsed:?}"
        );
        assert_eq!(t.exit_code(), Some(-15));
    }

    #[test]
    fn close_escalates_to_sigkill() {
        let mut t = start(
            "sh",
            &["-c", "trap '' TERM; while true; do sleep 0.05; done"],
        );
        t.close(Duration::from_millis(200));
        assert_eq!(t.exit_code(), Some(-9));
    }

    #[test]
    fn double_close_is_safe() {
        let mut t = start("cat", &[]);
        t.close(Duration::from_secs(5));
        t.close(Duration::from_secs(5)); // must not panic or hang
    }

    #[test]
    fn read_line_timeout_does_not_hang() {
        let mut t = start("cat", &[]);
        let err = t.read_line(Some(Duration::from_millis(100))).unwrap_err();
        assert!(matches!(err, Error::Timeout(_)));
        t.close(Duration::from_secs(5));
    }

    #[test]
    fn nonexistent_command_surfaces_the_spawn_error() {
        // DirectTransport has no Debug impl (Child doesn't have one), so
        // Result::unwrap_err() isn't available here -- match instead.
        match DirectTransport::start(
            "san-db-ox-definitely-does-not-exist",
            &[] as &[&str],
            None,
            None,
            StderrSink::Null,
        ) {
            Err(Error::Io(_)) => {}
            other => panic!(
                "expected Error::Io, got a different outcome: {}",
                other.is_ok()
            ),
        }
    }
}
