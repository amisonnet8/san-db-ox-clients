//! Shared test-only helpers: binary discovery and `match_json`, a
//! partial-match comparison over the codec's own `Json` tree (so number
//! tokens compare by their literal text -- `88` never matches `88.0`).
//! Mirrors python/tests/conftest.py and
//! python/tests/_conformance_support.py.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

use crate::codec::Json;

pub(crate) const CALL_TIMEOUT_SECS: u64 = 10;

pub(crate) fn repo_root() -> PathBuf {
    // CARGO_MANIFEST_DIR is rust/, so the repository root is one level up.
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("rust/ has a parent directory")
        .to_path_buf()
}

/// Path to the san-db-ox binary under test, or `None` if not found.
/// Callers must skip -- never fail -- the individual test when this is
/// `None` (testing.md).
pub(crate) fn san_db_ox_bin() -> Option<PathBuf> {
    if let Ok(p) = env::var("SAN_DB_OX_BIN") {
        return Some(PathBuf::from(p));
    }
    let name = if cfg!(windows) {
        "san-db-ox.exe"
    } else {
        "san-db-ox"
    };
    let candidate = repo_root().join("bin").join(name);
    if candidate.is_file() {
        Some(candidate)
    } else {
        None
    }
}

// A private, writable copy of the binary (for the `overwrite` test) isn't
// needed here: the internal conformance runner never calls overwrite.
// `tests/common/mod.rs` has its own copy_of_binary for the integration
// suite, which does exercise it -- a deliberate small duplication, since
// `tests/*.rs` is a separate crate that cannot see this pub(crate) module.

/// A fresh, isolated temporary directory. Tests that exercise
/// `snapshot`/`overwrite` must run the server with this as its cwd -- an
/// earlier isolation bug in this repository's history (the TypeScript
/// driver's test suite) let a snapshot land inside the repository itself
/// when a test ran with an un-isolated cwd.
pub(crate) fn isolated_dir() -> PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir = env::temp_dir().join(format!("san-db-ox-rs-test-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&dir).expect("creating an isolated temp dir");
    dir
}

/// Partial-match `expect` against `actual` (conformance/README.md):
/// objects match on the keys `expect` specifies (extra `actual` keys are
/// fine); arrays must match length and order exactly; numbers compare by
/// their literal token, so `88` and `88.0` never match each other;
/// everything else compares by equality. Returns `None` on a match, or a
/// description of the first mismatch found.
pub(crate) fn match_json(expect: &Json, actual: &Json, path: &str) -> Option<String> {
    match expect {
        Json::Object(exp_pairs) => {
            let Json::Object(_) = actual else {
                return Some(format!("{path}: expected an object, got {actual:?}"));
            };
            for (key, exp_val) in exp_pairs {
                let Some(act_val) = actual.get(key) else {
                    return Some(format!("{path}.{key}: missing in response"));
                };
                if let Some(m) = match_json(exp_val, act_val, &format!("{path}.{key}")) {
                    return Some(m);
                }
            }
            None
        }
        Json::Array(exp_items) => {
            let Json::Array(act_items) = actual else {
                return Some(format!("{path}: expected an array, got {actual:?}"));
            };
            if exp_items.len() != act_items.len() {
                return Some(format!(
                    "{path}: expected {} elements, got {}",
                    exp_items.len(),
                    act_items.len()
                ));
            }
            for (i, (e, a)) in exp_items.iter().zip(act_items.iter()).enumerate() {
                if let Some(m) = match_json(e, a, &format!("{path}[{i}]")) {
                    return Some(m);
                }
            }
            None
        }
        Json::Number(exp_n) => match actual {
            Json::Number(act_n) if exp_n.as_str() == act_n.as_str() => None,
            _ => Some(format!(
                "{path}: expected number token {:?}, got {actual:?}",
                exp_n.as_str()
            )),
        },
        _ => {
            if expect == actual {
                None
            } else {
                Some(format!("{path}: expected {expect:?}, got {actual:?}"))
            }
        }
    }
}
