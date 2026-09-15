//! Shared helpers for the integration test suite (`tests/client.rs`,
//! `tests/socket.rs`). A deliberate, small duplicate of
//! `src/tests/support.rs`: `tests/*.rs` is a separate crate that only
//! sees this crate's public API, so it cannot reach that internal
//! module. Mirrors python/tests/conftest.py.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

/// Every call in this suite gets this timeout, so a flush/read bug hangs
/// the affected test instead of the whole suite (testing.md).
pub const CALL_TIMEOUT: Duration = Duration::from_secs(10);

pub fn repo_root() -> PathBuf {
    // CARGO_MANIFEST_DIR is rust/, so the repository root is one level up.
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("rust/ has a parent directory")
        .to_path_buf()
}

/// Path to the san-db-ox binary under test, or `None` if not found.
/// `SAN_DB_OX_BIN` takes priority; otherwise `bin/san-db-ox`
/// (`bin/san-db-ox.exe` on Windows) at the repo root.
pub fn san_db_ox_bin() -> Option<PathBuf> {
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

/// A private, writable copy of the binary, for tests (`overwrite`) that
/// mutate it. Not every test binary that includes this `common` module
/// uses every helper in it (each `tests/*.rs` file compiles `common` as
/// its own copy), hence the blanket `#[allow(dead_code)]` below.
#[allow(dead_code)]
pub fn copy_of_binary(bin: &Path, dest_dir: &Path) -> PathBuf {
    let dest = dest_dir.join(bin.file_name().expect("binary path has a file name"));
    fs::copy(bin, &dest).expect("copying the test binary");
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&dest, fs::Permissions::from_mode(0o755)).expect("chmod +x");
    }
    dest
}

/// A fresh, isolated temporary directory. Tests that exercise
/// `snapshot`/`overwrite` must run the server with this as its cwd -- an
/// earlier isolation bug in this repository's history (the TypeScript
/// driver's test suite) let a snapshot land inside the repository itself
/// when a test ran with an un-isolated cwd.
pub fn isolated_dir() -> PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir = env::temp_dir().join(format!("san-db-ox-rs-itest-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&dir).expect("creating an isolated temp dir");
    dir
}
