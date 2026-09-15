import assert from "node:assert/strict";
import { describe, test } from "node:test";
import * as codec from "../src/codec.js";

function rawToken(v: unknown): string {
  assert.ok(JSON.isRawJSON(v), `expected a JSON.rawJSON box, got ${JSON.stringify(v)}`);
  return v.rawJSON;
}

describe("encodeValue", () => {
  test("null", () => {
    assert.equal(codec.encodeValue(null), null);
  });

  test("bool is rejected", () => {
    // @ts-expect-error boolean is deliberately not a ParamValue
    assert.throws(() => codec.encodeValue(true), TypeError);
  });

  test("bigint within int64 range encodes as an INTEGER token", () => {
    assert.equal(rawToken(codec.encodeValue(42n)), "42");
    assert.equal(
      rawToken(codec.encodeValue(codec.INT64_MAX)),
      codec.INT64_MAX.toString(),
    );
    assert.equal(
      rawToken(codec.encodeValue(codec.INT64_MIN)),
      codec.INT64_MIN.toString(),
    );
  });

  test("bigint outside int64 range is rejected", () => {
    assert.throws(() => codec.encodeValue(codec.INT64_MAX + 1n), RangeError);
    assert.throws(() => codec.encodeValue(codec.INT64_MIN - 1n), RangeError);
  });

  test("finite number always renders with a decimal point or exponent", () => {
    assert.equal(rawToken(codec.encodeValue(88)), "88.0");
    assert.equal(rawToken(codec.encodeValue(88.5)), "88.5");
    assert.equal(rawToken(codec.encodeValue(-0)), "-0.0");
  });

  test("NaN/Infinity number params are rejected", () => {
    assert.throws(() => codec.encodeValue(Number.NaN), RangeError);
    assert.throws(() => codec.encodeValue(Number.POSITIVE_INFINITY), RangeError);
    assert.throws(() => codec.encodeValue(Number.NEGATIVE_INFINITY), RangeError);
  });

  test("Uint8Array encodes as a base64 singleton array", () => {
    assert.deepEqual(codec.encodeValue(new Uint8Array([104, 105])), ["aGk="]);
  });

  test('empty Uint8Array encodes as [""]', () => {
    assert.deepEqual(codec.encodeValue(new Uint8Array()), [""]);
  });

  test("Buffer (a Uint8Array subclass) is accepted", () => {
    assert.deepEqual(codec.encodeValue(Buffer.from("hi")), ["aGk="]);
  });

  test("string passes through unchanged", () => {
    assert.equal(codec.encodeValue("hello"), "hello");
  });

  test("unsupported types are rejected", () => {
    // @ts-expect-error not a ParamValue
    assert.throws(() => codec.encodeValue({}), TypeError);
    // @ts-expect-error not a ParamValue
    assert.throws(() => codec.encodeValue(undefined), TypeError);
  });
});

describe("encodeValue REAL token shape", () => {
  test("1e21 keeps its exponent form", () => {
    assert.equal(rawToken(codec.encodeValue(1e21)), "1e+21");
  });

  test("5e-7 keeps its exponent form", () => {
    assert.equal(rawToken(codec.encodeValue(5e-7)), "5e-7");
  });
});

describe("encodeParams", () => {
  test("undefined or empty is omitted", () => {
    assert.equal(codec.encodeParams(undefined), undefined);
    assert.equal(codec.encodeParams([]), undefined);
  });

  test("errors are wrapped with the offending index", () => {
    assert.throws(
      // @ts-expect-error boolean is deliberately not a ParamValue
      () => codec.encodeParams([1n, true]),
      (e: unknown) =>
        e instanceof TypeError && (e as Error).message.startsWith("params[1]:"),
    );
  });

  test("encodes a mixed list in order", () => {
    const encoded = codec.encodeParams([42n, 88, "s", null]);
    assert.ok(encoded);
    assert.equal(rawToken(encoded[0]), "42");
    assert.equal(rawToken(encoded[1]), "88.0");
    assert.equal(encoded[2], "s");
    assert.equal(encoded[3], null);
  });
});

describe("encodeRequestLine", () => {
  test("ends with exactly one newline and leaks no unset fields", () => {
    const line = codec.encodeRequestLine({ op: "query", sql: "SELECT 1" });
    const text = new TextDecoder().decode(line);
    assert.equal(text, '{"op":"query","sql":"SELECT 1"}\n');
    assert.equal(text.indexOf("\n"), text.length - 1);
  });
});

describe("decodeHello", () => {
  test("decodes a well-formed hello line", () => {
    const hello = codec.decodeHello(
      new TextEncoder().encode('{"protocol":1,"version":"0.1.1","product":"SanDBox"}'),
    );
    assert.deepEqual(hello, { protocol: 1, version: "0.1.1", product: "SanDBox" });
    assert.equal(typeof hello.protocol, "number");
  });

  test("does not validate the protocol number itself", () => {
    const hello = codec.decodeHello(
      new TextEncoder().encode('{"protocol":99,"version":"x","product":"SanDBox"}'),
    );
    assert.equal(hello.protocol, 99);
  });

  test("missing field is a ProtocolError", () => {
    assert.throws(
      () => codec.decodeHello(new TextEncoder().encode('{"protocol":1,"version":"x"}')),
      codec.ProtocolError,
    );
  });

  test("invalid JSON is a ProtocolError", () => {
    assert.throws(
      () => codec.decodeHello(new TextEncoder().encode("not json")),
      codec.ProtocolError,
    );
  });
});

describe("decodeResponseLine", () => {
  test("ok:true carries every top-level field", () => {
    const { ok, fields, error } = codec.decodeResponseLine(
      new TextEncoder().encode('{"ok":true,"columns":["a"],"rows":[]}'),
    );
    assert.equal(ok, true);
    assert.equal(error, null);
    assert.deepEqual(fields.columns, ["a"]);
  });

  test("ok:false with an error object", () => {
    const { ok, error } = codec.decodeResponseLine(
      new TextEncoder().encode(
        '{"ok":false,"error":{"code":"bad_request","message":"nope"}}',
      ),
    );
    assert.equal(ok, false);
    assert.ok(error instanceof codec.ResponseError);
    assert.equal(error.code, "bad_request");
  });

  test("ok:false with no error field", () => {
    const { ok, error } = codec.decodeResponseLine(
      new TextEncoder().encode('{"ok":false}'),
    );
    assert.equal(ok, false);
    assert.equal(error, null);
  });

  test("invalid JSON raises ProtocolError", () => {
    assert.throws(
      () => codec.decodeResponseLine(new TextEncoder().encode("{")),
      codec.ProtocolError,
    );
  });

  test("a bareword NaN/Infinity constant is rejected by JSON.parse itself", () => {
    assert.throws(
      () => codec.decodeResponseLine(new TextEncoder().encode('{"ok":true,"x":NaN}')),
      codec.ProtocolError,
    );
  });

  test("non-object response line is a ProtocolError", () => {
    assert.throws(
      () => codec.decodeResponseLine(new TextEncoder().encode("[1,2]")),
      codec.ProtocolError,
    );
  });
});

describe("decodeValue", () => {
  test("null", () => {
    assert.equal(codec.decodeValue(null), null);
  });

  test("large integer survives as bigint", () => {
    const parsed = codec.parseJsonPreservingNumbers("9223372036854775807") as bigint;
    assert.equal(codec.decodeValue(parsed), 9223372036854775807n);
  });

  test("REAL survives as number", () => {
    const parsed = codec.parseJsonPreservingNumbers("88.0") as number;
    assert.equal(codec.decodeValue(parsed), 88);
  });

  test("+Inf/-Inf tokens decode to Infinity/-Infinity", () => {
    assert.equal(
      codec.decodeValue(codec.parseJsonPreservingNumbers("9e999")),
      Number.POSITIVE_INFINITY,
    );
    assert.equal(
      codec.decodeValue(codec.parseJsonPreservingNumbers("-9e999")),
      Number.NEGATIVE_INFINITY,
    );
  });

  test("string", () => {
    assert.equal(codec.decodeValue("hi"), "hi");
  });

  test("blob round-trips", () => {
    assert.deepEqual(codec.decodeValue(["aGk="]), Buffer.from("hi"));
  });

  test("empty blob", () => {
    assert.deepEqual(codec.decodeValue([""]), Buffer.alloc(0));
  });

  test("blob array of wrong length is rejected", () => {
    assert.throws(() => codec.decodeValue(["a", "b"]), codec.ProtocolError);
    assert.throws(() => codec.decodeValue([]), codec.ProtocolError);
  });

  test("blob with invalid base64 characters is rejected", () => {
    assert.throws(() => codec.decodeValue(["not base64!!"]), codec.ProtocolError);
  });

  test("bool is rejected", () => {
    assert.throws(() => codec.decodeValue(true), codec.ProtocolError);
  });

  test("unexpected type is rejected", () => {
    assert.throws(() => codec.decodeValue({}), codec.ProtocolError);
  });
});

describe("decodeRows", () => {
  test("rejects a non-array rows field", () => {
    assert.throws(() => codec.decodeRows("nope"), codec.ProtocolError);
  });

  test("rejects a non-array row, with its index in the message", () => {
    assert.throws(
      () => codec.decodeRows([["ok"], "bad"]),
      (e: unknown) => e instanceof codec.ProtocolError && e.message.includes("rows[1]"),
    );
  });
});

describe("per-op result decoders", () => {
  test("decodeQueryResponse", () => {
    const { fields } = codec.decodeResponseLine(
      new TextEncoder().encode('{"ok":true,"columns":["x"],"rows":[[1]]}'),
    );
    const r = codec.decodeQueryResponse(fields);
    assert.deepEqual(r.columns, ["x"]);
    assert.deepEqual(r.rows, [[1n]]);
  });

  test("decodeExecResponse returns bigint fields", () => {
    const { fields } = codec.decodeResponseLine(
      new TextEncoder().encode(
        '{"ok":true,"rows_affected":1,"last_insert_id":9223372036854775807}',
      ),
    );
    const r = codec.decodeExecResponse(fields);
    assert.equal(r.rowsAffected, 1n);
    assert.equal(r.lastInsertId, 9223372036854775807n);
  });

  test("decodeSnapshotResponse", () => {
    const { fields } = codec.decodeResponseLine(
      new TextEncoder().encode('{"ok":true,"path":"/tmp/x"}'),
    );
    assert.deepEqual(codec.decodeSnapshotResponse(fields), { path: "/tmp/x" });
  });

  test("decodeInspectResponse with data", () => {
    const { fields } = codec.decodeResponseLine(
      new TextEncoder().encode(
        '{"ok":true,"has_data":true,"version":1,"data_length":42,"source":"embedded","read_only":false}',
      ),
    );
    assert.deepEqual(codec.decodeInspectResponse(fields), {
      hasData: true,
      version: 1n,
      dataLength: 42n,
      source: "embedded",
      readOnly: false,
    });
  });

  test("decodeInspectResponse with no data (null version/dataLength)", () => {
    const { fields } = codec.decodeResponseLine(
      new TextEncoder().encode(
        '{"ok":true,"has_data":false,"version":null,"data_length":null,"source":"none","read_only":false}',
      ),
    );
    const r = codec.decodeInspectResponse(fields);
    assert.equal(r.version, null);
    assert.equal(r.dataLength, null);
  });

  test("decodeTablesResponse / decodeSchemaResponse / decodeDumpResponse", () => {
    assert.deepEqual(
      codec.decodeTablesResponse(
        codec.decodeResponseLine(new TextEncoder().encode('{"ok":true,"tables":["t"]}'))
          .fields,
      ),
      { tables: ["t"] },
    );
    assert.deepEqual(
      codec.decodeSchemaResponse(
        codec.decodeResponseLine(
          new TextEncoder().encode('{"ok":true,"schema":["CREATE TABLE t(x)"]}'),
        ).fields,
      ),
      { schema: ["CREATE TABLE t(x)"] },
    );
    assert.deepEqual(
      codec.decodeDumpResponse(
        codec.decodeResponseLine(new TextEncoder().encode('{"ok":true,"sql":"..."}'))
          .fields,
      ),
      { sql: "..." },
    );
  });

  test("missing required field raises ProtocolError", () => {
    const { fields } = codec.decodeResponseLine(
      new TextEncoder().encode('{"ok":true}'),
    );
    assert.throws(() => codec.decodeQueryResponse(fields), codec.ProtocolError);
  });
});
