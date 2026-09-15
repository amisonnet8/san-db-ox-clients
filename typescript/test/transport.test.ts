// Transport-level tests using `cat`/`sh` as stand-ins for san-db-ox -- no
// binary needed (mirrors python/tests/test_transport.py). POSIX-only,
// skipped on win32.

import assert from "node:assert/strict";
import { Writable } from "node:stream";
import { describe, test } from "node:test";
import { ProtocolError, SanDBoxTimeoutError } from "../src/codec.js";
import { DirectTransport } from "../src/transport.js";
import { CALL_TIMEOUT_MS } from "./helpers.js";

const posixOnly = { skip: process.platform === "win32" ? "POSIX-only" : false };
const enc = (s: string) => new TextEncoder().encode(s);
const dec = (b: Uint8Array) => new TextDecoder().decode(b);

const CLOSE_TIMEOUT_MS = 5_000;

describe("DirectTransport", () => {
  test("echoes a line round-trip via cat", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const t = DirectTransport.start("cat", []);
    try {
      await t.writeLine(enc("hello\n"));
      const line = await t.readLine(CALL_TIMEOUT_MS);
      assert.equal(dec(line), "hello");
    } finally {
      await t.close(CLOSE_TIMEOUT_MS);
    }
  });

  test("stderr is drained even when discarded (a full pipe would deadlock the child)", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    // No `stderr` option -> stdio[2] is "ignore" (the OS null device):
    // there is no pipe for 20k lines of stderr to fill in the first
    // place, so the child never blocks writing to it.
    const script =
      'i=0; while [ $i -lt 20000 ]; do echo "warning $i" >&2; i=$((i+1)); done; cat';
    const t = DirectTransport.start("sh", ["-c", script]);
    try {
      await t.writeLine(enc("ping\n"));
      const line = await t.readLine(CALL_TIMEOUT_MS);
      assert.equal(dec(line), "ping");
    } finally {
      await t.close(CLOSE_TIMEOUT_MS);
    }
  });

  test("stderr is drained to a provided sink", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const chunks: Buffer[] = [];
    const sink = new Writable({
      write(chunk: Buffer, _enc, cb) {
        chunks.push(chunk);
        cb();
      },
    });
    const t = DirectTransport.start("sh", ["-c", "echo one >&2; echo two >&2; cat"], {
      stderr: sink,
    });
    try {
      await t.writeLine(enc("x\n"));
      await t.readLine(CALL_TIMEOUT_MS);
    } finally {
      await t.close(CLOSE_TIMEOUT_MS);
    }
    assert.equal(Buffer.concat(chunks).toString("utf-8"), "one\ntwo\n");
  });

  test("close via stdin reaps promptly", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const t = DirectTransport.start("cat", []);
    const start = Date.now();
    await t.close(CLOSE_TIMEOUT_MS);
    const elapsed = Date.now() - start;
    assert.ok(elapsed < 1_000, `close took ${elapsed}ms, expected < 1000ms`);
    assert.equal(t.exitCode, 0);
  });

  test("close escalates to SIGTERM when the child ignores stdin", {
    ...posixOnly,
    timeout: 20_000,
  }, async () => {
    const t = DirectTransport.start("sh", ["-c", "while true; do sleep 0.05; done"]);
    const start = Date.now();
    await t.close(200);
    const elapsed = Date.now() - start;
    assert.ok(elapsed >= 200, `close returned after ${elapsed}ms, expected >= 200ms`);
    assert.ok(elapsed < 2_000, `close took ${elapsed}ms, expected < 2000ms`);
    assert.notEqual(t.exitCode, null);
    assert.notEqual(t.exitCode, 0);
  });

  test("close is idempotent", { ...posixOnly, timeout: CALL_TIMEOUT_MS }, async () => {
    const t = DirectTransport.start("cat", []);
    await t.close(CLOSE_TIMEOUT_MS);
    await t.close(CLOSE_TIMEOUT_MS);
  });

  test("readLine times out rather than hanging", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const t = DirectTransport.start("cat", []);
    try {
      await assert.rejects(() => t.readLine(100), SanDBoxTimeoutError);
    } finally {
      await t.close(CLOSE_TIMEOUT_MS);
    }
  });

  test("a nonexistent command rejects rather than throwing synchronously", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const t = DirectTransport.start("no-such-command-xyz", []);
    await assert.rejects(() => t.readLine(CALL_TIMEOUT_MS));
    await t.close(CLOSE_TIMEOUT_MS);
  });

  test("a line exceeding MAX_LINE_BYTES without a newline is a ProtocolError", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    // `yes`/`tr` are unbounded generators on their own -- piping through
    // `head -c` caps the child's total output a little over
    // MAX_LINE_BYTES, so the whole pipeline exits on its own (via SIGPIPE
    // once `head` stops reading) in well under a second regardless of
    // how the driver behaves, instead of depending on this test (or a
    // driver bug) to be what stops it.
    const bytes = 2 * 1024 * 1024 + 4096;
    const t = DirectTransport.start("sh", [
      "-c",
      `yes | tr -d '\\n' | head -c ${bytes}`,
    ]);
    try {
      await assert.rejects(() => t.readLine(CALL_TIMEOUT_MS), ProtocolError);
    } finally {
      await t.close(CLOSE_TIMEOUT_MS);
    }
  });
});
