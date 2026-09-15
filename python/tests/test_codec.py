"""Unit tests for san_db_ox._codec. No I/O, no binary required."""

from __future__ import annotations

import base64
import json
import math

import pytest

from san_db_ox import _codec

# --- encode_value / encode_params -------------------------------------


def test_encode_value_none() -> None:
    assert _codec.encode_value(None) is None


def test_encode_value_bool_rejected() -> None:
    with pytest.raises(TypeError, match="bool"):
        _codec.encode_value(True)


@pytest.mark.parametrize("value", [_codec.INT64_MAX, _codec.INT64_MIN, 0, 42])
def test_encode_value_int_in_range(value: int) -> None:
    assert _codec.encode_value(value) == value


@pytest.mark.parametrize("value", [_codec.INT64_MAX + 1, _codec.INT64_MIN - 1, 2**100])
def test_encode_value_int_out_of_range(value: int) -> None:
    with pytest.raises(ValueError, match="int64 range"):
        _codec.encode_value(value)


def test_encode_value_float_finite_renders_with_decimal_point() -> None:
    encoded = _codec.encode_value(88.0)
    text = json.dumps(encoded)
    # san-db-ox binds INTEGER vs REAL by whether the JSON token has a
    # decimal point/exponent -- verify the rendered token actually has one.
    assert "." in text or "e" in text or "E" in text


@pytest.mark.parametrize("value", [math.nan, math.inf, -math.inf])
def test_encode_value_float_non_finite_rejected(value: float) -> None:
    with pytest.raises(ValueError, match="finite"):
        _codec.encode_value(value)


def test_encode_value_bytes_is_base64_singleton_array() -> None:
    assert _codec.encode_value(b"hi") == [base64.b64encode(b"hi").decode("ascii")]


def test_encode_value_empty_bytes() -> None:
    assert _codec.encode_value(b"") == [""]


def test_encode_value_bytearray_and_memoryview() -> None:
    expected = [base64.b64encode(b"hi").decode("ascii")]
    assert _codec.encode_value(bytearray(b"hi")) == expected
    assert _codec.encode_value(memoryview(b"hi")) == expected


def test_encode_value_str() -> None:
    assert _codec.encode_value("hello") == "hello"


def test_encode_value_unsupported_type() -> None:
    with pytest.raises(TypeError, match="unsupported param type"):
        _codec.encode_value(object())  # type: ignore[arg-type]


def test_encode_params_empty_is_none() -> None:
    assert _codec.encode_params(()) is None
    assert _codec.encode_params([]) is None


def test_encode_params_rejects_bare_str_or_bytes() -> None:
    with pytest.raises(TypeError, match="sequence"):
        # A bare str/bytes is structurally a Sequence[ParamValue] too (str
        # is Sequence[str]), so mypy accepts this call -- the TypeError is
        # a runtime guard against a str/bytes passed in place of params.
        _codec.encode_params("abc")
    with pytest.raises(TypeError, match="sequence"):
        _codec.encode_params(b"abc")


def test_encode_params_wraps_error_with_index() -> None:
    with pytest.raises(TypeError, match=r"params\[1\]"):
        _codec.encode_params([1, object()])  # type: ignore[list-item]


def test_encode_params_roundtrip_shapes() -> None:
    encoded = _codec.encode_params([1, 88.0, "x", b"hi", None])
    assert encoded == [1, 88.0, "x", [base64.b64encode(b"hi").decode("ascii")], None]


# --- encode_request_line -------------------------------------------------


def test_encode_request_line_ends_with_single_newline() -> None:
    line = _codec.encode_request_line({"op": "close"})
    assert line == b'{"op":"close"}\n'


def test_encode_request_line_no_unset_fields_leak_in() -> None:
    # Callers are responsible for omitting unset fields before calling this;
    # verify the encoder itself doesn't add anything of its own.
    line = _codec.encode_request_line({"op": "query", "sql": "SELECT 1"})
    assert json.loads(line) == {"op": "query", "sql": "SELECT 1"}


# --- decode_hello ----------------------------------------------------------


def test_decode_hello_ok() -> None:
    hello = _codec.decode_hello(
        b'{"protocol":1,"version":"v0.1.1","product":"SanDBox"}'
    )
    assert hello == _codec.Hello(protocol=1, version="v0.1.1", product="SanDBox")


def test_decode_hello_does_not_validate_protocol_number() -> None:
    # Validation is the caller's job (session layer), not codec's.
    hello = _codec.decode_hello(b'{"protocol":99,"version":"x","product":"y"}')
    assert hello.protocol == 99


def test_decode_hello_missing_field() -> None:
    with pytest.raises(_codec.ProtocolError, match="missing field"):
        _codec.decode_hello(b'{"protocol":1,"version":"x"}')


def test_decode_hello_invalid_json() -> None:
    with pytest.raises(_codec.ProtocolError):
        _codec.decode_hello(b"not json")


# --- decode_response_line ---------------------------------------------


def test_decode_response_line_success() -> None:
    ok, fields, err = _codec.decode_response_line(b'{"ok":true,"rows_affected":1}')
    assert ok is True
    assert fields == {"ok": True, "rows_affected": 1}
    assert err is None


def test_decode_response_line_error() -> None:
    ok, _fields, err = _codec.decode_response_line(
        b'{"ok":false,"error":{"code":"sqlite_error","message":"no such table"}}'
    )
    assert ok is False
    assert err is not None
    assert err.code == "sqlite_error"
    assert err.message == "no such table"
    assert isinstance(err, _codec.ResponseError)


def test_decode_response_line_ok_false_no_error_field() -> None:
    ok, _fields, err = _codec.decode_response_line(b'{"ok":false}')
    assert ok is False
    assert err is None


def test_decode_response_line_invalid_json() -> None:
    with pytest.raises(_codec.ProtocolError):
        _codec.decode_response_line(b"{not json")


def test_decode_response_line_rejects_nonstandard_constants() -> None:
    with pytest.raises(_codec.ProtocolError, match="non-finite constant"):
        _codec.decode_response_line(b'{"ok":true,"rows":[[NaN]]}')


# --- decode_value / decode_rows -----------------------------------------


def test_decode_value_null() -> None:
    assert _codec.decode_value(None) is None


def test_decode_value_int_large() -> None:
    big = 9223372036854775807
    assert _codec.decode_value(big) == big


def test_decode_value_float() -> None:
    assert _codec.decode_value(88.0) == 88.0


def test_decode_value_inf() -> None:
    assert _codec.decode_value(math.inf) == math.inf
    assert _codec.decode_value(-math.inf) == -math.inf


def test_decode_value_str() -> None:
    assert _codec.decode_value("hi") == "hi"


def test_decode_value_blob_roundtrip() -> None:
    encoded = base64.b64encode(b"hi").decode("ascii")
    assert _codec.decode_value([encoded]) == b"hi"


def test_decode_value_blob_wrong_length_rejected() -> None:
    with pytest.raises(_codec.ProtocolError, match="exactly 1"):
        _codec.decode_value([])
    with pytest.raises(_codec.ProtocolError, match="exactly 1"):
        _codec.decode_value(["a", "b"])


def test_decode_value_bool_rejected() -> None:
    with pytest.raises(_codec.ProtocolError):
        _codec.decode_value(True)


def test_decode_value_unexpected_type() -> None:
    with pytest.raises(_codec.ProtocolError):
        _codec.decode_value({"nope": 1})


def test_decode_rows_shape() -> None:
    rows = _codec.decode_rows([[1, "x", None]])
    assert rows == [[1, "x", None]]


def test_decode_rows_not_an_array() -> None:
    with pytest.raises(_codec.ProtocolError):
        _codec.decode_rows("nope")


def test_decode_rows_row_not_an_array() -> None:
    with pytest.raises(_codec.ProtocolError):
        _codec.decode_rows(["nope"])


# --- op response decoders -------------------------------------------------


def test_decode_query_response() -> None:
    resp = _codec.decode_query_response({"columns": ["x"], "rows": [[1]]})
    assert resp == _codec.QueryResult(columns=["x"], rows=[[1]])


def test_decode_exec_response_large_last_insert_id() -> None:
    big = 9223372036854775807
    resp = _codec.decode_exec_response({"rows_affected": 1, "last_insert_id": big})
    assert resp.last_insert_id == big


def test_decode_inspect_response_nulls() -> None:
    resp = _codec.decode_inspect_response(
        {
            "has_data": False,
            "version": None,
            "data_length": None,
            "source": "san-db-ox",
            "read_only": False,
        }
    )
    assert resp.version is None
    assert resp.data_length is None


def test_decode_inspect_response_with_data() -> None:
    resp = _codec.decode_inspect_response(
        {
            "has_data": True,
            "version": 1,
            "data_length": 4096,
            "source": "san-db-ox",
            "read_only": True,
        }
    )
    assert resp.has_data is True
    assert resp.version == 1
    assert resp.data_length == 4096
    assert resp.read_only is True


def test_decode_response_missing_field() -> None:
    with pytest.raises(_codec.ProtocolError, match="missing field"):
        _codec.decode_exec_response({"rows_affected": 1})


def test_decode_tables_schema_dump() -> None:
    assert _codec.decode_tables_response({"tables": ["a", "b"]}).tables == ["a", "b"]
    assert _codec.decode_schema_response({"schema": ["CREATE TABLE a(x)"]}).schema == [
        "CREATE TABLE a(x)"
    ]
    assert _codec.decode_dump_response({"sql": "PRAGMA"}).sql == "PRAGMA"


def test_decode_snapshot_response() -> None:
    assert _codec.decode_snapshot_response({"path": "/tmp/x.db"}).path == "/tmp/x.db"
