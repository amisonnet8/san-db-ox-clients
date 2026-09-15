"""Shared pytest fixtures. Mirrors go/sandbox/testutil_test.go."""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

import pytest

# Every request in these tests gets this timeout so a flush/read bug hangs
# the affected test instead of the whole suite (.claude/rules/testing.md).
CALL_TIMEOUT: float = 10.0


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


@pytest.fixture
def san_db_ox_bin() -> str:
    """Path to the san-db-ox binary under test.

    SAN_DB_OX_BIN takes priority; otherwise bin/san-db-ox (bin/san-db-ox.exe
    on Windows) at the repo root. Skips (doesn't fail) the individual test
    when neither is found, matching testing.md.
    """
    env_bin = os.environ.get("SAN_DB_OX_BIN")
    if env_bin:
        return env_bin
    name = "san-db-ox.exe" if sys.platform == "win32" else "san-db-ox"
    candidate = repo_root() / "bin" / name
    if candidate.is_file():
        return str(candidate)
    pytest.skip("no san-db-ox binary found: run `make fetch` or set SAN_DB_OX_BIN")
    raise AssertionError("unreachable")  # pytest.skip always raises


@pytest.fixture
def copy_of_binary(san_db_ox_bin: str, tmp_path: Path) -> str:
    """A private copy of the binary, for tests (overwrite) that mutate it."""
    dest = tmp_path / Path(san_db_ox_bin).name
    shutil.copy2(san_db_ox_bin, dest)
    dest.chmod(0o755)
    return str(dest)
