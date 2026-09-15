"""Client for SanDBox: connect over its stdio protocol from Python.

A driver is a convenience, not a prerequisite -- the wire protocol is plain
JSON Lines, and nothing here does more than give it a typed Python API. Op
names mirror the protocol's own op names 1:1: query, exec, snapshot, load,
inspect, tables, schema, dump, overwrite, close.
"""

from __future__ import annotations

from san_db_ox._client import (
    CLOSE_TIMEOUT,
    DEFAULT_TIMEOUT,
    Client,
    Connection,
    SocketClient,
    connect,
    connect_socket,
    connect_tcp,
    connect_unix,
)
from san_db_ox._codec import (
    CODE_BAD_REQUEST,
    CODE_IO_ERROR,
    CODE_READ_ONLY,
    CODE_SQLITE_ERROR,
    CODE_UNSUPPORTED_OP,
    PROTOCOL,
    DumpResult,
    ExecResult,
    Hello,
    InspectResult,
    ProtocolError,
    QueryResult,
    ResponseError,
    Row,
    SanDBoxError,
    SanDBoxTimeoutError,
    SchemaResult,
    SnapshotResult,
    TablesResult,
    Value,
)

__all__ = [
    "CLOSE_TIMEOUT",
    "CODE_BAD_REQUEST",
    "CODE_IO_ERROR",
    "CODE_READ_ONLY",
    "CODE_SQLITE_ERROR",
    "CODE_UNSUPPORTED_OP",
    "DEFAULT_TIMEOUT",
    "PROTOCOL",
    "Client",
    "Connection",
    "DumpResult",
    "ExecResult",
    "Hello",
    "InspectResult",
    "ProtocolError",
    "QueryResult",
    "ResponseError",
    "Row",
    "SanDBoxError",
    "SanDBoxTimeoutError",
    "SchemaResult",
    "SnapshotResult",
    "SocketClient",
    "TablesResult",
    "Value",
    "connect",
    "connect_socket",
    "connect_tcp",
    "connect_unix",
]

__version__ = "0.1.0"
