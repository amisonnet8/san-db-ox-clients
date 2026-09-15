"""Runs conformance/cases/*.json against a real san-db-ox binary. Mirrors
go/sandbox/conformance_test.go; see conformance/README_ja.md for the case
format and the rules this runner implements (partial-match `expect`,
`expect_raw`, `known_failing` as xfail-but-never-swallow-transport-errors).

Uses _transport.DirectTransport directly rather than the public API: some
cases deliberately send malformed requests (wrong param shapes, unknown
ops) that the typed Client API has no way to construct.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from _conformance_support import dumps_literal, load_literal, match_json
from conftest import CALL_TIMEOUT, repo_root

from san_db_ox._codec import ProtocolError
from san_db_ox._transport import DirectTransport

CASES_DIR = repo_root() / "conformance" / "cases"


def _case_paths() -> list[Path]:
    return sorted(CASES_DIR.glob("*.json"))


def test_conformance_cases_directory_is_not_empty() -> None:
    # A typo in CASES_DIR or an empty glob would otherwise make the suite
    # below silently collect zero tests instead of failing.
    assert _case_paths(), f"no conformance cases found in {CASES_DIR}"


@pytest.mark.parametrize("case_path", _case_paths(), ids=lambda p: p.stem)
def test_conformance_case(case_path: Path, san_db_ox_bin: str) -> None:
    case: dict[str, Any] = load_literal(case_path.read_bytes())
    args = [str(a) for a in case.get("args", [])]
    known_failing = case.get("known_failing")

    transport = DirectTransport.start(san_db_ox_bin, ["--serve-stdio", *args])
    try:
        transport.read_line(timeout=CALL_TIMEOUT)  # hello line, discarded

        mismatches: list[str] = []
        for i, step in enumerate(case["steps"]):
            line = dumps_literal(step["request"]).encode("utf-8") + b"\n"
            transport.write_line(line)
            try:
                resp_line = transport.read_line(timeout=CALL_TIMEOUT)
            except (TimeoutError, EOFError, OSError, ProtocolError) as e:
                # A transport-level failure is never a "known" failure --
                # it means the bug's shape changed, which known_failing
                # must not hide (conformance/README_ja.md).
                pytest.fail(f"step {i}: transport failure: {e}")

            try:
                actual = load_literal(resp_line)
            except ValueError as e:
                pytest.fail(f"step {i}: invalid JSON response: {e}")

            expect = step.get("expect")
            if expect is not None:
                mismatch = match_json(expect, actual)
                if mismatch is not None:
                    mismatches.append(f"step {i}: {mismatch}")

            expect_raw = step.get("expect_raw")
            if expect_raw:
                raw_text = resp_line.decode("utf-8", errors="replace")
                for snippet in expect_raw:
                    if snippet not in raw_text:
                        mismatches.append(
                            f"step {i}: expected raw response to contain "
                            f"{snippet!r}, got {raw_text!r}"
                        )
    finally:
        transport.close(timeout=2.0)

    if mismatches:
        message = "\n".join(mismatches)
        if known_failing:
            # xfail: run the case, but don't fail the suite over it -- the
            # bug is tracked by known_failing's own message.
            print(f"{case_path.stem}: known_failing ({known_failing}):\n{message}")
        else:
            pytest.fail(message)
    elif known_failing:
        pytest.fail(
            f"{case_path.stem} has known_failing={known_failing!r} but all "
            "steps passed -- remove known_failing now that it's fixed"
        )
