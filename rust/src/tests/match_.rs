//! Unit tests for `match_json` and the literal-preserving parse/write
//! round trip, mirrors python/tests/test_match.py and
//! typescript/test/match.test.ts.

use std::fs;

use crate::codec::{parse_json, write_json};

use super::support::{match_json, repo_root};

fn j(s: &str) -> crate::codec::Json {
    parse_json(s.as_bytes()).unwrap()
}

#[test]
fn number_tokens_88_and_88_point_0_do_not_match() {
    assert!(match_json(&j("88"), &j("88.0"), "$").is_some());
    assert!(match_json(&j("88"), &j("88"), "$").is_none());
    assert!(match_json(&j("88.0"), &j("88.0"), "$").is_none());
}

#[test]
fn object_partial_match_ignores_extra_actual_keys() {
    let expect = j(r#"{"ok":true}"#);
    let actual = j(r#"{"ok":true,"extra":"field"}"#);
    assert_eq!(match_json(&expect, &actual, "$"), None);
}

#[test]
fn object_partial_match_reports_missing_key() {
    let expect = j(r#"{"a":1,"b":2}"#);
    let actual = j(r#"{"a":1}"#);
    assert!(match_json(&expect, &actual, "$").unwrap().contains(".b"));
}

#[test]
fn array_match_requires_exact_length_and_order() {
    assert!(match_json(&j("[1,2,3]"), &j("[1,2]"), "$").is_some());
    assert!(match_json(&j("[1,2]"), &j("[2,1]"), "$").is_some());
    assert!(match_json(&j("[1,2]"), &j("[1,2]"), "$").is_none());
}

#[test]
fn nested_mismatch_path_is_reported() {
    let expect = j(r#"{"a":[{"b":1}]}"#);
    let actual = j(r#"{"a":[{"b":2}]}"#);
    let m = match_json(&expect, &actual, "$").unwrap();
    assert!(m.contains("$.a[0].b"), "unexpected path in: {m}");
}

#[test]
fn strings_bools_and_null_compare_by_equality() {
    assert!(match_json(&j(r#""a""#), &j(r#""b""#), "$").is_some());
    assert_eq!(match_json(&j("true"), &j("true"), "$"), None);
    assert_eq!(match_json(&j("null"), &j("null"), "$"), None);
    assert!(match_json(&j("null"), &j("false"), "$").is_some());
}

#[test]
fn large_integer_token_is_compared_verbatim() {
    let expect = j("9223372036854775807");
    let actual = j("9223372036854775807");
    assert_eq!(match_json(&expect, &actual, "$"), None);
}

#[test]
fn every_case_file_round_trips_byte_for_byte() {
    let cases_dir = repo_root().join("conformance").join("cases");
    let mut checked = 0;
    for entry in fs::read_dir(&cases_dir).expect("reading conformance/cases") {
        let path = entry.unwrap().path();
        if path.extension().and_then(|s| s.to_str()) != Some("json") {
            continue;
        }
        let text = fs::read_to_string(&path).unwrap();
        let parsed = parse_json(text.as_bytes()).unwrap();
        let steps = parsed
            .get("steps")
            .and_then(crate::codec::Json::as_array)
            .unwrap();
        for step in steps {
            if let Some(request) = step.get("request") {
                let rewritten = write_json(request);
                let reparsed = parse_json(rewritten.as_bytes()).unwrap();
                assert_eq!(request, &reparsed, "round trip mismatch in {path:?}");
            }
        }
        checked += 1;
    }
    assert!(
        checked > 0,
        "no conformance case files found under {cases_dir:?}"
    );
}
