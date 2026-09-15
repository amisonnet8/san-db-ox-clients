// A driver is a convenience, not a prerequisite. The stdio protocol is
// line-delimited JSON, and any runtime that can spawn a subprocess and
// speak to its pipes can talk to san-db-ox directly with no library at all.
// This driver is a thin, typed layer on top of that: op names mirror the
// protocol 1:1 (query, exec, snapshot, load, inspect, tables, schema, dump,
// overwrite, close), mapped only to TypeScript's camelCase convention.
//
// This is the only public module -- everything else under src/ is
// implementation detail reached only through relative imports inside this
// package (enforced by package.json's "exports" map, not by convention).

export {
  type CallOptions,
  CLOSE_TIMEOUT_MS,
  Client,
  type CloseOptions,
  type Connection,
  type ConnectOptions,
  connect,
  connectSocket,
  connectTcp,
  connectUnix,
  DEFAULT_TIMEOUT_MS,
  type SnapshotOptions,
  SocketClient,
  type SocketConnectOptions,
} from "./client.js";
export {
  CODE_BAD_REQUEST,
  CODE_IO_ERROR,
  CODE_READ_ONLY,
  CODE_SQLITE_ERROR,
  CODE_UNSUPPORTED_OP,
  type DumpResult,
  type ErrorCode,
  type ExecResult,
  type Hello,
  type InspectResult,
  type ParamValue,
  PROTOCOL,
  ProtocolError,
  type QueryResult,
  ResponseError,
  type Row,
  SanDBoxError,
  SanDBoxTimeoutError,
  type SchemaResult,
  type SnapshotResult,
  type TablesResult,
  type Value,
} from "./codec.js";

/** The version of this package. Kept in sync with package.json's "version"
 *  by a test (test/client.test.ts), not by build-time codegen -- there is
 *  no automated single-sourcing here, the same tradeoff Python's
 *  __version__ makes. */
export const VERSION = "0.1.0";
