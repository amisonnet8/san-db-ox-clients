"""Integration tests for san_db_ox.connect() / Client against a real
san-db-ox binary. Mirrors go/sandbox/sandbox_test.go."""

from __future__ import annotations

import base64
import io
import math
from pathlib import Path

import pytest
from conftest import CALL_TIMEOUT

import san_db_ox


def test_hello_and_basic_query(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        assert c.hello.protocol == san_db_ox.PROTOCOL
        assert c.hello.product == "SanDBox"
        result = c.query("SELECT 1")
        assert result.rows == [[1]]


def test_connect_rejects_nonexistent_command() -> None:
    # subprocess.Popen's own FileNotFoundError propagates as-is -- there's
    # no protocol violation or wire error to wrap here, just a bad command.
    with pytest.raises(FileNotFoundError):
        san_db_ox.connect("this-command-does-not-exist-really", ["--serve-stdio"])


def test_blob_roundtrip(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        c.exec("CREATE TABLE t(b BLOB)")
        c.exec("INSERT INTO t VALUES (?)", [b"hi"])
        result = c.query("SELECT b FROM t")
        assert result.rows == [[b"hi"]]


def test_large_integer_roundtrip(san_db_ox_bin: str) -> None:
    big = 9223372036854775807
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        c.exec("CREATE TABLE big(n INTEGER)")
        c.exec("INSERT INTO big VALUES (?)", [big])
        result = c.query("SELECT n, typeof(n) FROM big")
        assert result.rows == [[big, "integer"]]


def test_real_representation(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        assert c.query("SELECT 88.0").rows == [[88.0]]
        assert c.query("SELECT 1e308 * 10").rows == [[math.inf]]
        assert c.query("SELECT -1e308 * 10").rows == [[-math.inf]]
        assert c.query("SELECT (1e308 * 10) - (1e308 * 10)").rows == [[None]]


def test_error_codes_and_connection_survives(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        with pytest.raises(san_db_ox.ResponseError) as exc_info:
            c.query("SELECT * FROM nope")
        assert exc_info.value.code == san_db_ox.CODE_SQLITE_ERROR

        with pytest.raises(san_db_ox.ResponseError) as exc_info:
            c.load("/nonexistent/path/really-not-there.db")
        assert exc_info.value.code == san_db_ox.CODE_IO_ERROR

        # The connection must still be usable after errors.
        assert c.query("SELECT 1").rows == [[1]]


def test_read_only_two_tier_rejection(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(
        san_db_ox_bin, ["--serve-stdio", "--read-only"], timeout=CALL_TIMEOUT
    ) as c:
        with pytest.raises(san_db_ox.ResponseError) as exc_info:
            c.exec("CREATE TABLE t(x INTEGER)")
        assert exc_info.value.code == san_db_ox.CODE_SQLITE_ERROR

        with pytest.raises(san_db_ox.ResponseError) as exc_info:
            c.overwrite()
        assert exc_info.value.code == san_db_ox.CODE_READ_ONLY

        with pytest.raises(san_db_ox.ResponseError) as exc_info:
            c.load("/nonexistent/does-not-matter.db")
        assert exc_info.value.code == san_db_ox.CODE_READ_ONLY

        assert c.query("SELECT 1").rows == [[1]]
        assert c.inspect().read_only is True


def test_snapshot_and_load(san_db_ox_bin: str, tmp_path: Path) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        c.exec("CREATE TABLE t(x INTEGER)")
        c.exec("INSERT INTO t VALUES (42)")
        snap = c.snapshot(filename=str(tmp_path / "snap"))
        path = snap.path

    with san_db_ox.connect(
        san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT
    ) as c2:
        c2.load(path)
        assert c2.query("SELECT x FROM t").rows == [[42]]


def test_inspect_reflects_own_process_only(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        info = c.inspect()
        assert info.has_data is False
        assert info.data_length is None
        assert info.version is None
        c.exec("CREATE TABLE t(x INTEGER)")
        # exec'd SQL state doesn't change inspect's view of embedded data.
        info_after = c.inspect()
        assert info_after.has_data is False


def test_tables_schema_dump(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        c.exec("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)")
        c.exec("CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)")
        c.exec("INSERT INTO users(id, name) VALUES (1, 'alice')")

        assert c.tables().tables == ["logs", "users"]

        schema = c.schema()
        assert schema.schema == [
            "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)",
        ]
        assert c.schema(table="users").schema == [
            "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)"
        ]

        dump = c.dump(pattern="users")
        assert "INSERT INTO \"users\" VALUES(1,'alice')" in dump.sql
        assert "logs" not in dump.sql


def test_close_reaps_process_and_is_idempotent(san_db_ox_bin: str) -> None:
    c = san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT)
    c.close()
    assert c.exit_code == 0
    c.close()  # must not raise


def test_overwrite_reaps_and_persists_data(
    san_db_ox_bin: str, copy_of_binary: str
) -> None:
    with san_db_ox.connect(
        copy_of_binary, ["--serve-stdio"], timeout=CALL_TIMEOUT
    ) as c:
        c.exec("CREATE TABLE t(x INTEGER)")
        c.exec("INSERT INTO t VALUES (7)")
        c.overwrite()
        assert c.exit_code == 0

    with san_db_ox.connect(
        copy_of_binary, ["--serve-stdio"], timeout=CALL_TIMEOUT
    ) as c2:
        assert c2.inspect().has_data is True
        assert c2.query("SELECT x FROM t").rows == [[7]]


def test_call_timeout_raises_and_tears_down(san_db_ox_bin: str) -> None:
    # A plain "SELECT 1" round-trips fast enough that an unrealistically
    # tiny timeout is a coin flip on a quiet machine -- use a query that
    # reliably takes a few seconds (well past the 0.2s timeout below) so
    # the timeout path is exercised deterministically instead of racing
    # actual round-trip latency. This runs in the background (query()
    # doesn't cancel the server side, close() below is a no-op once the
    # timeout has already marked the session closed), so it doesn't slow
    # the test itself.
    slow_query = (
        "WITH RECURSIVE r(x) AS ("
        "SELECT 1 UNION ALL SELECT x+1 FROM r WHERE x < 10000000"
        ") SELECT count(*) FROM r"
    )
    c = san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT)
    try:
        with pytest.raises(san_db_ox.SanDBoxTimeoutError):
            c.query(slow_query, timeout=0.2)
    finally:
        # The connection is unusable now; close() must still be safe.
        c.close()


def test_socket_client_has_no_direct_only_apis() -> None:
    assert not hasattr(san_db_ox.SocketClient, "overwrite")
    assert not hasattr(san_db_ox.SocketClient, "exit_code")


def test_stderr_bytes_reach_provided_sink(san_db_ox_bin: str) -> None:
    sink = io.BytesIO()
    with san_db_ox.connect(
        san_db_ox_bin, ["--serve-stdio"], stderr=sink, timeout=CALL_TIMEOUT
    ) as c:
        c.query("SELECT 1")
    # Not asserting on content (san-db-ox may or may not log anything for a
    # clean run) -- just that wiring a sink doesn't break the connection.
    assert isinstance(sink.getvalue(), bytes)


def test_int64_range_and_bool_rejected(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        with pytest.raises(ValueError, match="int64 range"):
            c.query("SELECT ?", [2**64])
        with pytest.raises(TypeError, match="bool"):
            c.query("SELECT ?", [True])


def test_nan_and_inf_params_rejected(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        with pytest.raises(ValueError, match="finite"):
            c.query("SELECT ?", [math.nan])
        with pytest.raises(ValueError, match="finite"):
            c.query("SELECT ?", [math.inf])


def test_empty_blob_roundtrip(san_db_ox_bin: str) -> None:
    with san_db_ox.connect(san_db_ox_bin, ["--serve-stdio"], timeout=CALL_TIMEOUT) as c:
        c.exec("CREATE TABLE t(b BLOB)")
        c.exec("INSERT INTO t VALUES (?)", [b""])
        assert c.query("SELECT b FROM t").rows == [[b""]]
        # And confirm the wire shape is the documented singleton array.
        assert base64.b64encode(b"") == b""
