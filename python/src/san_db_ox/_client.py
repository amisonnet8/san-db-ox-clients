"""Session, Client, and SocketClient: the public connection objects.

The protocol has no request id, so responses can only be matched to
requests by strict ordering. A lock serializing calls, on top of each
transport's own single background reader thread (san_db_ox._transport), is
enough to make that safe without exposing the ordering constraint to
callers (mirrors go/sandbox/sandbox.go's session).
"""

from __future__ import annotations

import contextlib
import os
import socket as socket_module
import threading
from collections.abc import Mapping, Sequence
from typing import IO, Any, Protocol, TypeVar

from san_db_ox._codec import (
    PROTOCOL,
    DumpResult,
    ExecResult,
    Fields,
    Hello,
    InspectResult,
    ParamValue,
    ProtocolError,
    QueryResult,
    SanDBoxError,
    SanDBoxTimeoutError,
    SchemaResult,
    SnapshotResult,
    TablesResult,
    decode_dump_response,
    decode_exec_response,
    decode_hello,
    decode_inspect_response,
    decode_query_response,
    decode_response_line,
    decode_schema_response,
    decode_snapshot_response,
    decode_tables_response,
    encode_params,
    encode_request_line,
)
from san_db_ox._transport import DirectTransport, SocketTransport, Transport

DEFAULT_TIMEOUT: float = 30.0

# How long each stage of close() waits before escalating (Client) or before
# giving up on a graceful shutdown (SocketClient).
CLOSE_TIMEOUT: float = 5.0


class _Unset:
    """Sentinel distinguishing "use the connection's default timeout"
    (unset) from an explicit timeout=None ("wait forever")."""

    __slots__ = ()

    def __repr__(self) -> str:
        return "<unset>"


_UNSET = _Unset()
_SessionT = TypeVar("_SessionT", bound="_Session")
TimeoutArg = "float | None | _Unset"


class Connection(Protocol):
    """What every connection to a SanDBox process can do, regardless of
    transport. overwrite/exit_code are deliberately not part of this --
    they only mean something over a direct connection, and are only on
    Client."""

    def query(
        self, sql: str, params: Sequence[ParamValue] = (), *, timeout: Any = _UNSET
    ) -> QueryResult: ...

    def exec(
        self, sql: str, params: Sequence[ParamValue] = (), *, timeout: Any = _UNSET
    ) -> ExecResult: ...

    def snapshot(
        self,
        *,
        filename: str | None = None,
        sqlite: bool = False,
        timestamp: bool = False,
        timeout: Any = _UNSET,
    ) -> SnapshotResult: ...

    def load(self, path: str, *, timeout: Any = _UNSET) -> None: ...

    def inspect(self, *, timeout: Any = _UNSET) -> InspectResult: ...

    def tables(self, *, timeout: Any = _UNSET) -> TablesResult: ...

    def schema(
        self, table: str | None = None, *, timeout: Any = _UNSET
    ) -> SchemaResult: ...

    def dump(
        self, pattern: str | None = None, *, timeout: Any = _UNSET
    ) -> DumpResult: ...

    def close(self, *, timeout: float = CLOSE_TIMEOUT) -> None: ...


class _Session:
    """Machinery shared by every transport: hello validation and the
    serialized call/response cycle. Client and SocketClient each hold a
    _Session; neither subclasses it publicly."""

    hello: Hello

    def __init__(self, transport: Transport, timeout: float | None) -> None:
        self._transport = transport
        self._default_timeout = timeout
        self._lock = threading.Lock()
        self._closed = False
        self.hello = self._read_hello()

    def _read_hello(self) -> Hello:
        try:
            line = self._transport.read_line(self._default_timeout)
        except (EOFError, OSError) as e:
            self._transport.close(CLOSE_TIMEOUT)
            raise SanDBoxError(f"reading hello line: {e}") from e
        try:
            hello = decode_hello(line)
        except ProtocolError:
            self._transport.close(CLOSE_TIMEOUT)
            raise
        if hello.protocol != PROTOCOL:
            self._transport.close(CLOSE_TIMEOUT)
            raise SanDBoxError(
                f"unsupported protocol {hello.protocol} (this driver speaks {PROTOCOL})"
            )
        return hello

    def _resolve_timeout(self, timeout: float | _Unset | None) -> float | None:
        if isinstance(timeout, _Unset):
            return self._default_timeout
        return timeout

    def _call(self, req: dict[str, Any], timeout: float | _Unset | None) -> Fields:
        resolved = self._resolve_timeout(timeout)
        with self._lock:
            if self._closed:
                raise SanDBoxError("sandbox: connection is closed")
            try:
                self._transport.write_line(encode_request_line(req))
            except OSError as e:
                raise SanDBoxError(f"writing request: {e}") from e
            try:
                line = self._transport.read_line(resolved)
            except TimeoutError as e:
                # The read is stuck; there is no way to interrupt a single
                # pending read short of tearing down the connection, so do
                # that in the background -- the caller asked to give up,
                # and a session that can never be used again is a fair
                # price to guarantee no thread or process is leaked.
                self._closed = True
                threading.Thread(
                    target=self._transport.close,
                    args=(CLOSE_TIMEOUT,),
                    daemon=True,
                ).start()
                raise SanDBoxTimeoutError(str(e)) from e
            except (EOFError, OSError) as e:
                raise SanDBoxError(f"reading response: {e}") from e
            ok, fields, err = decode_response_line(line)
        if not ok:
            if err is not None:
                raise err
            raise ProtocolError("response has ok=false with no error field")
        return fields

    def query(
        self,
        sql: str,
        params: Sequence[ParamValue] = (),
        *,
        timeout: float | _Unset | None = _UNSET,
    ) -> QueryResult:
        """Run a SQL query and return its result set in full (the protocol
        has no cursor -- there is no way to fetch a result set
        incrementally)."""
        req: dict[str, Any] = {"op": "query", "sql": sql}
        encoded = encode_params(params)
        if encoded is not None:
            req["params"] = encoded
        return decode_query_response(self._call(req, timeout))

    def exec(
        self,
        sql: str,
        params: Sequence[ParamValue] = (),
        *,
        timeout: float | _Unset | None = _UNSET,
    ) -> ExecResult:
        """Run a SQL statement and return rows affected / last insert id."""
        req: dict[str, Any] = {"op": "exec", "sql": sql}
        encoded = encode_params(params)
        if encoded is not None:
            req["params"] = encoded
        return decode_exec_response(self._call(req, timeout))

    def snapshot(
        self,
        *,
        filename: str | None = None,
        sqlite: bool = False,
        timestamp: bool = False,
        timeout: float | _Unset | None = _UNSET,
    ) -> SnapshotResult:
        """Save the current database and return the path written."""
        req: dict[str, Any] = {"op": "snapshot"}
        if filename:
            req["filename"] = filename
        if sqlite:
            req["sqlite"] = sqlite
        if timestamp:
            req["timestamp"] = timestamp
        return decode_snapshot_response(self._call(req, timeout))

    def load(self, path: str, *, timeout: float | _Unset | None = _UNSET) -> None:
        """Replace the running database with the one at path."""
        self._call({"op": "load", "path": path}, timeout)

    def inspect(self, *, timeout: float | _Unset | None = _UNSET) -> InspectResult:
        """Report on the running process's own embedded data. Not a
        general-purpose "inspect any path" op -- it only ever describes
        this process."""
        return decode_inspect_response(self._call({"op": "inspect"}, timeout))

    def tables(self, *, timeout: float | _Unset | None = _UNSET) -> TablesResult:
        """List table names."""
        return decode_tables_response(self._call({"op": "tables"}, timeout))

    def schema(
        self,
        table: str | None = None,
        *,
        timeout: float | _Unset | None = _UNSET,
    ) -> SchemaResult:
        """Return CREATE statements, optionally filtered to one table."""
        req: dict[str, Any] = {"op": "schema"}
        if table:
            req["table"] = table
        return decode_schema_response(self._call(req, timeout))

    def dump(
        self,
        pattern: str | None = None,
        *,
        timeout: float | _Unset | None = _UNSET,
    ) -> DumpResult:
        """Return a SQL dump, optionally filtered by pattern (server
        defaults to "%", i.e. everything, when pattern is omitted)."""
        req: dict[str, Any] = {"op": "dump"}
        if pattern:
            req["pattern"] = pattern
        return decode_dump_response(self._call(req, timeout))

    def close(self, *, timeout: float = CLOSE_TIMEOUT) -> None:
        """Gracefully end the connection: send the close op, and regardless
        of whether a response arrives, follow through with the transport's
        own shutdown so nothing is left dangling. Safe to call more than
        once."""
        with self._lock:
            if self._closed:
                return
            self._closed = True
            # Best effort: send the close op so a well-behaved server exits
            # promptly. Its response (or the lack of one) doesn't change
            # what happens next -- the shutdown below ends the connection
            # either way.
            with contextlib.suppress(OSError):
                self._transport.write_line(encode_request_line({"op": "close"}))
        self._transport.close(timeout)

    def __enter__(self: _SessionT) -> _SessionT:
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()


class Client(_Session):
    """A direct-connect connection to a running SanDBox process.

    Not safe for concurrent use by multiple threads: the protocol has no
    request id, so responses can only be matched to requests by strict
    ordering -- _Session serializes calls with an internal lock rather than
    exposing that footgun.
    """

    def __init__(self, transport: DirectTransport, timeout: float | None) -> None:
        super().__init__(transport, timeout)
        self._direct = transport

    def overwrite(self, *, timeout: float | _Unset | None = _UNSET) -> None:
        """Replace the running process's own executable with one embedding
        the current database, then exit. This only makes sense over a
        direct-connect process -- socat-fronted sockets can have several
        clients connect through the same listener, and multiple processes
        writing the same executable path at once is exactly what this op
        shouldn't risk -- so it lives here on Client, not on Connection,
        and SocketClient has no overwrite method to call."""
        self._call({"op": "overwrite"}, timeout)
        # A successful overwrite ends the connection from the server's
        # side; reap it so no zombie is left behind.
        with self._lock:
            self._closed = True
        self._direct.close(CLOSE_TIMEOUT)

    @property
    def exit_code(self) -> int | None:
        """The child process's exit code. Only meaningful after close() or
        overwrite() has returned. Only available over a direct connection
        -- SocketClient has no child process to report on."""
        return self._direct.exit_code


class SocketClient(_Session):
    """A socket connection to a SanDBox process, typically fronted by
    something like socat. Has no overwrite or exit_code -- see Client."""


def connect(
    command: str,
    args: Sequence[str] = (),
    *,
    env: Mapping[str, str] | None = None,
    cwd: str | os.PathLike[str] | None = None,
    stderr: IO[bytes] | None = None,
    timeout: float | None = DEFAULT_TIMEOUT,
) -> Client:
    """Launch command with args as a child process, read its hello line,
    and return a ready-to-use Client.

    timeout bounds the connection attempt (reading the hello line) and
    becomes the default for methods that don't pass their own timeout.
    """
    transport = DirectTransport.start(command, args, env=env, cwd=cwd, stderr=stderr)
    return Client(transport, timeout)


def connect_tcp(
    host: str, port: int, *, timeout: float | None = DEFAULT_TIMEOUT
) -> SocketClient:
    """Dial a TCP socket exposing SanDBox's stdio protocol (e.g. via
    socat), read its hello line, and return a ready-to-use SocketClient."""
    transport = SocketTransport.connect_tcp(host, port, timeout=timeout)
    return SocketClient(transport, timeout)


def connect_unix(
    path: str | os.PathLike[str], *, timeout: float | None = DEFAULT_TIMEOUT
) -> SocketClient:
    """Dial a UNIX domain socket exposing SanDBox's stdio protocol (e.g.
    via socat), read its hello line, and return a ready-to-use
    SocketClient."""
    transport = SocketTransport.connect_unix(path, timeout=timeout)
    return SocketClient(transport, timeout)


def connect_socket(
    sock: socket_module.socket, *, timeout: float | None = DEFAULT_TIMEOUT
) -> SocketClient:
    """Wrap an already-connected socket, read its hello line, and return a
    ready-to-use SocketClient. This is the seam for connections
    connect_tcp/connect_unix can't build directly -- most notably a
    TLS-wrapped socket (ssl.SSLContext.wrap_socket) for mutual-TLS
    authentication -- without this module needing a TLS-specific
    constructor."""
    transport = SocketTransport(sock)
    return SocketClient(transport, timeout)
