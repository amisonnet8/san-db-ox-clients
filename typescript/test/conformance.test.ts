// Runs conformance/cases/*.json against the real binary, driving
// DirectTransport directly rather than the typed Client (mirrors
// python/tests/test_conformance.py / go/sandbox/conformance_test.go) --
// some cases send deliberately malformed requests the typed API can't
// construct. See conformance/README.md for the case-file contract.

import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { DirectTransport } from "../src/transport.js";
import { CALL_TIMEOUT_MS, repoRoot, sanDbOxBin } from "./helpers.js";
import { matchJson, parseLiteral, stringifyLiteral } from "./match.js";

const CLOSE_TIMEOUT_MS = 5_000;

interface Step {
  readonly request: Record<string, unknown>;
  readonly expect?: Record<string, unknown>;
  readonly expect_raw?: readonly string[];
}

interface Case {
  readonly name: string;
  readonly description: string;
  readonly args?: readonly string[];
  readonly steps: readonly Step[];
  readonly known_failing?: string;
}

function casesDir(): string {
  return join(repoRoot(), "conformance", "cases");
}

function casePaths(): string[] {
  return readdirSync(casesDir())
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => join(casesDir(), f));
}

const paths = casePaths();

test("conformance/cases is not empty", () => {
  assert.ok(paths.length > 0, `no case files found under ${casesDir()}`);
});

for (const path of paths) {
  const caseName = path.slice(casesDir().length + 1).replace(/\.json$/, "");

  test(`conformance: ${caseName}`, { timeout: CALL_TIMEOUT_MS }, async (t) => {
    const bin = sanDbOxBin();
    if (!bin) {
      t.skip("no san-db-ox binary: run `make fetch` or set SAN_DB_OX_BIN");
      return;
    }

    const text = readFileSync(path, "utf-8");
    const parsed = parseLiteral(text) as Case;

    const transport = DirectTransport.start(bin, [
      "--serve-stdio",
      ...(parsed.args ?? []),
    ]);
    const mismatches: string[] = [];
    try {
      // Discard the hello line -- the conformance suite exercises the
      // request/response cycle, not connection setup.
      await transport.readLine(CALL_TIMEOUT_MS);

      for (let i = 0; i < parsed.steps.length; i++) {
        // biome-ignore lint/style/noNonNullAssertion: i < parsed.steps.length
        const step = parsed.steps[i]!;
        const reqLine = `${stringifyLiteral(step.request)}\n`;

        let raw: Buffer;
        try {
          await transport.writeLine(new TextEncoder().encode(reqLine));
          raw = await transport.readLine(CALL_TIMEOUT_MS);
        } catch (e) {
          // A transport failure is never a "known" failure -- known_failing
          // exists to tolerate a wrong *answer*, not a broken connection.
          assert.fail(`step ${i}: transport failure: ${(e as Error).message}`);
        }
        const rawText = new TextDecoder().decode(raw);

        if (step.expect !== undefined) {
          const actual = parseLiteral(rawText);
          const mismatch = matchJson(step.expect, actual);
          if (mismatch) {
            mismatches.push(`step ${i}: mismatch at ${mismatch} (got ${rawText})`);
          }
        }
        if (step.expect_raw !== undefined) {
          for (const substr of step.expect_raw) {
            if (!rawText.includes(substr)) {
              mismatches.push(
                `step ${i}: expected raw response to contain ${JSON.stringify(substr)}, got ${rawText}`,
              );
            }
          }
        }
      }
    } finally {
      await transport.close(CLOSE_TIMEOUT_MS);
    }

    if (parsed.known_failing) {
      if (mismatches.length === 0) {
        assert.fail(
          `known_failing is set (${parsed.known_failing}) but every step passed -- remove the marker`,
        );
      }
      // xfail: the case is allowed to fail, but the failure must still be
      // visible so a change in *how* it fails doesn't go unnoticed.
      console.log(
        `known_failing (${parsed.known_failing}):\n  ${mismatches.join("\n  ")}`,
      );
      return;
    }
    assert.deepEqual(mismatches, []);
  });
}
