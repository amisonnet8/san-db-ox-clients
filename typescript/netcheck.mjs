#!/usr/bin/env node
// Mechanically confirms src/codec.ts stays free of networking/process
// machinery (.claude/rules/architecture.md: "コーデック層はネットワークに
// 依存しないことを検証可能にする"), the same idea as go-netcheck (`go list
// -deps | grep`) and python/netcheck.py (an import allowlist plus a
// sys.modules diff after loading _codec.py in isolation).
//
// Plain .mjs, run directly by `node netcheck.mjs` with no build step of its
// own -- it inspects the already-built dist/codec.js (via
// `make typescript-netcheck: typescript-build`), which is what actually
// ships.

import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const CODEC_SRC = fileURLToPath(new URL("./src/codec.ts", import.meta.url));
const CODEC_DIST = fileURLToPath(new URL("./dist/codec.js", import.meta.url));

// Anything reachable through these substrings, transitively, means the
// codec layer pulled in networking, TLS, HTTP, or child-process machinery.
const FORBIDDEN_SUBSTRINGS = [
  "net",
  "tls",
  "http",
  "http2",
  "https",
  "dgram",
  "dns",
  "child_process",
  "cluster",
  "worker_threads",
  "inspector",
  "repl",
  "fs",
  "tcp_wrap",
  "pipe_wrap",
  "udp_wrap",
  "spawn_sync",
  "process_wrap",
  "cares_wrap",
];

function isForbidden(moduleLoadListEntry) {
  // Entries look like "NativeModule net" or "Internal Binding tcp_wrap".
  const name = moduleLoadListEntry.split(" ").at(-1) ?? "";
  return FORBIDDEN_SUBSTRINGS.some(
    (bad) =>
      name === bad || name.startsWith(`${bad}/`) || name.startsWith(`node:${bad}`),
  );
}

let failed = false;
function fail(message) {
  failed = true;
  console.error(`typescript-netcheck FAILED: ${message}`);
}

// -- (a) Static import allowlist --------------------------------------------
//
// The codec's intended import count is zero: base64 uses the Buffer
// global, encoding uses TextEncoder/TextDecoder globals, none of which are
// imports. Any import at all must be relative (a sibling module in this
// driver, e.g. a future split of codec.ts) -- a bare specifier ("node:*" or
// an npm package) is disqualifying on its own.
{
  const source = await readFile(CODEC_SRC, "utf-8");
  const info = ts.preProcessFile(
    source,
    /* readImportFiles */ true,
    /* detectJavaScriptImports */ true,
  );
  const bad = info.importedFiles
    .map((f) => f.fileName)
    .filter((spec) => !spec.startsWith("."));
  if (bad.length > 0) {
    fail(`src/codec.ts has non-relative import(s): ${JSON.stringify(bad)}`);
  } else {
    console.log(
      `static check: src/codec.ts imports ${info.importedFiles.length} module(s), all relative.`,
    );
  }
}

// -- (b)/(c) Runtime module-load-graph diff, with a positive control --------
//
// Runs in a fresh child `node -e` so the probe's own module graph doesn't
// contaminate the measurement, and so the positive control (importing
// node:net) doesn't leave net loaded for the real probe.
async function loadListDiff(specifier) {
  const { execFileSync } = await import("node:child_process");
  const script = `
    const before = new Set(process.moduleLoadList);
    await import(${JSON.stringify(specifier)});
    const diff = process.moduleLoadList.filter((m) => !before.has(m));
    process.stdout.write(JSON.stringify(diff));
  `;
  const out = execFileSync(process.execPath, ["--input-type=module", "-e", script], {
    encoding: "utf-8",
  });
  return JSON.parse(out);
}

{
  // Positive control first: process.moduleLoadList is undocumented, so
  // prove the detection mechanism itself still works before trusting a
  // clean result from codec.js. If Node ever stops reporting builtin loads
  // this way, this must fail loudly rather than let the real check pass by
  // accident.
  const controlDiff = await loadListDiff("node:net");
  if (!controlDiff.some(isForbidden)) {
    fail(
      "netcheck's detection mechanism is broken (process.moduleLoadList no " +
        "longer reports loaded builtins for node:net) -- fix netcheck.mjs " +
        "before trusting it",
    );
  } else {
    console.log("positive control: importing node:net is detected, as expected.");
  }

  const codecDiff = await loadListDiff(CODEC_DIST);
  const codecBad = codecDiff.filter(isForbidden);
  if (codecBad.length > 0) {
    fail(
      `dist/codec.js transitively loaded forbidden module(s): ${JSON.stringify(codecBad)}`,
    );
  } else {
    console.log(
      `runtime check: importing dist/codec.js loaded ${codecDiff.length} module(s), none forbidden.`,
    );
  }
}

if (failed) {
  process.exit(1);
}
console.log("typescript-netcheck OK");
