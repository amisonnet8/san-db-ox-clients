"""Shared helpers for the conformance suite tests: parsing JSON while
keeping each number's exact literal token (so 88 and 88.0 compare as
different values, and integers beyond 2**53 never round-trip through a
float), re-serializing that structure byte-for-byte, and a partial-match
comparison mirroring conformance/README_ja.md's `expect` semantics.

Named with a leading underscore, not test_*, so pytest doesn't try to
collect it as a test module.
"""

from __future__ import annotations

import json
from typing import Any, NamedTuple


class Num(NamedTuple):
    """A JSON number, kept as the exact text it was written with. Not a
    subclass of str -- Num("88") must never compare equal to the string
    "88", which a plain str subclass would allow."""

    text: str

    def __repr__(self) -> str:
        return self.text


def load_literal(data: bytes | str) -> Any:
    """Parse JSON, keeping every number as a Num instead of int/float.

    Python's default int/float parsing would already distinguish 88 from
    88.0 and preserve arbitrary-precision integers, but Num makes that
    property explicit and lets dumps_literal below round-trip the token
    byte-for-byte (mirroring Go's json.RawMessage-based forwarding).
    """
    return json.loads(data, parse_int=Num, parse_float=Num)


def dumps_literal(value: Any) -> str:
    """Serialize a structure produced by load_literal back to JSON text,
    rendering each Num using its original token verbatim."""
    if isinstance(value, Num):
        return value.text
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list):
        return "[" + ",".join(dumps_literal(v) for v in value) + "]"
    if isinstance(value, dict):
        items = (
            f"{json.dumps(k, ensure_ascii=False)}:{dumps_literal(v)}"
            for k, v in value.items()
        )
        return "{" + ",".join(items) + "}"
    raise TypeError(f"cannot serialize {type(value).__name__} literally")


def match_json(expect: Any, actual: Any, path: str = "$") -> str | None:
    """Partial-match expect against actual (conformance/README_ja.md):
    objects match on the keys expect specifies (extra actual keys are
    fine); arrays, strings, bools, null, and numbers must match exactly --
    numbers compare by their literal token, so 88 and 88.0 never match
    each other. Returns None on a match, or a description of the first
    mismatch found.
    """
    if isinstance(expect, dict):
        if not isinstance(actual, dict):
            return f"{path}: expected an object, got {actual!r}"
        for key, exp_val in expect.items():
            if key not in actual:
                return f"{path}.{key}: missing in response"
            mismatch = match_json(exp_val, actual[key], f"{path}.{key}")
            if mismatch is not None:
                return mismatch
        return None
    if isinstance(expect, list):
        if not isinstance(actual, list):
            return f"{path}: expected an array, got {actual!r}"
        if len(expect) != len(actual):
            return f"{path}: expected {len(expect)} elements, got {len(actual)}"
        for i, (e, a) in enumerate(zip(expect, actual, strict=True)):
            mismatch = match_json(e, a, f"{path}[{i}]")
            if mismatch is not None:
                return mismatch
        return None
    if isinstance(expect, Num):
        if not isinstance(actual, Num) or expect.text != actual.text:
            return f"{path}: expected number token {expect.text!r}, got {actual!r}"
        return None
    if expect != actual:
        return f"{path}: expected {expect!r}, got {actual!r}"
    return None
