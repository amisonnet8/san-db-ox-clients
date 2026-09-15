// Shared test scaffolding (mirrors python/tests/conftest.py /
// go/sandbox/testutil_test.go): binary discovery, timeouts, and a writable
// copy of the binary for tests (like overwrite) that mutate it.

import { execFileSync } from "node:child_process";
import { chmodSync, copyFileSync, existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

// Applied to every request/read in the test suite, so a flush/read bug
// fails one test instead of hanging CI (.claude/rules/testing.md).
export const CALL_TIMEOUT_MS = 10_000;

export function repoRoot(): string {
  // test/helpers.ts -> build-test/test/helpers.js at runtime, two levels
  // below typescript/, three below the repo root.
  return join(fileURLToPath(new URL("../../../", import.meta.url)));
}

/** SAN_DB_OX_BIN wins; otherwise <repo>/bin/san-db-ox(.exe). Returns null
 *  (never throws) when no binary is found -- callers must skip, not fail
 *  (.claude/rules/testing.md). */
export function sanDbOxBin(): string | null {
  const fromEnv = process.env.SAN_DB_OX_BIN;
  if (fromEnv) return fromEnv;
  const name = process.platform === "win32" ? "san-db-ox.exe" : "san-db-ox";
  const path = join(repoRoot(), "bin", name);
  return existsSync(path) ? path : null;
}

/** Copies the binary into a fresh temp dir (0o755) for tests that mutate it
 *  (overwrite). Returns { path, cleanup }. */
export function copyOfBinary(bin: string): { path: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "san-db-ox-ts-"));
  const dest = join(dir, process.platform === "win32" ? "san-db-ox.exe" : "san-db-ox");
  copyFileSync(bin, dest);
  chmodSync(dest, 0o755);
  return {
    path: dest,
    cleanup: () => rmSync(dir, { recursive: true, force: true }),
  };
}

/** A fresh, empty temp directory to use as a spawned san-db-ox process's
 *  cwd. Without this, a test that doesn't pass its own `filename` to an op
 *  like `snapshot` -- which writes into the child's cwd by default -- would
 *  otherwise write into the Node test runner's own cwd (which is
 *  typescript/ when run via `make typescript-test`) and pollute the repo
 *  working tree. Not cleaned up automatically; OS temp cleanup handles it. */
export function isolatedCwd(): string {
  return mkdtempSync(join(tmpdir(), "san-db-ox-ts-cwd-"));
}

export function hasSocat(): boolean {
  try {
    execFileSync("socat", ["-V"], { stdio: "ignore" });
    return true;
  } catch {
    return false;
  }
}
