"""Unit tests for the conformance partial-match semantics. Mirrors
go/sandbox/match_test.go: exercises match_json in isolation, no binary or
transport involved."""

from __future__ import annotations

from _conformance_support import Num, load_literal, match_json


def test_object_partial_match_ignores_extra_actual_keys() -> None:
    assert match_json({"ok": True}, {"ok": True, "rows": [[1]]}) is None


def test_object_missing_key_is_mismatch() -> None:
    assert match_json({"ok": True, "x": 1}, {"ok": True}) is not None


def test_array_requires_exact_length_and_order() -> None:
    assert match_json(load_literal("[1,2]"), load_literal("[1,2]")) is None
    assert match_json(load_literal("[1,2]"), load_literal("[2,1]")) is not None
    assert match_json(load_literal("[1,2]"), load_literal("[1,2,3]")) is not None


def test_number_literal_token_must_match_exactly() -> None:
    assert match_json(load_literal("88"), load_literal("88")) is None
    assert match_json(load_literal("88.0"), load_literal("88.0")) is None
    assert match_json(load_literal("88"), load_literal("88.0")) is not None
    assert match_json(load_literal("88.0"), load_literal("88")) is not None


def test_large_integer_is_not_corrupted() -> None:
    big = "9223372036854775807"
    assert match_json(load_literal(big), load_literal(big)) is None


def test_string_bool_null_exact_match() -> None:
    assert match_json("x", "x") is None
    assert match_json("x", "y") is not None
    assert match_json(True, True) is None
    assert match_json(True, False) is not None
    assert match_json(None, None) is None


def test_string_and_number_do_not_cross_match() -> None:
    assert match_json("88", load_literal("88")) is not None


def test_nested_object_partial_match() -> None:
    expect = load_literal('{"error":{"code":"sqlite_error"}}')
    actual = load_literal('{"ok":false,"error":{"code":"sqlite_error","message":"x"}}')
    assert match_json(expect, actual) is None


def test_num_repr_is_its_token() -> None:
    assert repr(Num("9e999")) == "9e999"
