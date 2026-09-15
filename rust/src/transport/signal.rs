//! POSIX process signaling for the staged close sequence (protocol.md):
//! stdin close -> wait -> SIGTERM -> wait -> SIGKILL. `std::process::Child`
//! can only ever send SIGKILL (`Child::kill`); the SIGTERM stage needs a
//! syscall `std` does not expose. A single hand-declared `kill(2)` symbol
//! keeps this crate's runtime dependencies at zero (no `libc` crate for
//! one function).
//!
//! This is the crate's only `unsafe` code -- `src/lib.rs` denies
//! `unsafe_code` everywhere else, and `src/codec/mod.rs` forbids it
//! outright.
#![allow(unsafe_code)]

#[cfg(unix)]
pub(crate) fn terminate(pid: u32) -> bool {
    const SIGTERM: i32 = 15; // the same signal number on every Unix std targets.
    // SAFETY: `kill(2)` is called only with a pid this process itself
    // spawned and has not yet reaped. The sole precondition is that the
    // pid still refers to that process -- once `wait()`/`try_wait()` has
    // observed its exit, the OS is free to recycle the pid, and signaling
    // it afterward could hit an unrelated process. `DirectTransport`'s
    // close sequence only reaches this call on the branch where its own
    // `try_wait()` has not yet observed an exit, and the owning `Child` is
    // never shared with another thread, so that precondition holds by
    // construction (there is no concurrent waiter that could reap the
    // child between the `try_wait()` check and this call).
    unsafe { kill(pid as i32, SIGTERM) == 0 }
}

#[cfg(unix)]
unsafe extern "C" {
    fn kill(pid: i32, sig: i32) -> i32;
}

#[cfg(not(unix))]
pub(crate) fn terminate(_pid: u32) -> bool {
    // Windows has no SIGTERM; DirectTransport::close falls straight
    // through to Child::kill() (SIGKILL-equivalent), same as the Go
    // driver's direct.go on this platform.
    false
}
