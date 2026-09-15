// Partial-match comparator for the conformance suite (mirrors
// python/tests/_conformance_support.py's match_json / go/sandbox/match_test.go).
// Not named *.test.ts so `node --test` does not collect it as a test file.

import { parseJsonRaw } from "../src/codec.js";

/** Parses a conformance case's JSON text with every number token preserved
 *  verbatim as a JSON.rawJSON box (see codec.ts's parseJsonRaw), so
 *  `JSON.stringify` of the result reproduces the source text byte-for-byte
 *  and numbers compare by literal token rather than parsed value (`88`
 *  never matches `88.0`). */
export function parseLiteral(text: string): unknown {
  return parseJsonRaw(text);
}

/** Re-serializes a value parsed by parseLiteral. JSON.stringify has native
 *  support for JSON.rawJSON boxes, so this needs no custom writer the way
 *  Python's dumps_literal or Go's json.RawMessage plumbing did. */
export function stringifyLiteral(value: unknown): string {
  return JSON.stringify(value);
}

function describePath(path: string): string {
  return path;
}

/** Partial match: `expect`'s objects only require their own keys to be
 *  present and equal in `actual` (extra actual keys are fine, so new
 *  response fields don't break old cases); arrays require exact length and
 *  order; numbers (JSON.rawJSON boxes from parseLiteral) compare by literal
 *  token; everything else compares by strict equality. Returns a
 *  `$.a[0].b`-style path to the first mismatch, or null if everything
 *  matched. */
export function matchJson(expect: unknown, actual: unknown, path = "$"): string | null {
  if (JSON.isRawJSON(expect)) {
    if (!JSON.isRawJSON(actual) || actual.rawJSON !== expect.rawJSON) {
      return describePath(path);
    }
    return null;
  }
  if (JSON.isRawJSON(actual)) {
    return describePath(path);
  }

  if (Array.isArray(expect)) {
    if (!Array.isArray(actual) || actual.length !== expect.length) {
      return describePath(path);
    }
    for (let i = 0; i < expect.length; i++) {
      const mismatch = matchJson(expect[i], actual[i], `${path}[${i}]`);
      if (mismatch) return mismatch;
    }
    return null;
  }
  if (Array.isArray(actual)) {
    return describePath(path);
  }

  if (isPlainObject(expect)) {
    if (!isPlainObject(actual)) return describePath(path);
    for (const key of Object.keys(expect)) {
      const mismatch = matchJson(expect[key], actual[key], `${path}.${key}`);
      if (mismatch) return mismatch;
    }
    return null;
  }
  if (isPlainObject(actual)) {
    return describePath(path);
  }

  return Object.is(expect, actual) || expect === actual ? null : describePath(path);
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null;
}
