"""JSON Lines encoding/decoding for SanDBox's stdio protocol.

This module knows nothing about I/O or process/socket lifetimes -- it only
converts between Python values and the wire representation (protocol.md).
Keeping it pure lets python-netcheck confirm it never pulls in networking or
subprocess machinery (.claude/rules/architecture.md).
"""

from __future__ import annotations

import base64
import binascii
import json
import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Final, NoReturn, TypeAlias

PROTOCOL: Final[int] = 1

# Sized for upstream's stated 1 MiB line limit, with headroom above it.
MAX_LINE_BYTES: Final[int] = 2 * 1024 * 1024

INT64_MIN: Final[int] = -(2**63)
INT64_MAX: Final[int] = 2**63 - 1

# The 5 stable error codes (protocol.md). Never branch on `message` --
# upstream's wording can change between releases without notice.
CODE_SQLITE_ERROR: Final[str] = "sqlite_error"
CODE_BAD_REQUEST: Final[str] = "bad_request"
CODE_IO_ERROR: Final[str] = "io_error"
CODE_UNSUPPORTED_OP: Final[str] = "unsupported_op"
CODE_READ_ONLY: Final[str] = "read_only"

# Values as they come back out of a response (BLOB decodes to bytes).
Value: TypeAlias = "int | float | str | bytes | None"
Row: TypeAlias = "list[Value]"

# Values a caller may pass in as a query/exec parameter. Bytes-like inputs
# are accepted for convenience; decode always hands back plain bytes.
ParamValue: TypeAlias = "int | float | str | bytes | bytearray | memoryview | None"

Fields: TypeAlias = "Mapping[str, Any]"


class SanDBoxError(Exception):
    """Base class for every error this driver raises."""


class ResponseError(SanDBoxError):
    """A stdio protocol error response.

    Compare .code against the CODE_* constants above; .message is
    upstream's own text and can change wording between releases without
    notice, so never branch on it.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class ProtocolError(SanDBoxError):
    """The driver saw something that violates the stdio contract (protocol.md)."""


class SanDBoxTimeoutError(SanDBoxError, TimeoutError):
    """A call did not receive a response within its timeout."""


@dataclass(frozen=True, slots=True)
class Hello:
    protocol: int
    version: str
    product: str


@dataclass(frozen=True, slots=True)
class QueryResult:
    columns: list[str]
    rows: list[Row]


@dataclass(frozen=True, slots=True)
class ExecResult:
    rows_affected: int
    last_insert_id: int


@dataclass(frozen=True, slots=True)
class SnapshotResult:
    path: str


@dataclass(frozen=True, slots=True)
class InspectResult:
    has_data: bool
    version: int | None
    data_length: int | None
    source: str
    read_only: bool


@dataclass(frozen=True, slots=True)
class TablesResult:
    tables: list[str]


@dataclass(frozen=True, slots=True)
class SchemaResult:
    schema: list[str]


@dataclass(frozen=True, slots=True)
class DumpResult:
    sql: str


def encode_value(v: ParamValue) -> Any:
    """Encode a single param value to something json.dumps can render."""
    if v is None:
        return None
    # bool is a subclass of int in Python; san-db-ox has no boolean SQLite
    # type, and Go's type switch rejects it too, so check this first.
    if isinstance(v, bool):
        raise TypeError(f"bool is not a supported param type (got {v!r})")
    if isinstance(v, int):
        if not (INT64_MIN <= v <= INT64_MAX):
            raise ValueError(f"int param out of int64 range: {v!r}")
        return v
    if isinstance(v, float):
        if math.isnan(v) or math.isinf(v):
            raise ValueError(
                "REAL param must be finite (san-db-ox rejects NaN/Inf in "
                f"params): {v!r}"
            )
        # Unlike Go, no custom formatter is needed here: repr() of a finite
        # float always contains "." or "e", which is exactly the token
        # shape san-db-ox uses to bind REAL rather than INTEGER, and
        # json.dumps renders floats via repr().
        return v
    if isinstance(v, (bytes, bytearray, memoryview)):
        return [base64.b64encode(bytes(v)).decode("ascii")]
    if isinstance(v, str):
        return v
    raise TypeError(
        f"unsupported param type {type(v).__name__} "
        "(want None, int, float, str, or bytes-like)"
    )


def encode_params(params: Sequence[ParamValue]) -> list[Any] | None:
    """Encode a params sequence, or None if it should be omitted."""
    if isinstance(params, (str, bytes, bytearray)):
        raise TypeError(
            "params must be a sequence of values, not a single str/bytes/bytearray"
        )
    if not params:
        return None
    encoded: list[Any] = []
    for i, v in enumerate(params):
        try:
            encoded.append(encode_value(v))
        except (TypeError, ValueError) as e:
            raise type(e)(f"params[{i}]: {e}") from e
    return encoded


def encode_request_line(req: Mapping[str, Any]) -> bytes:
    """Encode a request object as one JSON Lines record, newline included."""
    text = json.dumps(req, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return text.encode("utf-8") + b"\n"


def _reject_constant(name: str) -> NoReturn:
    # san-db-ox never emits the nonstandard NaN/Infinity/-Infinity bareword
    # tokens (it uses null/9e999/-9e999) -- treat one as a protocol
    # violation rather than silently accepting an unknown representation.
    raise ProtocolError(f"unexpected non-finite constant in response: {name}")


def decode_hello(line: bytes) -> Hello:
    """Decode a hello line. Does not validate the protocol number -- that's
    the caller's job, since only it knows what happens next (protocol.md)."""
    try:
        obj = json.loads(line)
    except ValueError as e:
        raise ProtocolError(f"invalid hello line: {e}") from e
    if not isinstance(obj, dict):
        raise ProtocolError(f"hello line is not a JSON object: {line!r}")
    try:
        return Hello(
            protocol=obj["protocol"], version=obj["version"], product=obj["product"]
        )
    except KeyError as e:
        raise ProtocolError(f"hello line missing field {e}") from e


def decode_response_line(line: bytes) -> tuple[bool, Fields, ResponseError | None]:
    """Decode one response line.

    Returns (ok, fields, error). On success, fields includes every
    top-level field of the response (including "ok"), which is what the
    conformance runner needs for partial-match comparisons; typed decoders
    below only look at the fields relevant to their op.
    """
    try:
        obj = json.loads(line, parse_constant=_reject_constant)
    except ValueError as e:
        raise ProtocolError(f"invalid response line: {e}") from e
    if not isinstance(obj, dict):
        raise ProtocolError(f"response line is not a JSON object: {line!r}")
    if obj.get("ok") is True:
        return True, obj, None
    error_obj = obj.get("error")
    if isinstance(error_obj, dict) and "code" in error_obj and "message" in error_obj:
        err = ResponseError(str(error_obj["code"]), str(error_obj["message"]))
        return False, obj, err
    return False, obj, None


def decode_value(raw: Any) -> Value:
    if raw is None:
        return None
    if isinstance(raw, bool):
        raise ProtocolError(f"unexpected boolean value in response: {raw!r}")
    if isinstance(raw, (int, float, str)):
        return raw
    if isinstance(raw, list):
        if len(raw) != 1 or not isinstance(raw[0], str):
            raise ProtocolError(
                f"protocol violation: BLOB array must have exactly 1 "
                f"string element, got {raw!r}"
            )
        try:
            return base64.b64decode(raw[0], validate=True)
        except binascii.Error as e:
            raise ProtocolError(f"invalid BLOB base64: {e}") from e
    raise ProtocolError(f"unexpected value type in response: {type(raw).__name__}")


def decode_rows(raw: Any) -> list[Row]:
    if not isinstance(raw, list):
        raise ProtocolError(f"rows field is not an array: {raw!r}")
    rows: list[Row] = []
    for i, row in enumerate(raw):
        if not isinstance(row, list):
            raise ProtocolError(f"rows[{i}] is not an array: {row!r}")
        try:
            rows.append([decode_value(v) for v in row])
        except ProtocolError as e:
            raise ProtocolError(f"rows[{i}]: {e}") from e
    return rows


def _required(fields: Fields, key: str) -> Any:
    if key not in fields:
        raise ProtocolError(f"response missing field {key!r}")
    return fields[key]


def _required_str(fields: Fields, key: str) -> str:
    v = _required(fields, key)
    if not isinstance(v, str):
        raise ProtocolError(f"response field {key!r} is not a string: {v!r}")
    return v


def _required_bool(fields: Fields, key: str) -> bool:
    v = _required(fields, key)
    if not isinstance(v, bool):
        raise ProtocolError(f"response field {key!r} is not a bool: {v!r}")
    return v


def _required_int(fields: Fields, key: str) -> int:
    v = _required(fields, key)
    if isinstance(v, bool) or not isinstance(v, int):
        raise ProtocolError(f"response field {key!r} is not an integer: {v!r}")
    return v


def _required_optional_int(fields: Fields, key: str) -> int | None:
    v = _required(fields, key)
    if v is None:
        return None
    if isinstance(v, bool) or not isinstance(v, int):
        raise ProtocolError(f"response field {key!r} is not an integer or null: {v!r}")
    return v


def _required_str_list(fields: Fields, key: str) -> list[str]:
    v = _required(fields, key)
    if not isinstance(v, list) or not all(isinstance(x, str) for x in v):
        raise ProtocolError(f"response field {key!r} is not a string array: {v!r}")
    return list(v)


def decode_query_response(fields: Fields) -> QueryResult:
    columns = _required_str_list(fields, "columns")
    rows = decode_rows(_required(fields, "rows"))
    return QueryResult(columns=columns, rows=rows)


def decode_exec_response(fields: Fields) -> ExecResult:
    return ExecResult(
        rows_affected=_required_int(fields, "rows_affected"),
        last_insert_id=_required_int(fields, "last_insert_id"),
    )


def decode_snapshot_response(fields: Fields) -> SnapshotResult:
    return SnapshotResult(path=_required_str(fields, "path"))


def decode_inspect_response(fields: Fields) -> InspectResult:
    return InspectResult(
        has_data=_required_bool(fields, "has_data"),
        version=_required_optional_int(fields, "version"),
        data_length=_required_optional_int(fields, "data_length"),
        source=_required_str(fields, "source"),
        read_only=_required_bool(fields, "read_only"),
    )


def decode_tables_response(fields: Fields) -> TablesResult:
    return TablesResult(tables=_required_str_list(fields, "tables"))


def decode_schema_response(fields: Fields) -> SchemaResult:
    return SchemaResult(schema=_required_str_list(fields, "schema"))


def decode_dump_response(fields: Fields) -> DumpResult:
    return DumpResult(sql=_required_str(fields, "sql"))
