"""Socket transport and SocketClient tests. Mirrors
go/sandbox/internal/transport/socket_test.go and go/sandbox/socket_test.go.

DirectTransport can't be reused as the bridge's byte pipe here: it already
commits incoming bytes to line framing via its own background reader
thread, so a second consumer racing it for the same stdout would corrupt
data. The bridge below talks to the child process with a bare
subprocess.Popen instead, exactly mirroring what an external `socat` does.
"""

from __future__ import annotations

import contextlib
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
from collections.abc import Iterator, Sequence
from pathlib import Path

import pytest
from conftest import CALL_TIMEOUT

import san_db_ox
from san_db_ox._transport import SocketTransport

pytestmark = pytest.mark.skipif(
    sys.platform == "win32", reason="AF_UNIX and these fixtures are POSIX-only"
)


# --- transport-level: dial / wrap / close --------------------------------


def test_dial_tcp_echo() -> None:
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    host, port = listener.getsockname()

    def echo() -> None:
        conn, _ = listener.accept()
        with conn:
            data = conn.recv(1024)
            conn.sendall(data)

    threading.Thread(target=echo, daemon=True).start()
    t = SocketTransport.connect_tcp(host, port, timeout=5.0)
    try:
        t.write_line(b"ping\n")
        assert t.read_line(timeout=5.0) == b"ping"
    finally:
        t.close(timeout=2.0)
        listener.close()


def test_dial_unix_echo(tmp_path: Path) -> None:
    path = str(tmp_path / "echo.sock")
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(path)
    listener.listen(1)

    def echo() -> None:
        conn, _ = listener.accept()
        with conn:
            data = conn.recv(1024)
            conn.sendall(data)

    threading.Thread(target=echo, daemon=True).start()
    t = SocketTransport.connect_unix(path, timeout=5.0)
    try:
        t.write_line(b"ping\n")
        assert t.read_line(timeout=5.0) == b"ping"
    finally:
        t.close(timeout=2.0)
        listener.close()


def test_wraps_already_connected_socket() -> None:
    # Simulates the seam connect_socket exists for: wrapping a socket this
    # module didn't dial itself (e.g. the result of
    # ssl.SSLContext.wrap_socket), with no TLS-specific constructor needed.
    a, b = socket.socketpair()

    def echo() -> None:
        data = b.recv(1024)
        b.sendall(data)

    threading.Thread(target=echo, daemon=True).start()
    t = SocketTransport(a)
    try:
        t.write_line(b"hi\n")
        assert t.read_line(timeout=5.0) == b"hi"
    finally:
        t.close(timeout=2.0)
        b.close()


def test_close_unblocks_pending_read() -> None:
    a, b = socket.socketpair()
    t = SocketTransport(a)
    errors: list[BaseException] = []

    def reader() -> None:
        try:
            t.read_line(timeout=None)
        except BaseException as e:
            errors.append(e)

    th = threading.Thread(target=reader, daemon=True)
    th.start()
    time.sleep(0.05)  # give the reader a chance to actually block on recv()
    t.close(timeout=1.0)
    th.join(timeout=2.0)
    assert not th.is_alive()
    assert len(errors) == 1
    b.close()


# --- in-process bridge (socat substitute) --------------------------------


def _bridge_once(conn: socket.socket, binary: str, args: Sequence[str]) -> None:
    proc = subprocess.Popen(
        [binary, *args],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    assert proc.stdin is not None
    assert proc.stdout is not None

    def to_process() -> None:
        try:
            while True:
                data = conn.recv(65536)
                if not data:
                    break
                proc.stdin.write(data)  # type: ignore[union-attr]
                proc.stdin.flush()  # type: ignore[union-attr]
        except OSError:
            pass
        finally:
            with contextlib.suppress(OSError):
                proc.stdin.close()  # type: ignore[union-attr]

    def to_client() -> None:
        try:
            while True:
                # read1(), not read(): BufferedReader.read(n) blocks until
                # n bytes accumulate (or EOF), which would sit on a short
                # hello line forever instead of forwarding it promptly.
                # read1() returns as soon as one underlying read has data,
                # exactly what a byte-forwarding proxy needs.
                data = proc.stdout.read1(65536)  # type: ignore[union-attr]
                if not data:
                    break
                conn.sendall(data)
        except OSError:
            pass
        finally:
            with contextlib.suppress(OSError):
                conn.shutdown(socket.SHUT_WR)

    t1 = threading.Thread(target=to_process, daemon=True)
    t2 = threading.Thread(target=to_client, daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    with contextlib.suppress(subprocess.TimeoutExpired):
        proc.wait(timeout=5)
    with contextlib.suppress(OSError):
        proc.stdout.close()
    conn.close()


def _spawn_bridge_acceptor(
    listener: socket.socket, binary: str, args: Sequence[str]
) -> None:
    def serve() -> None:
        try:
            conn, _ = listener.accept()
        except OSError:
            return
        _bridge_once(conn, binary, args)

    threading.Thread(target=serve, daemon=True).start()


@pytest.fixture
def tcp_bridge(san_db_ox_bin: str) -> Iterator[tuple[str, int]]:
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    host, port = listener.getsockname()
    _spawn_bridge_acceptor(listener, san_db_ox_bin, ["--serve-stdio"])
    yield host, port
    with contextlib.suppress(OSError):
        listener.close()


@pytest.fixture
def unix_bridge(san_db_ox_bin: str, tmp_path: Path) -> Iterator[str]:
    path = str(tmp_path / "bridge.sock")
    listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    listener.bind(path)
    listener.listen(1)
    _spawn_bridge_acceptor(listener, san_db_ox_bin, ["--serve-stdio"])
    yield path
    with contextlib.suppress(OSError):
        listener.close()


def test_socket_client_over_tcp_bridge(tcp_bridge: tuple[str, int]) -> None:
    host, port = tcp_bridge
    with san_db_ox.connect_tcp(host, port, timeout=CALL_TIMEOUT) as c:
        assert c.hello.protocol == san_db_ox.PROTOCOL
        c.exec("CREATE TABLE t(x INTEGER)")
        c.exec("INSERT INTO t VALUES (1)")
        assert c.query("SELECT x FROM t").rows == [[1]]


def test_socket_client_over_unix_bridge(unix_bridge: str) -> None:
    with san_db_ox.connect_unix(unix_bridge, timeout=CALL_TIMEOUT) as c:
        assert c.hello.protocol == san_db_ox.PROTOCOL
        c.exec("CREATE TABLE t(x INTEGER)")
        assert c.query("SELECT 1").rows == [[1]]


def test_socket_client_close_reaches_bridged_process(
    unix_bridge: str,
) -> None:
    c = san_db_ox.connect_unix(unix_bridge, timeout=CALL_TIMEOUT)
    c.close()
    c.close()  # must not raise


# --- real socat, when available ------------------------------------------


def test_socket_client_via_real_socat(san_db_ox_bin: str, tmp_path: Path) -> None:
    if shutil.which("socat") is None:
        pytest.skip("socat not installed")
    path = str(tmp_path / "socat.sock")
    proc = subprocess.Popen(
        [
            "socat",
            f"UNIX-LISTEN:{path},fork",
            f"EXEC:{san_db_ox_bin} --serve-stdio",
        ]
    )
    try:
        for _ in range(50):
            if os.path.exists(path):
                break
            time.sleep(0.1)
        else:
            pytest.fail("socat did not create the socket in time")
        with san_db_ox.connect_unix(path, timeout=CALL_TIMEOUT) as c:
            assert c.hello.protocol == san_db_ox.PROTOCOL
            assert c.query("SELECT 1").rows == [[1]]
    finally:
        proc.terminate()
        with contextlib.suppress(subprocess.TimeoutExpired):
            proc.wait(timeout=5)
