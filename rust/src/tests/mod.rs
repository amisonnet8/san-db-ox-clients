//! Internal (crate-private) test-only modules: these need access to
//! `codec`/`transport` internals that a `tests/*.rs` integration test
//! cannot see (Rust's `tests/` directory is a separate crate that only
//! sees the public API) -- mirrors how Go puts this kind of test inside
//! its package and Python's test suite imports the underscore-prefixed
//! `_transport`/`_codec` modules directly.

mod conformance;
mod match_;
pub(crate) mod support;
