"""Tests for san_db_ox._transport.DirectTransport. No san-db-ox binary
required -- cat/sh stand in as the child process, mirroring
go/sandbox/internal/transport/direct_test.go."""

from __future__ import annotations

import io
import sys
import time

import pytest

from san_db_ox._transport import DirectTransport

pytestmark = pytest.mark.skipif(
    sys.platform == "win32", reason="cat/sh fixtures are POSIX-only"
)


def test_write_read_echo() -> None:
    t = DirectTransport.start("cat")
    try:
        t.write_line(b"hello\n")
        assert t.read_line(timeout=5.0) == b"hello"
        t.write_line(b"world\n")
        assert t.read_line(timeout=5.0) == b"world"
    finally:
        t.close(timeout=2.0)


def test_stderr_is_always_drained_even_when_discarded() -> None:
    # A chatty child must never block on a full stderr pipe, even when the
    # caller didn't ask to see stderr (protocol.md's stderr contract).
    script = (
        'i=0; while [ $i -lt 20000 ]; do echo "warning $i" >&2; i=$((i+1)); done; cat'
    )
    t = DirectTransport.start("sh", ["-c", script])
    try:
        t.write_line(b"still-alive\n")
        assert t.read_line(timeout=10.0) == b"still-alive"
    finally:
        t.close(timeout=2.0)


def test_stderr_drained_to_provided_sink() -> None:
    script = "echo one >&2; echo two >&2; cat"
    sink = io.BytesIO()
    t = DirectTransport.start("sh", ["-c", script], stderr=sink)
    try:
        t.write_line(b"ping\n")
        assert t.read_line(timeout=5.0) == b"ping"
    finally:
        t.close(timeout=2.0)
    assert sink.getvalue() == b"one\ntwo\n"


def test_close_via_stdin_reaps_promptly() -> None:
    t = DirectTransport.start("cat")
    start = time.monotonic()
    t.close(timeout=2.0)
    elapsed = time.monotonic() - start
    assert elapsed < 1.0
    assert t.exit_code == 0


def test_close_escalates_to_sigterm() -> None:
    # cat never reads stdin in this mode... use a process that ignores
    # stdin entirely so closing stdin alone can't end it, forcing the
    # staged shutdown to escalate to SIGTERM.
    t = DirectTransport.start("sh", ["-c", "while true; do sleep 0.05; done"])
    start = time.monotonic()
    t.close(timeout=0.2)
    elapsed = time.monotonic() - start
    # Must wait out the first stage before escalating...
    assert elapsed >= 0.2
    # ...but SIGTERM should end a plain sh loop well within the 2nd stage.
    assert elapsed < 2.0
    assert t.exit_code is not None
    assert t.exit_code != 0


def test_double_close_is_safe() -> None:
    t = DirectTransport.start("cat")
    t.close(timeout=2.0)
    t.close(timeout=2.0)  # must not raise


def test_read_line_timeout_does_not_hang() -> None:
    t = DirectTransport.start("cat")
    try:
        with pytest.raises(TimeoutError):
            t.read_line(timeout=0.1)
    finally:
        t.close(timeout=2.0)
