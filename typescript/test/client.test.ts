// Integration tests against the real san-db-ox binary (mirrors
// python/tests/test_client.py / go/sandbox/sandbox_test.go). Every test
// skips (does not fail) when no binary is found.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { Writable } from "node:stream";
import type { TestContext } from "node:test";
import { describe, test } from "node:test";
import type { Client, SocketClient } from "../src/index.js";
import {
  CODE_BAD_REQUEST,
  CODE_READ_ONLY,
  CODE_SQLITE_ERROR,
  connect,
  PROTOCOL,
  ResponseError,
  SanDBoxTimeoutError,
  VERSION,
} from "../src/index.js";
import { CALL_TIMEOUT_MS, copyOfBinary, repoRoot, sanDbOxBin } from "./helpers.js";

function requireBinary(t: TestContext): string | null {
  const bin = sanDbOxBin();
  if (!bin) {
    t.skip("no san-db-ox binary: run `make fetch` or set SAN_DB_OX_BIN");
    return null;
  }
  return bin;
}

async function openClient(bin: string, args: readonly string[] = []): Promise<Client> {
  return connect(bin, ["--serve-stdio", ...args], { timeoutMs: CALL_TIMEOUT_MS });
}

describe("connect", () => {
  test("hello reports protocol 1 and product SanDBox", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      assert.equal(c.hello.protocol, PROTOCOL);
      assert.equal(c.hello.product, "SanDBox");
      assert.equal(typeof c.hello.protocol, "number");
    } finally {
      await c.close();
    }
  });

  test("a nonexistent command rejects rather than hanging", {
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    await assert.rejects(() =>
      connect("no-such-san-db-ox-binary-xyz", ["--serve-stdio"]),
    );
  });

  test("VERSION matches package.json", () => {
    const pkg = JSON.parse(
      readFileSync(join(repoRoot(), "typescript", "package.json"), "utf-8"),
    ) as { version: string };
    assert.equal(VERSION, pkg.version);
  });
});

describe("values", () => {
  test("BLOB round-trips through params, including empty", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      await c.exec("CREATE TABLE t(b BLOB)");
      await c.exec("INSERT INTO t VALUES (?)", [new Uint8Array([1, 2, 3])]);
      await c.exec("INSERT INTO t VALUES (?)", [new Uint8Array()]);
      const r = await c.query("SELECT b FROM t ORDER BY rowid");
      assert.deepEqual(r.rows[0]?.[0], Buffer.from([1, 2, 3]));
      assert.deepEqual(r.rows[1]?.[0], Buffer.alloc(0));
    } finally {
      await c.close();
    }
  });

  test("a large integer round-trips as bigint", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      await c.exec("CREATE TABLE t(x INTEGER)");
      await c.exec("INSERT INTO t VALUES (?)", [9223372036854775807n]);
      const r = await c.query("SELECT x FROM t");
      assert.equal(r.rows[0]?.[0], 9223372036854775807n);
    } finally {
      await c.close();
    }
  });

  test("REAL keeps a decimal point, and +-Inf/NaN round-trip", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      const r = await c.query("SELECT 88.0");
      assert.equal(r.rows[0]?.[0], 88);
      assert.equal(typeof r.rows[0]?.[0], "number");

      // SQLite has no IEEE 754 division-by-zero semantics (1.0/0.0 is SQL
      // NULL, not Infinity) -- +-Inf only appears by overflowing a REAL
      // multiplication, exactly as conformance/cases/
      // real-and-integer-representation.json does.
      const inf = await c.query("SELECT 1e308 * 10");
      assert.equal(inf.rows[0]?.[0], Number.POSITIVE_INFINITY);
      const ninf = await c.query("SELECT -1e308 * 10");
      assert.equal(ninf.rows[0]?.[0], Number.NEGATIVE_INFINITY);
      // Inf - Inf is NaN, which the server sends as the JSON literal null
      // -- indistinguishable from SQL NULL once decoded (protocol.md).
      const nan = await c.query("SELECT (1e308 * 10) - (1e308 * 10)");
      assert.equal(nan.rows[0]?.[0], null);
    } finally {
      await c.close();
    }
  });
});

describe("errors", () => {
  test("stable error codes leave the connection usable", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      await assert.rejects(
        () => c.exec("SELECT * FROM no_such_table"),
        (e: unknown) => e instanceof ResponseError && e.code === CODE_SQLITE_ERROR,
      );
      await assert.rejects(
        () => c.query("SELECT 1", [true as unknown as never]),
        // A TS caller can't construct this; a plain-JS caller can, and it
        // must fail client-side rather than reach the wire.
        TypeError,
      );
      await assert.rejects(
        // A missing required field ("sql") is dropped entirely by
        // JSON.stringify (undefined-valued properties are omitted), so
        // this reaches the wire as {"op":"query"} and exercises the
        // server's own bad_request path.
        // @ts-expect-error sql is intentionally omitted to trigger bad_request
        () => c.query(undefined),
        (e: unknown) => e instanceof ResponseError && e.code === CODE_BAD_REQUEST,
      );
      // Connection must still work after all of the above.
      const r = await c.query("SELECT 1");
      assert.equal(r.rows[0]?.[0], 1n);
    } finally {
      await c.close();
    }
  });
});

describe("--read-only", () => {
  test("op-level writes are rejected with read_only", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin, ["--read-only"]);
    try {
      await assert.rejects(
        () => c.snapshot(),
        (e: unknown) => e instanceof ResponseError && e.code === CODE_READ_ONLY,
      );
    } finally {
      await c.close();
    }
  });

  test("SQL-level writes are rejected by SQLite itself, as sqlite_error", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin, ["--read-only"]);
    try {
      await assert.rejects(
        () => c.exec("CREATE TABLE t(x)"),
        (e: unknown) => e instanceof ResponseError && e.code === CODE_SQLITE_ERROR,
      );
      // Reads still work; the process is still alive.
      const r = await c.query("SELECT 1");
      assert.equal(r.rows[0]?.[0], 1n);
    } finally {
      await c.close();
    }
  });
});

describe("snapshot / load / inspect / tables / schema / dump", () => {
  test("snapshot then load round-trips data across connections", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c1 = await openClient(bin);
    let path: string;
    try {
      await c1.exec("CREATE TABLE t(x)");
      await c1.exec("INSERT INTO t VALUES (1)");
      path = (await c1.snapshot()).path;
    } finally {
      await c1.close();
    }
    const c2 = await openClient(bin);
    try {
      await c2.load(path);
      const r = await c2.query("SELECT x FROM t");
      assert.equal(r.rows[0]?.[0], 1n);
    } finally {
      await c2.close();
    }
  });

  test("inspect describes only this process's own data", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      const info = await c.inspect();
      assert.equal(typeof info.hasData, "boolean");
      assert.equal(typeof info.readOnly, "boolean");
      assert.equal(typeof info.source, "string");
    } finally {
      await c.close();
    }
  });

  test("tables / schema / dump", { timeout: CALL_TIMEOUT_MS }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      await c.exec("CREATE TABLE t(x)");
      const tables = await c.tables();
      assert.ok(tables.tables.includes("t"));
      const schema = await c.schema("t");
      assert.equal(schema.schema.length, 1);
      const dump = await c.dump();
      assert.ok(dump.sql.includes("CREATE TABLE t"));
    } finally {
      await c.close();
    }
  });
});

describe("close", () => {
  test("reaps the child and is idempotent", { timeout: CALL_TIMEOUT_MS }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    await c.close();
    assert.equal(c.exitCode, 0);
    await c.close();
    assert.equal(c.exitCode, 0);
  });
});

describe("overwrite", () => {
  test("reaps the child and persists data into a fresh connection", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const { path: copyPath, cleanup } = copyOfBinary(bin);
    try {
      const c = await openClient(copyPath);
      await c.exec("CREATE TABLE t(x)");
      await c.exec("INSERT INTO t VALUES (42)");
      await c.overwrite();
      assert.equal(c.exitCode, 0);

      const c2 = await openClient(copyPath);
      try {
        const r = await c2.query("SELECT x FROM t");
        assert.equal(r.rows[0]?.[0], 42n);
      } finally {
        await c2.close();
      }
    } finally {
      cleanup();
    }
  });
});

describe("timeouts", () => {
  test("a timed-out call tears the connection down in the background", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const c = await openClient(bin);
    try {
      await assert.rejects(
        () =>
          c.query(
            "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM cnt WHERE x < 100000000) SELECT count(*) FROM cnt",
            [],
            { timeoutMs: 200 },
          ),
        SanDBoxTimeoutError,
      );
    } finally {
      // The connection is unusable after a timeout, but close() must
      // still be safe and must wait for the background teardown, so the
      // child is reliably reaped (no zombie left for the test runner).
      await c.close();
    }
    assert.notEqual(c.exitCode, null);
  });
});

describe("stderr sink", () => {
  test("wiring a stderr sink doesn't break the connection", {
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const sink = new Writable({
      write(_chunk, _enc, cb) {
        cb();
      },
    });
    const c = await connect(bin, ["--serve-stdio"], {
      stderr: sink,
      timeoutMs: CALL_TIMEOUT_MS,
    });
    try {
      const r = await c.query("SELECT 1");
      assert.equal(r.rows[0]?.[0], 1n);
    } finally {
      await c.close();
    }
  });
});

describe("SocketClient has no direct-only APIs", () => {
  test("overwrite/exitCode are compile errors on SocketClient", () => {
    // A pure type-level check -- SocketClient's actual runtime behavior
    // (connecting over TCP/UNIX) is exercised in socket.test.ts. Here we
    // only need `make typescript-typecheck` to fail if either member is
    // ever accidentally added to SocketClient.
    const sc = {} as SocketClient;
    // @ts-expect-error SocketClient has no overwrite -- direct-only.
    void sc.overwrite;
    // @ts-expect-error SocketClient has no exitCode -- direct-only.
    void sc.exitCode;
  });
});
