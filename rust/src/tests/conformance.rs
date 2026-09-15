//! Runs conformance/cases/*.json against a real san-db-ox binary. See
//! conformance/README.md for the case format and the rules this runner
//! implements (partial-match `expect`, `expect_raw`, `known_failing` as
//! xfail-but-never-swallow-a-transport-failure). Mirrors
//! go/sandbox/conformance_test.go and python/tests/test_conformance.py.
//!
//! Uses `DirectTransport` directly rather than the public `Client` API:
//! some cases deliberately send malformed requests (wrong param shapes,
//! unknown ops) that the typed API has no way to construct.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::codec::{Json, parse_json, write_json};
use crate::transport::Transport;
use crate::transport::direct::{DirectTransport, StderrSink};

use super::support::{CALL_TIMEOUT_SECS, isolated_dir, match_json, repo_root, san_db_ox_bin};

fn cases_dir() -> PathBuf {
    repo_root().join("conformance").join("cases")
}

fn case_paths() -> Vec<PathBuf> {
    let mut v: Vec<_> = fs::read_dir(cases_dir())
        .expect("reading conformance/cases")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|s| s.to_str()) == Some("json"))
        .collect();
    v.sort();
    v
}

#[test]
fn conformance_cases_directory_is_not_empty() {
    // A typo in cases_dir() or an empty glob would otherwise make the
    // suite below silently collect zero cases instead of failing.
    assert!(
        !case_paths().is_empty(),
        "no conformance cases found in {:?}",
        cases_dir()
    );
}

#[test]
fn conformance_cases_all_pass() {
    let Some(bin) = san_db_ox_bin() else {
        eprintln!(
            "skipping conformance suite: no san-db-ox binary found (run `make fetch` or set SAN_DB_OX_BIN)"
        );
        return;
    };
    let mut failures = Vec::new();
    for path in case_paths() {
        let name = path.file_stem().unwrap().to_string_lossy().to_string();
        if let Err(msg) = run_case(&bin, &path) {
            failures.push(format!("{name}: {msg}"));
        }
    }
    assert!(failures.is_empty(), "\n{}", failures.join("\n"));
}

fn run_case(bin: &Path, path: &Path) -> Result<(), String> {
    let text = fs::read_to_string(path).map_err(|e| format!("reading case file: {e}"))?;
    let case = parse_json(text.as_bytes()).map_err(|e| format!("parsing case file: {e}"))?;

    let args: Vec<String> = case
        .get("args")
        .and_then(Json::as_array)
        .map(|a| {
            a.iter()
                .filter_map(Json::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();
    let known_failing = case
        .get("known_failing")
        .and_then(Json::as_str)
        .map(str::to_string);
    let steps = case
        .get("steps")
        .and_then(Json::as_array)
        .ok_or("case has no steps array")?;

    let mut full_args = vec!["--serve-stdio".to_string()];
    full_args.extend(args);
    let cwd = isolated_dir();
    let mut transport = DirectTransport::start(bin, &full_args, None, Some(&cwd), StderrSink::Null)
        .map_err(|e| e.to_string())?;

    let timeout = Some(Duration::from_secs(CALL_TIMEOUT_SECS));
    let outcome = run_steps(&mut transport, steps, timeout);
    transport.close(Duration::from_secs(2));
    let _ = fs::remove_dir_all(&cwd);

    let mismatches = outcome?;
    if let Some(reason) = known_failing {
        if mismatches.is_empty() {
            return Err(format!(
                "known_failing is set ({reason:?}) but every step passed -- remove the marker"
            ));
        }
        // xfail: the case is allowed to fail, but the failure must still
        // be visible so a change in *how* it fails doesn't go unnoticed.
        eprintln!("known_failing ({reason}):\n  {}", mismatches.join("\n  "));
        return Ok(());
    }
    if mismatches.is_empty() {
        Ok(())
    } else {
        Err(mismatches.join("\n"))
    }
}

fn run_steps(
    transport: &mut DirectTransport,
    steps: &[Json],
    timeout: Option<Duration>,
) -> Result<Vec<String>, String> {
    // A transport-level failure (unlike a wrong *answer*) is never a
    // "known" failure -- it means the bug's shape changed, which
    // known_failing must not hide.
    transport
        .read_line(timeout)
        .map_err(|e| format!("reading hello line: {e}"))?; // discarded

    let mut mismatches = Vec::new();
    for (i, step) in steps.iter().enumerate() {
        let request = step
            .get("request")
            .ok_or_else(|| format!("step {i}: case has no request"))?;
        let mut line = write_json(request).into_bytes();
        line.push(b'\n');
        transport
            .write_line(&line)
            .map_err(|e| format!("step {i}: transport failure: {e}"))?;
        let raw = transport
            .read_line(timeout)
            .map_err(|e| format!("step {i}: transport failure: {e}"))?;
        let raw_text = String::from_utf8_lossy(&raw).into_owned();

        if let Some(expect) = step.get("expect") {
            let actual =
                parse_json(&raw).map_err(|e| format!("step {i}: invalid JSON response: {e}"))?;
            if let Some(m) = match_json(expect, &actual, "$") {
                mismatches.push(format!("step {i}: {m} (got {raw_text})"));
            }
        }
        if let Some(expect_raw) = step.get("expect_raw").and_then(Json::as_array) {
            for snippet in expect_raw.iter().filter_map(Json::as_str) {
                if !raw_text.contains(snippet) {
                    mismatches.push(format!(
                        "step {i}: expected raw response to contain {snippet:?}, got {raw_text:?}"
                    ));
                }
            }
        }
    }
    Ok(mismatches)
}
