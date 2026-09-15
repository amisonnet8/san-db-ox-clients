// Socket-level tests: transport-level TCP/UNIX echo (no binary needed),
// then SocketClient exercised over an in-process bridge to the real
// binary, then an opt-in test against a real `socat` process (mirrors
// python/tests/test_socket.py). POSIX-only (UNIX sockets, socat).

import assert from "node:assert/strict";
import { type ChildProcess, spawn } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import * as net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { TestContext } from "node:test";
import { describe, test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
import { connectTcp, connectUnix } from "../src/client.js";
import { PROTOCOL } from "../src/codec.js";
import { SocketTransport } from "../src/transport.js";
import { CALL_TIMEOUT_MS, hasSocat, isolatedCwd, sanDbOxBin } from "./helpers.js";

const posixOnly = { skip: process.platform === "win32" ? "POSIX-only" : false };
const enc = (s: string) => new TextEncoder().encode(s);
const dec = (b: Uint8Array) => new TextDecoder().decode(b);
const CLOSE_TIMEOUT_MS = 5_000;

function requireBinary(t: TestContext): string | null {
  const bin = sanDbOxBin();
  if (!bin) {
    t.skip("no san-db-ox binary: run `make fetch` or set SAN_DB_OX_BIN");
    return null;
  }
  return bin;
}

function tcpEchoServer(): Promise<{ server: net.Server; port: number }> {
  return new Promise((resolve, reject) => {
    const server = net.createServer((conn) => {
      conn.pipe(conn);
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      if (addr === null || typeof addr === "string") {
        reject(new Error("server has no port"));
        return;
      }
      resolve({ server, port: addr.port });
    });
  });
}

function unixEchoServer(path: string): Promise<net.Server> {
  return new Promise((resolve, reject) => {
    const server = net.createServer((conn) => {
      conn.pipe(conn);
    });
    server.once("error", reject);
    server.listen(path, () => resolve(server));
  });
}

// A bare `spawn`, not DirectTransport: DirectTransport's own LineReader
// already commits child.stdout to line framing, which would fight the raw
// byte forwarding a socket bridge needs -- exactly like an external socat
// process, which knows nothing about the protocol either.
function bridgeOnce(
  conn: net.Socket,
  binary: string,
  args: readonly string[],
): ChildProcess {
  const child = spawn(binary, args, {
    stdio: ["pipe", "pipe", "ignore"],
    cwd: isolatedCwd(),
  });
  conn.on("error", () => {});
  child.stdin?.on("error", () => {});
  child.stdout?.on("error", () => {});
  conn.pipe(child.stdin as NodeJS.WritableStream);
  (child.stdout as NodeJS.ReadableStream).pipe(conn);
  child.once("exit", () => conn.destroy());
  return child;
}

function tcpBridge(
  binary: string,
  args: readonly string[],
): Promise<{ server: net.Server; port: number }> {
  return new Promise((resolve, reject) => {
    const server = net.createServer((conn) => bridgeOnce(conn, binary, args));
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      if (addr === null || typeof addr === "string") {
        reject(new Error("server has no port"));
        return;
      }
      resolve({ server, port: addr.port });
    });
  });
}

function unixBridge(
  path: string,
  binary: string,
  args: readonly string[],
): Promise<net.Server> {
  return new Promise((resolve, reject) => {
    const server = net.createServer((conn) => bridgeOnce(conn, binary, args));
    server.once("error", reject);
    server.listen(path, () => resolve(server));
  });
}

describe("SocketTransport (no binary needed)", () => {
  test("TCP echo round-trip", { ...posixOnly, timeout: CALL_TIMEOUT_MS }, async () => {
    const { server, port } = await tcpEchoServer();
    try {
      const t = await SocketTransport.connectTcp("127.0.0.1", port, CALL_TIMEOUT_MS);
      try {
        await t.writeLine(enc("hello\n"));
        const line = await t.readLine(CALL_TIMEOUT_MS);
        assert.equal(dec(line), "hello");
      } finally {
        await t.close(CLOSE_TIMEOUT_MS);
      }
    } finally {
      server.close();
    }
  });

  test("UNIX echo round-trip", { ...posixOnly, timeout: CALL_TIMEOUT_MS }, async () => {
    const dir = mkdtempSync(join(tmpdir(), "san-db-ox-ts-sock-"));
    const path = join(dir, "echo.sock");
    const server = await unixEchoServer(path);
    try {
      const t = await SocketTransport.connectUnix(path, CALL_TIMEOUT_MS);
      try {
        await t.writeLine(enc("hello\n"));
        const line = await t.readLine(CALL_TIMEOUT_MS);
        assert.equal(dec(line), "hello");
      } finally {
        await t.close(CLOSE_TIMEOUT_MS);
      }
    } finally {
      server.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  test("wrapping an already-connected socket needs no TLS-specific constructor", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const { server, port } = await tcpEchoServer();
    try {
      const socket = net.createConnection({ host: "127.0.0.1", port });
      await new Promise<void>((resolve, reject) => {
        socket.once("connect", () => resolve());
        socket.once("error", reject);
      });
      const t = new SocketTransport(socket);
      try {
        await t.writeLine(enc("wrapped\n"));
        const line = await t.readLine(CALL_TIMEOUT_MS);
        assert.equal(dec(line), "wrapped");
      } finally {
        await t.close(CLOSE_TIMEOUT_MS);
      }
    } finally {
      server.close();
    }
  });

  test("close unblocks a pending read", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    const { server, port } = await tcpEchoServer();
    try {
      const t = await SocketTransport.connectTcp("127.0.0.1", port, CALL_TIMEOUT_MS);
      const pending = t.readLine(null);
      // Attach a throwaway handler immediately: without it, the window
      // between creating `pending` and the assert.rejects() below (which
      // is where the real check lives) is long enough for Node to flag
      // the rejection as briefly "unhandled" once close() below causes
      // the echo server to FIN back at us.
      pending.catch(() => {});
      await sleep(50); // let the read actually start blocking
      await t.close(CLOSE_TIMEOUT_MS);
      await assert.rejects(() => pending);
    } finally {
      server.close();
    }
  });

  test("connectTcp bounds the connection attempt by its own timeout", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async () => {
    // 192.0.2.0/24 is reserved for documentation (RFC 5737) and routes
    // nowhere real, so this either times out or fails fast with a
    // network error -- either way it must reject well within
    // CALL_TIMEOUT_MS rather than hang.
    await assert.rejects(() => SocketTransport.connectTcp("192.0.2.1", 1, 300));
  });
});

describe("SocketClient over an in-process bridge", () => {
  test("TCP bridge: hello + query, close is idempotent", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const { server, port } = await tcpBridge(bin, ["--serve-stdio"]);
    try {
      const sc = await connectTcp("127.0.0.1", port, { timeoutMs: CALL_TIMEOUT_MS });
      assert.equal(sc.hello.protocol, PROTOCOL);
      const r = await sc.query("SELECT 1");
      assert.equal(r.rows[0]?.[0], 1n);
      await sc.close();
      await sc.close();
    } finally {
      server.close();
    }
  });

  test("UNIX bridge: hello + query", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    const dir = mkdtempSync(join(tmpdir(), "san-db-ox-ts-sock-"));
    const path = join(dir, "bridge.sock");
    const server = await unixBridge(path, bin, ["--serve-stdio"]);
    try {
      const sc = await connectUnix(path, { timeoutMs: CALL_TIMEOUT_MS });
      try {
        assert.equal(sc.hello.protocol, PROTOCOL);
        const r = await sc.query("SELECT 1");
        assert.equal(r.rows[0]?.[0], 1n);
      } finally {
        await sc.close();
      }
    } finally {
      server.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("SocketClient via real socat (opt-in)", () => {
  test("connects through an actual socat UNIX-LISTEN process", {
    ...posixOnly,
    timeout: CALL_TIMEOUT_MS,
  }, async (t) => {
    const bin = requireBinary(t);
    if (!bin) return;
    if (!hasSocat()) {
      t.skip("socat is not installed");
      return;
    }
    const dir = mkdtempSync(join(tmpdir(), "san-db-ox-ts-socat-"));
    const sockPath = join(dir, "s.sock");
    const socat = spawn("socat", [
      `UNIX-LISTEN:${sockPath},fork`,
      `EXEC:${bin} --serve-stdio`,
    ]);
    try {
      const deadline = Date.now() + 5_000;
      while (!existsSync(sockPath)) {
        if (Date.now() > deadline) {
          throw new Error("socat did not create the socket in time");
        }
        await sleep(50);
      }
      const sc = await connectUnix(sockPath, { timeoutMs: CALL_TIMEOUT_MS });
      try {
        const r = await sc.query("SELECT 1");
        assert.equal(r.rows[0]?.[0], 1n);
      } finally {
        await sc.close();
      }
    } finally {
      socat.kill("SIGTERM");
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
