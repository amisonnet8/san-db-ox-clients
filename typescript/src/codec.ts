// JSON Lines encoding/decoding for SanDBox's stdio protocol.
//
// This module knows nothing about I/O, process, or socket lifetimes -- it
// only converts between JS values and the wire representation
// (.claude/rules/protocol.md). Keeping it pure lets `make typescript-netcheck`
// confirm it never pulls in networking or subprocess machinery
// (.claude/rules/architecture.md). Every import here must be relative;
// nothing here may import "node:*" or a package.
//
// JSON.rawJSON/isRawJSON and the reviver's third argument, used below, are
// declared globally by json-raw.d.ts (an ambient .d.ts needs no import to
// take effect as long as it's in the program -- see tsconfig*.json).

export const PROTOCOL = 1;

// Sized for upstream's stated 1 MiB line limit, with headroom above it.
// Not part of the public API (index.ts does not re-export it) -- transport.ts
// imports it directly, the same way Python's _transport.py reaches into
// _codec.MAX_LINE_BYTES.
export const MAX_LINE_BYTES = 2 * 1024 * 1024;

export const INT64_MIN = -(2n ** 63n);
export const INT64_MAX = 2n ** 63n - 1n;

// The 5 stable error codes (protocol.md). Never branch on `message` --
// upstream's wording can change between releases without notice.
export const CODE_SQLITE_ERROR = "sqlite_error";
export const CODE_BAD_REQUEST = "bad_request";
export const CODE_IO_ERROR = "io_error";
export const CODE_UNSUPPORTED_OP = "unsupported_op";
export const CODE_READ_ONLY = "read_only";

/** The 5 stable codes, for callers who want an exhaustive switch.
 *  ResponseError.code itself is typed `string`, not this union: an unknown
 *  future code must not be a type error at the call site. */
export type ErrorCode =
  | typeof CODE_SQLITE_ERROR
  | typeof CODE_BAD_REQUEST
  | typeof CODE_IO_ERROR
  | typeof CODE_UNSUPPORTED_OP
  | typeof CODE_READ_ONLY;

// -- Values -------------------------------------------------------------

/** A value as it comes back out of a response. SQLite NULL -> null,
 *  INTEGER -> bigint, REAL -> number, TEXT -> string, BLOB -> Uint8Array.
 *  A REAL NaN is indistinguishable from SQL NULL once decoded (both arrive
 *  as the JSON literal `null`) -- that is upstream's own wire
 *  representation, not a decoding loss introduced here. */
export type Value = bigint | number | string | Uint8Array | null;

export type Row = readonly Value[];

/** A value a caller may pass as a query/exec parameter. Node's Buffer is a
 *  Uint8Array, so Buffers are accepted with no extra member. `boolean` is
 *  deliberately absent: SanDBox has no boolean storage class, so passing one
 *  is a compile error for TS callers and a thrown TypeError for JS callers. */
export type ParamValue = bigint | number | string | Uint8Array | null;

/** Every top-level field of a decoded response (including "ok"), which is
 *  what the conformance runner needs for partial-match comparisons. Typed
 *  decoders below only look at the fields relevant to their op. */
export type Fields = Readonly<Record<string, unknown>>;

// -- Errors ---------------------------------------------------------------

/** Base class for every error this driver raises. */
export class SanDBoxError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "SanDBoxError";
  }
}

/** A stdio protocol error response. Compare .code against the CODE_*
 *  constants above; .message is upstream's own text and can change wording
 *  between releases without notice, so never branch on it. */
export class ResponseError extends SanDBoxError {
  readonly code: string;

  constructor(code: string, message: string) {
    super(`${code}: ${message}`);
    this.name = "ResponseError";
    this.code = code;
  }
}

/** The driver saw something that violates the stdio contract (protocol.md). */
export class ProtocolError extends SanDBoxError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "ProtocolError";
  }
}

/** A call did not receive a response within its timeout. */
export class SanDBoxTimeoutError extends SanDBoxError {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "SanDBoxTimeoutError";
  }
}

// -- Result types -----------------------------------------------------------

export interface Hello {
  readonly protocol: number;
  readonly version: string;
  readonly product: string;
}

export interface QueryResult {
  readonly columns: readonly string[];
  readonly rows: readonly Row[];
}

export interface ExecResult {
  readonly rowsAffected: bigint;
  readonly lastInsertId: bigint;
}

export interface SnapshotResult {
  readonly path: string;
}

export interface InspectResult {
  readonly hasData: boolean;
  readonly version: bigint | null;
  readonly dataLength: bigint | null;
  readonly source: string;
  readonly readOnly: boolean;
}

export interface TablesResult {
  readonly tables: readonly string[];
}

export interface SchemaResult {
  readonly schema: readonly string[];
}

export interface DumpResult {
  readonly sql: string;
}

// -- Encoding ---------------------------------------------------------------

/** Renders a finite `number` as a REAL token: always carrying a decimal
 *  point or exponent, so it is bound as REAL rather than INTEGER upstream.
 *  A bare `String(88)` -> "88" would bind INTEGER -- the same pitfall Go's
 *  driver needed a custom formatter for and Python got for free from
 *  `repr()`. `JSON.rawJSON` itself validates the resulting token's syntax,
 *  so a malformed token can never reach the wire. */
function realToken(v: number): string {
  // String(-0) is "0" -- the sign would be silently dropped without this.
  let s = Object.is(v, -0) ? "-0" : String(v);
  if (!/[.eE]/.test(s)) s += ".0";
  return s;
}

/** Encode a single param value to something JSON.stringify renders
 *  correctly on the wire (using JSON.rawJSON for exact numeric tokens). */
export function encodeValue(v: ParamValue): unknown {
  if (v === null) return null;
  if (typeof v === "boolean") {
    throw new TypeError(`bool is not a supported param type (got ${v})`);
  }
  if (typeof v === "bigint") {
    if (v < INT64_MIN || v > INT64_MAX) {
      throw new RangeError(`bigint param out of int64 range: ${v}`);
    }
    return JSON.rawJSON(v.toString());
  }
  if (typeof v === "number") {
    if (!Number.isFinite(v)) {
      throw new RangeError(
        `REAL param must be finite (san-db-ox rejects NaN/Inf in params): ${v}`,
      );
    }
    return JSON.rawJSON(realToken(v));
  }
  if (typeof v === "string") return v;
  if (v instanceof Uint8Array) {
    return [Buffer.from(v).toString("base64")];
  }
  throw new TypeError(
    `unsupported param type ${typeof v} (want null, bigint, number, string, or Uint8Array)`,
  );
}

/** Encode a params array, or undefined if the field should be omitted. */
export function encodeParams(params?: readonly ParamValue[]): unknown[] | undefined {
  if (params === undefined || params.length === 0) return undefined;
  const encoded: unknown[] = [];
  for (let i = 0; i < params.length; i++) {
    try {
      // biome-ignore lint/style/noNonNullAssertion: i < params.length
      encoded.push(encodeValue(params[i]!));
    } catch (e) {
      if (e instanceof TypeError || e instanceof RangeError) {
        const wrapped = new (e.constructor as new (m: string) => Error)(
          `params[${i}]: ${e.message}`,
        );
        throw wrapped;
      }
      throw e;
    }
  }
  return encoded;
}

/** Encode a request object as one JSON Lines record, newline included.
 *  JSON.stringify handles the two things Python needed explicit options
 *  for: it emits no whitespace, and it does not need an ASCII-escape flag
 *  (non-ASCII passes through as UTF-8, encoded below). It also escapes
 *  every control character, so a request line can never contain an
 *  embedded newline. */
export function encodeRequestLine(req: Readonly<Record<string, unknown>>): Uint8Array {
  const text = JSON.stringify(req);
  return new TextEncoder().encode(`${text}\n`);
}

// -- Decoding: source-exact number parsing -----------------------------------

/** JSON.parse with a reviver that sees each number token's exact source
 *  text (the JSON.parse source-access proposal, available at this driver's
 *  Node >=22.12 floor) and hands it to `numberPolicy` instead of the lossy
 *  double JSON.parse would otherwise produce. This is the TS analogue of
 *  Go's json.Number / Python's parse_int+parse_float hooks. */
function parseJsonWith(
  text: string,
  numberPolicy: (token: string) => unknown,
): unknown {
  return JSON.parse(text, (_key, value, context) =>
    typeof value === "number" && typeof context?.source === "string"
      ? numberPolicy(context.source)
      : value,
  );
}

/** A token with no "." or "e"/"E" is an INTEGER -> bigint; otherwise it is a
 *  REAL -> number (Number("9e999") is Infinity, Number("-9e999") is
 *  -Infinity -- exactly upstream's wire representation for +-Inf). NaN
 *  arrives as the JSON literal `null` and needs no special case here.
 *  JSON.parse itself already rejects the bareword NaN/Infinity/-Infinity
 *  tokens (a SyntaxError, not a value) -- san-db-ox never emits them, so
 *  this is not a guard this driver needs to add, only rely on. */
function valueNumberPolicy(token: string): bigint | number {
  if (/[.eE]/.test(token)) return Number(token);
  const n = BigInt(token);
  if (n < INT64_MIN || n > INT64_MAX) {
    throw new ProtocolError(`integer value out of int64 range in response: ${token}`);
  }
  return n;
}

/** Parse response JSON with INTEGER/REAL tokens resolved to bigint/number
 *  respectively (protocol.md: never decode a 64-bit integer through a
 *  double). Used for every response the driver itself decodes. */
export function parseJsonPreservingNumbers(text: string): unknown {
  return parseJsonWith(text, valueNumberPolicy);
}

/** Parse JSON with every number token preserved verbatim as a JSON.rawJSON
 *  box, for exact byte-for-byte round-tripping. Used only by the
 *  conformance runner (test/match.ts), which needs to compare wire tokens
 *  literally (`88` must never match `88.0`) rather than decode them into
 *  driver values. */
export function parseJsonRaw(text: string): unknown {
  return parseJsonWith(text, (token) => JSON.rawJSON(token));
}

// -- Decoding: framing --------------------------------------------------------

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/** Decode a hello line. Does not validate the protocol number -- that's the
 *  caller's job, since only it knows what happens next (protocol.md). Uses
 *  plain JSON.parse (not parseJsonPreservingNumbers): `protocol` is a small
 *  discriminator the driver itself branches on, not SQL data, so it comes
 *  back as a native `number` (mirrors Python's decode_hello, which also
 *  uses plain json.loads). */
export function decodeHello(line: Uint8Array): Hello {
  const text = new TextDecoder("utf-8", { fatal: true }).decode(line);
  let obj: unknown;
  try {
    obj = JSON.parse(text);
  } catch (e) {
    throw new ProtocolError(`invalid hello line: ${(e as Error).message}`, {
      cause: e,
    });
  }
  if (!isPlainObject(obj)) {
    throw new ProtocolError(`hello line is not a JSON object: ${text}`);
  }
  const { protocol, version, product } = obj;
  if (typeof protocol !== "number") {
    throw new ProtocolError("hello line missing field 'protocol'");
  }
  if (typeof version !== "string") {
    throw new ProtocolError("hello line missing field 'version'");
  }
  if (typeof product !== "string") {
    throw new ProtocolError("hello line missing field 'product'");
  }
  return { protocol, version, product };
}

/** Decode one response line.
 *
 *  Returns { ok, fields, error }. On success, fields includes every
 *  top-level field of the response (including "ok"), which is what the
 *  conformance runner needs for partial-match comparisons; typed decoders
 *  below only look at the fields relevant to their op. */
export function decodeResponseLine(line: Uint8Array): {
  ok: boolean;
  fields: Fields;
  error: ResponseError | null;
} {
  const text = new TextDecoder("utf-8", { fatal: true }).decode(line);
  let obj: unknown;
  try {
    obj = parseJsonPreservingNumbers(text);
  } catch (e) {
    if (e instanceof ProtocolError) throw e;
    throw new ProtocolError(`invalid response line: ${(e as Error).message}`, {
      cause: e,
    });
  }
  if (!isPlainObject(obj)) {
    throw new ProtocolError(`response line is not a JSON object: ${text}`);
  }
  const fields = obj as Fields;
  if (fields.ok === true) {
    return { ok: true, fields, error: null };
  }
  const errorObj = fields.error;
  if (
    isPlainObject(errorObj) &&
    typeof errorObj.code === "string" &&
    typeof errorObj.message === "string"
  ) {
    return {
      ok: false,
      fields,
      error: new ResponseError(errorObj.code, errorObj.message),
    };
  }
  return { ok: false, fields, error: null };
}

const BASE64_RE = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** Buffer.from(s, "base64") silently ignores characters outside the base64
 *  alphabet rather than throwing, so a corrupt BLOB on the wire would
 *  decode to nonsense bytes with no error. Validate the shape first. */
function decodeBase64Strict(s: string): Uint8Array {
  if (!BASE64_RE.test(s)) {
    throw new ProtocolError(`invalid BLOB base64: ${s}`);
  }
  return Buffer.from(s, "base64");
}

export function decodeValue(raw: unknown): Value {
  if (raw === null) return null;
  if (typeof raw === "boolean") {
    throw new ProtocolError(`unexpected boolean value in response: ${raw}`);
  }
  if (typeof raw === "bigint" || typeof raw === "number" || typeof raw === "string") {
    return raw;
  }
  if (Array.isArray(raw)) {
    if (raw.length !== 1 || typeof raw[0] !== "string") {
      throw new ProtocolError(
        `protocol violation: BLOB array must have exactly 1 string element, got ${JSON.stringify(raw)}`,
      );
    }
    return decodeBase64Strict(raw[0]);
  }
  throw new ProtocolError(`unexpected value type in response: ${typeof raw}`);
}

export function decodeRows(raw: unknown): Row[] {
  if (!Array.isArray(raw)) {
    throw new ProtocolError(`rows field is not an array: ${JSON.stringify(raw)}`);
  }
  const rows: Row[] = [];
  for (let i = 0; i < raw.length; i++) {
    const row = raw[i];
    if (!Array.isArray(row)) {
      throw new ProtocolError(`rows[${i}] is not an array: ${JSON.stringify(row)}`);
    }
    try {
      rows.push(row.map(decodeValue));
    } catch (e) {
      if (e instanceof ProtocolError) {
        throw new ProtocolError(`rows[${i}]: ${e.message}`, { cause: e });
      }
      throw e;
    }
  }
  return rows;
}

function required(fields: Fields, key: string): unknown {
  if (!(key in fields)) {
    throw new ProtocolError(`response missing field '${key}'`);
  }
  return fields[key];
}

function requiredStr(fields: Fields, key: string): string {
  const v = required(fields, key);
  if (typeof v !== "string") {
    throw new ProtocolError(
      `response field '${key}' is not a string: ${JSON.stringify(v)}`,
    );
  }
  return v;
}

function requiredBool(fields: Fields, key: string): boolean {
  const v = required(fields, key);
  if (typeof v !== "boolean") {
    throw new ProtocolError(
      `response field '${key}' is not a bool: ${JSON.stringify(v)}`,
    );
  }
  return v;
}

function requiredBigInt(fields: Fields, key: string): bigint {
  const v = required(fields, key);
  if (typeof v !== "bigint") {
    throw new ProtocolError(
      `response field '${key}' is not an integer: ${JSON.stringify(v)}`,
    );
  }
  return v;
}

function requiredOptionalBigInt(fields: Fields, key: string): bigint | null {
  const v = required(fields, key);
  if (v === null) return null;
  if (typeof v !== "bigint") {
    throw new ProtocolError(
      `response field '${key}' is not an integer or null: ${JSON.stringify(v)}`,
    );
  }
  return v;
}

function requiredStrArray(fields: Fields, key: string): string[] {
  const v = required(fields, key);
  if (!Array.isArray(v) || !v.every((x) => typeof x === "string")) {
    throw new ProtocolError(
      `response field '${key}' is not a string array: ${JSON.stringify(v)}`,
    );
  }
  return v as string[];
}

export function decodeQueryResponse(fields: Fields): QueryResult {
  return {
    columns: requiredStrArray(fields, "columns"),
    rows: decodeRows(required(fields, "rows")),
  };
}

export function decodeExecResponse(fields: Fields): ExecResult {
  return {
    rowsAffected: requiredBigInt(fields, "rows_affected"),
    lastInsertId: requiredBigInt(fields, "last_insert_id"),
  };
}

export function decodeSnapshotResponse(fields: Fields): SnapshotResult {
  return { path: requiredStr(fields, "path") };
}

export function decodeInspectResponse(fields: Fields): InspectResult {
  return {
    hasData: requiredBool(fields, "has_data"),
    version: requiredOptionalBigInt(fields, "version"),
    dataLength: requiredOptionalBigInt(fields, "data_length"),
    source: requiredStr(fields, "source"),
    readOnly: requiredBool(fields, "read_only"),
  };
}

export function decodeTablesResponse(fields: Fields): TablesResult {
  return { tables: requiredStrArray(fields, "tables") };
}

export function decodeSchemaResponse(fields: Fields): SchemaResult {
  return { schema: requiredStrArray(fields, "schema") };
}

export function decodeDumpResponse(fields: Fields): DumpResult {
  return { sql: requiredStr(fields, "sql") };
}
