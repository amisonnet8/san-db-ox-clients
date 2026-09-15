//! Confirms `rust/src/codec/` stays free of networking, process,
//! filesystem, and threading machinery (architecture.md's codec/transport
//! split: "コーデック層はネットワークに依存しないことを検証可能にする"),
//! the same idea as the Go driver's `go list -deps | grep`, Python's
//! `netcheck.py` (a `sys.modules` diff), and TypeScript's `netcheck.mjs`
//! (a static import allowlist). Rust has no per-module dependency graph to
//! inspect at this granularity -- `std` is always linked -- so this is a
//! textual scan instead: every `use` path (with grouped imports expanded,
//! so `use std::{fmt, io::Write}` can't evade a plain substring search)
//! plus a whole-file substring scan (catching a fully-qualified reference
//! like `std::process::Command::new(..)` with no `use` at all) are both
//! checked against a forbidden-prefix list.
//!
//! A **positive control** -- the identical scan run against
//! `src/transport/direct.rs`, which must produce at least one hit --
//! guards against the scanner itself silently breaking (a moved file, a
//! stripped-too-much comment remover) and reporting a false "clean" codec
//! (mirrors typescript/netcheck.mjs's `node:net` control).

use std::fs;
use std::path::{Path, PathBuf};

const FORBIDDEN: &[&str] = &[
    "std::net",
    "std::process",
    "std::fs",
    "std::io",
    "std::thread",
    "std::sync::mpsc",
    "std::os::unix::net",
    "std::os::unix::process",
    "std::time::Instant", // wall-clock/scheduling machinery belongs to transport, not codec
];

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("rust/ has a parent directory")
        .to_path_buf()
}

fn codec_files() -> Vec<PathBuf> {
    let dir = repo_root().join("rust").join("src").join("codec");
    let mut files: Vec<PathBuf> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("reading {dir:?}: {e}"))
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|s| s.to_str()) == Some("rs"))
        .collect();
    files.sort();
    assert!(
        !files.is_empty(),
        "no .rs files found under {dir:?} -- has codec/ moved?"
    );
    files
}

/// Removes `//...` and `/* ... */` comments, replacing their contents
/// with spaces so byte offsets are preserved and nothing outside a
/// comment shifts. Doc comments in this codebase legitimately explain the
/// layer boundary using words like "process" or "network" in prose, so
/// scanning comments verbatim would false-positive on exactly the text
/// that documents the rule -- this strips that out first.
fn strip_comments(src: &str) -> String {
    let mut out = String::with_capacity(src.len());
    let bytes = src.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'/' && bytes.get(i + 1) == Some(&b'/') {
            while i < bytes.len() && bytes[i] != b'\n' {
                out.push(' ');
                i += 1;
            }
        } else if bytes[i] == b'/' && bytes.get(i + 1) == Some(&b'*') {
            out.push(' ');
            out.push(' ');
            i += 2;
            while i < bytes.len() && !(bytes[i] == b'*' && bytes.get(i + 1) == Some(&b'/')) {
                out.push(if src.as_bytes()[i] == b'\n' {
                    '\n'
                } else {
                    ' '
                });
                i += 1;
            }
            if i < bytes.len() {
                out.push(' ');
                out.push(' ');
                i += 2;
            }
        } else {
            // Push whole chars, not bytes, to stay on UTF-8 boundaries.
            let ch = src[i..].chars().next().expect("valid UTF-8 source file");
            out.push(ch);
            i += ch.len_utf8();
        }
    }
    out
}

/// Extracts every path named in a `use` statement, expanding grouped
/// imports (`use std::{fmt, io::Write};` -> `["std::fmt", "std::io::Write"]`)
/// so a forbidden import can't evade a plain substring search by being
/// grouped with an allowed one.
fn extract_use_paths(src: &str) -> Vec<String> {
    let mut paths = Vec::new();
    let mut idx = 0;
    while let Some(pos) = src[idx..].find("use ") {
        let start = idx + pos + 4;
        let Some(end_rel) = src[start..].find(';') else {
            break;
        };
        expand_use_tree(src[start..start + end_rel].trim(), &mut paths);
        idx = start + end_rel + 1;
    }
    paths
}

fn expand_use_tree(stmt: &str, out: &mut Vec<String>) {
    if let Some(brace) = stmt.find('{') {
        let prefix = stmt[..brace].trim().trim_end_matches("::").trim();
        let inner_end = stmt.rfind('}').unwrap_or(stmt.len());
        let inner = &stmt[brace + 1..inner_end];
        for part in split_top_level_commas(inner) {
            let part = part.trim();
            if part.is_empty() {
                continue;
            }
            let combined = if prefix.is_empty() {
                part.to_string()
            } else {
                format!("{prefix}::{part}")
            };
            expand_use_tree(&combined, out);
        }
    } else {
        let path = stmt.split(" as ").next().unwrap_or(stmt).trim();
        out.push(path.trim_end_matches("::*").to_string());
    }
}

fn split_top_level_commas(s: &str) -> Vec<&str> {
    let mut parts = Vec::new();
    let mut depth = 0i32;
    let mut start = 0;
    for (i, c) in s.char_indices() {
        match c {
            '{' => depth += 1,
            '}' => depth -= 1,
            ',' if depth == 0 => {
                parts.push(&s[start..i]);
                start = i + c.len_utf8();
            }
            _ => {}
        }
    }
    parts.push(&s[start..]);
    parts
}

/// Returns every forbidden hit found in `path`'s contents (empty = clean).
fn scan(path: &Path) -> Vec<String> {
    let text = fs::read_to_string(path).unwrap_or_else(|e| panic!("reading {path:?}: {e}"));
    let stripped = strip_comments(&text);
    let mut hits = Vec::new();

    for use_path in extract_use_paths(&stripped) {
        if let Some(bad) = FORBIDDEN.iter().find(|f| use_path.starts_with(**f)) {
            hits.push(format!(
                "{}: use path {use_path:?} starts with forbidden prefix {bad:?}",
                path.display()
            ));
        }
    }
    for bad in FORBIDDEN {
        if stripped.contains(bad) {
            hits.push(format!(
                "{}: source contains forbidden path {bad:?}",
                path.display()
            ));
        }
    }
    hits
}

#[test]
fn codec_layer_has_no_networking_process_fs_or_threading_dependencies() {
    let mut all_hits = Vec::new();
    for file in codec_files() {
        all_hits.extend(scan(&file));
    }
    assert!(
        all_hits.is_empty(),
        "codec/ must not depend on I/O or process machinery, but found:\n{}",
        all_hits.join("\n")
    );
}

#[test]
fn codec_mod_forbids_unsafe_code() {
    let path = repo_root()
        .join("rust")
        .join("src")
        .join("codec")
        .join("mod.rs");
    let text = fs::read_to_string(&path).unwrap_or_else(|e| panic!("reading {path:?}: {e}"));
    let first_non_comment_line = text
        .lines()
        .find(|l| !l.trim().is_empty() && !l.trim_start().starts_with("//"))
        .unwrap_or("");
    assert_eq!(
        first_non_comment_line.trim(),
        "#![forbid(unsafe_code)]",
        "codec/mod.rs must open with #![forbid(unsafe_code)] so the codec layer is provably unsafe-free"
    );
}

/// The positive control: the identical scanner, run against a file that
/// is known to use forbidden machinery (`transport::direct`, which spawns
/// processes and does file/thread I/O by design). If this finds nothing,
/// the scanner itself is broken -- trust neither this result nor the
/// "clean" codec result above until this passes.
#[test]
fn positive_control_the_scanner_detects_a_known_dirty_file() {
    let path = repo_root()
        .join("rust")
        .join("src")
        .join("transport")
        .join("direct.rs");
    let hits = scan(&path);
    assert!(
        !hits.is_empty(),
        "positive control failed: the netcheck scanner found nothing forbidden in {path:?}, \
         which is known to use std::process/std::thread -- the scanner itself is broken \
         (a moved file, a broken comment stripper, or a stale forbidden-prefix list), so the \
         codec-layer result above cannot be trusted either"
    );
}

#[cfg(test)]
mod self_tests {
    use super::*;

    #[test]
    fn expands_grouped_use_imports() {
        let paths = extract_use_paths("use std::{fmt, io::Write};");
        assert!(paths.contains(&"std::fmt".to_string()));
        assert!(paths.contains(&"std::io::Write".to_string()));
    }

    #[test]
    fn strips_line_and_block_comments_without_shifting_offsets() {
        let src = "a // std::process\nb /* std::net */ c";
        let stripped = strip_comments(src);
        assert!(!stripped.contains("std::process"));
        assert!(!stripped.contains("std::net"));
        assert!(stripped.contains('a'));
        assert!(stripped.contains('c'));
    }
}
