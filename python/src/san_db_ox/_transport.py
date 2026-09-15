"""Direct (subprocess) and Socket transports for SanDBox's stdio protocol.

A transport only knows how to read/write bytes and manage the connection's
lifetime; it knows nothing about JSON Lines framing or the op vocabulary
(that's _codec.py's job -- .claude/rules/architecture.md).

The background line-reading thread lives here, not in the client/session
layer above: how to unblock a stuck read differs per transport (kill the
child process for Direct, shutdown() the socket for Socket), so each
transport is responsible for its own read cancellation.
"""

from __future__ import annotations

import contextlib
import os
import queue
import socket as socket_module
import subprocess
import threading
from collections.abc import Mapping, Sequence
from typing import IO, Protocol

from san_db_ox._codec import MAX_LINE_BYTES, ProtocolError


class Transport(Protocol):
    """What every connection to a SanDBox process can do, regardless of
    transport. Structural (no common base class needed)."""

    def write_line(self, line: bytes) -> None: ...
    def read_line(self, timeout: float | None) -> bytes: ...
    def close(self, timeout: float) -> None: ...


class _LineReader:
    """Background thread reading newline-delimited lines from a byte stream
    into a backpressured queue.

    Mirrors Go's session.readLoop: responses are framed one per line and
    always arrive in order, so a single reader thread feeding one slot is
    enough -- callers never race independent reads against each other.
    """

    def __init__(self, stream: IO[bytes]) -> None:
        self._stream = stream
        self._queue: queue.Queue[bytes | BaseException] = queue.Queue(maxsize=1)
        # queue.Queue has no close(): once the terminal item (EOF/error) is
        # put, a second get() would block forever unless we remember it
        # ourselves and keep handing it back.
        self._terminal: BaseException | None = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            while True:
                line = self._stream.readline(MAX_LINE_BYTES + 1)
                if not line:
                    self._queue.put(EOFError("connection closed"))
                    return
                if not line.endswith(b"\n"):
                    self._queue.put(
                        ProtocolError(
                            f"response line exceeds {MAX_LINE_BYTES} bytes "
                            "without a newline"
                        )
                    )
                    return
                self._queue.put(line[:-1])
        except OSError as e:
            self._queue.put(e)

    def read_line(self, timeout: float | None) -> bytes:
        if self._terminal is not None:
            raise self._terminal
        try:
            item = self._queue.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError("timed out waiting for a response line") from None
        if isinstance(item, BaseException):
            self._terminal = item
            raise item
        return item

    def join(self, timeout: float | None) -> None:
        self._thread.join(timeout=timeout)


def _drain(src: IO[bytes], dst: IO[bytes]) -> None:
    """Copy src to dst until EOF, swallowing errors from either side
    closing first. Used for stderr, which must always be drained
    (protocol.md) even when the caller doesn't want to see it."""
    try:
        while True:
            chunk = src.read(65536)
            if not chunk:
                return
            dst.write(chunk)
    except (OSError, ValueError):
        return


class DirectTransport:
    """Direct-connect transport: a child process, talked to over its
    stdin/stdout. Mirrors go/sandbox/internal/transport/direct.go.
    """

    def __init__(
        self, proc: subprocess.Popen[bytes], stderr_thread: threading.Thread | None
    ) -> None:
        assert proc.stdin is not None
        assert proc.stdout is not None
        self._proc = proc
        self._stdin: IO[bytes] = proc.stdin
        self._stdout: IO[bytes] = proc.stdout
        self._reader = _LineReader(proc.stdout)
        self._stderr_thread = stderr_thread

    @classmethod
    def start(
        cls,
        command: str,
        args: Sequence[str] = (),
        *,
        env: Mapping[str, str] | None = None,
        cwd: str | os.PathLike[str] | None = None,
        stderr: IO[bytes] | None = None,
    ) -> DirectTransport:
        # stderr is always drained -- even when discarded -- so a chatty
        # server can never block on a full pipe (protocol.md). DEVNULL has
        # no pipe to fill in the first place, so a background drain thread
        # is only needed when a caller actually wants the bytes.
        stderr_pipe = subprocess.PIPE if stderr is not None else subprocess.DEVNULL
        proc = subprocess.Popen(
            [command, *args],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=stderr_pipe,
            env=dict(env) if env is not None else None,
            cwd=cwd,
        )
        stderr_thread = None
        if stderr is not None:
            assert proc.stderr is not None
            stderr_thread = threading.Thread(
                target=_drain, args=(proc.stderr, stderr), daemon=True
            )
            stderr_thread.start()
        return cls(proc, stderr_thread)

    def write_line(self, line: bytes) -> None:
        # protocol.md: flush after every request. Popen's pipes are
        # buffered (unlike Go's raw os.Pipe), so this is required, not
        # optional.
        self._stdin.write(line)
        self._stdin.flush()

    def read_line(self, timeout: float | None) -> bytes:
        return self._reader.read_line(timeout)

    @property
    def exit_code(self) -> int | None:
        return self._proc.poll()

    def close(self, timeout: float) -> None:
        # Staged shutdown, matching direct.go: stdin close -> wait ->
        # SIGTERM -> wait -> SIGKILL. Popen.terminate()/.kill() already
        # abstract the POSIX/Windows signal difference that direct.go
        # handles by hand.
        with contextlib.suppress(OSError):
            self._stdin.close()
        try:
            self._proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait()
        if self._stderr_thread is not None:
            self._stderr_thread.join(timeout=timeout)
            assert self._proc.stderr is not None
            with contextlib.suppress(OSError):
                self._proc.stderr.close()
        # The process has exited by now, so its end of the pipe is closed
        # and the reader thread's blocking readline() has already returned
        # (or is about to) -- join before closing the stream out from under
        # it.
        self._reader.join(timeout=timeout)
        with contextlib.suppress(OSError):
            self._stdout.close()


class SocketTransport:
    """Socket transport: a TCP/UNIX socket, typically fronted by something
    like socat. Mirrors go/sandbox/internal/transport/socket.go.

    Unlike DirectTransport, close has no staged escalation: there is no
    child process to signal or reap here.
    """

    def __init__(self, sock: socket_module.socket) -> None:
        self._sock = sock
        self._file: IO[bytes] = sock.makefile("rb")
        self._reader = _LineReader(self._file)

    @classmethod
    def connect_tcp(
        cls, host: str, port: int, *, timeout: float | None
    ) -> SocketTransport:
        sock = socket_module.create_connection((host, port), timeout=timeout)
        sock.settimeout(None)  # back to blocking mode for the reader thread
        return cls(sock)

    @classmethod
    def connect_unix(
        cls, path: str | os.PathLike[str], *, timeout: float | None
    ) -> SocketTransport:
        sock = socket_module.socket(socket_module.AF_UNIX, socket_module.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect(os.fspath(path))
        sock.settimeout(None)
        return cls(sock)

    def write_line(self, line: bytes) -> None:
        self._sock.sendall(line)

    def read_line(self, timeout: float | None) -> bytes:
        return self._reader.read_line(timeout)

    def close(self, timeout: float) -> None:
        try:
            self._sock.settimeout(timeout)
            # shutdown() (unlike close()) reliably unblocks a concurrent
            # blocking recv() in the reader thread on every platform we
            # support -- Go achieves the same with SetDeadline.
            self._sock.shutdown(socket_module.SHUT_RDWR)
        except OSError:
            pass
        self._reader.join(timeout=timeout)
        with contextlib.suppress(OSError):
            self._file.close()
        self._sock.close()
