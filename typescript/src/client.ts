// Session, Client, and SocketClient: the public connection objects.
//
// The protocol has no request id, so responses can only be matched to
// requests by strict ordering. There is no lock in Node, so a promise-chain
// queue in Session serializes calls instead (mirrors go/sandbox/sandbox.go's
// session and python/src/san_db_ox/_client.py's threading.Lock).

import type { Duplex, Writable } from "node:stream";
import {
  type DumpResult,
  decodeDumpResponse,
  decodeExecResponse,
  decodeHello,
  decodeInspectResponse,
  decodeQueryResponse,
  decodeResponseLine,
  decodeSchemaResponse,
  decodeSnapshotResponse,
  decodeTablesResponse,
  type ExecResult,
  encodeParams,
  encodeRequestLine,
  type Fields,
  type Hello,
  type InspectResult,
  type ParamValue,
  PROTOCOL,
  ProtocolError,
  type QueryResult,
  SanDBoxError,
  SanDBoxTimeoutError,
  type SchemaResult,
  type SnapshotResult,
  type TablesResult,
} from "./codec.js";
import {
  type DirectOptions,
  DirectTransport,
  SocketTransport,
  type Transport,
} from "./transport.js";

export const DEFAULT_TIMEOUT_MS = 30_000;

// How long each stage of close() waits before escalating (Client) or before
// giving up on a graceful shutdown (SocketClient).
export const CLOSE_TIMEOUT_MS = 5_000;

/** timeoutMs: undefined -> use the connection's default; null -> wait
 *  forever. Replaces Python's _Unset sentinel class -- JS already
 *  distinguishes undefined from null natively, so no sentinel is needed. */
export interface CallOptions {
  readonly timeoutMs?: number | null;
  readonly signal?: AbortSignal;
}

export interface SnapshotOptions {
  readonly filename?: string;
  readonly sqlite?: boolean;
  readonly timestamp?: boolean;
}

export interface CloseOptions {
  readonly timeoutMs?: number;
}

export interface ConnectOptions extends CallOptions {
  readonly env?: NodeJS.ProcessEnv;
  readonly cwd?: string | URL;
  /** Where the child's stderr goes. Omit it and stderr is routed to the OS
   *  null device (no pipe to fill). Supply a Writable and it is piped with
   *  {end:false}, so passing process.stderr is safe. */
  readonly stderr?: Writable;
}

export type SocketConnectOptions = CallOptions;

/** What every connection to a SanDBox process can do, regardless of
 *  transport. overwrite/exitCode are deliberately not part of this -- they
 *  only mean something over a direct connection, and are only on Client. */
export interface Connection extends AsyncDisposable {
  readonly hello: Hello;

  query(
    sql: string,
    params?: readonly ParamValue[],
    options?: CallOptions,
  ): Promise<QueryResult>;
  exec(
    sql: string,
    params?: readonly ParamValue[],
    options?: CallOptions,
  ): Promise<ExecResult>;
  snapshot(options?: SnapshotOptions & CallOptions): Promise<SnapshotResult>;
  load(path: string, options?: CallOptions): Promise<void>;
  inspect(options?: CallOptions): Promise<InspectResult>;
  tables(options?: CallOptions): Promise<TablesResult>;
  schema(table?: string, options?: CallOptions): Promise<SchemaResult>;
  dump(pattern?: string, options?: CallOptions): Promise<DumpResult>;
  close(options?: CloseOptions): Promise<void>;
}

async function readHello(
  transport: Transport,
  timeoutMs: number | null,
): Promise<Hello> {
  let line: Buffer;
  try {
    line = await transport.readLine(timeoutMs);
  } catch (e) {
    await transport.close(CLOSE_TIMEOUT_MS);
    throw e instanceof SanDBoxError
      ? e
      : new SanDBoxError(`reading hello line: ${(e as Error).message}`, { cause: e });
  }
  let hello: Hello;
  try {
    hello = decodeHello(line);
  } catch (e) {
    await transport.close(CLOSE_TIMEOUT_MS);
    throw e;
  }
  if (hello.protocol !== PROTOCOL) {
    await transport.close(CLOSE_TIMEOUT_MS);
    throw new SanDBoxError(
      `unsupported protocol ${hello.protocol} (this driver speaks ${PROTOCOL})`,
    );
  }
  return hello;
}

/** Machinery shared by every transport: hello validation and the serialized
 *  call/response cycle. Client and SocketClient each extend this; neither
 *  exposes it publicly (it isn't exported). */
class Session {
  readonly hello: Hello;
  #transport: Transport;
  #defaultTimeoutMs: number | null;
  #tail: Promise<unknown> = Promise.resolve();
  #closed = false;
  #closePromise: Promise<void> | null = null;
  // Set only when a call times out (or is aborted mid-read): a single
  // pending read can't be cancelled, so the session is torn down in the
  // background rather than left to hang. Retained so a later close() can
  // await it, instead of returning before the teardown has actually
  // finished -- which is what keeps `node --test` from exiting with a
  // still-live child process.
  #teardown: Promise<void> | null = null;

  protected constructor(
    transport: Transport,
    hello: Hello,
    defaultTimeoutMs: number | null,
  ) {
    this.#transport = transport;
    this.hello = hello;
    this.#defaultTimeoutMs = defaultTimeoutMs;
  }

  #resolveTimeout(options?: CallOptions): number | null {
    if (options && "timeoutMs" in options && options.timeoutMs !== undefined) {
      return options.timeoutMs;
    }
    return this.#defaultTimeoutMs;
  }

  #enqueue<T>(fn: () => Promise<T>): Promise<T> {
    // Chained onto both success and failure of the previous call so one
    // failed call never wedges the queue (the direct analogue of a lock
    // that's released on exception).
    const run = this.#tail.then(fn, fn);
    // Swallow the result here so a later call's own failure doesn't also
    // surface as a second, unhandled rejection through the tail chain.
    this.#tail = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  protected call(req: Record<string, unknown>, options?: CallOptions): Promise<Fields> {
    return this.#enqueue(() => this.#callNow(req, options));
  }

  async #callNow(req: Record<string, unknown>, options?: CallOptions): Promise<Fields> {
    if (this.#closed) {
      throw new SanDBoxError("sandbox: connection is closed");
    }
    const signal = options?.signal;
    if (signal?.aborted) {
      // Nothing has been written yet, so the connection stays usable.
      throw signal.reason ?? new Error("aborted");
    }
    const timeoutMs = this.#resolveTimeout(options);

    try {
      await this.#transport.writeLine(encodeRequestLine(req));
    } catch (e) {
      throw e instanceof SanDBoxError
        ? e
        : new SanDBoxError(`writing request: ${(e as Error).message}`, { cause: e });
    }

    let line: Buffer;
    try {
      line = await this.#transport.readLine(timeoutMs, signal);
    } catch (e) {
      if (e instanceof SanDBoxTimeoutError || signal?.aborted) {
        // The read is stuck; there is no way to interrupt a single pending
        // read short of tearing down the connection, so do that in the
        // background -- the caller asked to give up (by timeout or abort),
        // and a session that can never be used again is a fair price to
        // guarantee no process or handle is leaked.
        this.#closed = true;
        this.#teardown = this.#transport.close(CLOSE_TIMEOUT_MS).catch(() => {});
        throw e instanceof SanDBoxTimeoutError ? e : (signal?.reason ?? e);
      }
      throw e instanceof SanDBoxError
        ? e
        : new SanDBoxError(`reading response: ${(e as Error).message}`, { cause: e });
    }

    const { ok, fields, error } = decodeResponseLine(line);
    if (!ok) {
      if (error) throw error;
      throw new ProtocolError("response has ok=false with no error field");
    }
    return fields;
  }

  async query(
    sql: string,
    params: readonly ParamValue[] = [],
    options?: CallOptions,
  ): Promise<QueryResult> {
    const req: Record<string, unknown> = { op: "query", sql };
    const encoded = encodeParams(params);
    if (encoded !== undefined) req.params = encoded;
    return decodeQueryResponse(await this.call(req, options));
  }

  async exec(
    sql: string,
    params: readonly ParamValue[] = [],
    options?: CallOptions,
  ): Promise<ExecResult> {
    const req: Record<string, unknown> = { op: "exec", sql };
    const encoded = encodeParams(params);
    if (encoded !== undefined) req.params = encoded;
    return decodeExecResponse(await this.call(req, options));
  }

  async snapshot(options?: SnapshotOptions & CallOptions): Promise<SnapshotResult> {
    const req: Record<string, unknown> = { op: "snapshot" };
    if (options?.filename) req.filename = options.filename;
    if (options?.sqlite) req.sqlite = options.sqlite;
    if (options?.timestamp) req.timestamp = options.timestamp;
    return decodeSnapshotResponse(await this.call(req, options));
  }

  async load(path: string, options?: CallOptions): Promise<void> {
    await this.call({ op: "load", path }, options);
  }

  async inspect(options?: CallOptions): Promise<InspectResult> {
    return decodeInspectResponse(await this.call({ op: "inspect" }, options));
  }

  async tables(options?: CallOptions): Promise<TablesResult> {
    return decodeTablesResponse(await this.call({ op: "tables" }, options));
  }

  async schema(table?: string, options?: CallOptions): Promise<SchemaResult> {
    const req: Record<string, unknown> = { op: "schema" };
    if (table) req.table = table;
    return decodeSchemaResponse(await this.call(req, options));
  }

  async dump(pattern?: string, options?: CallOptions): Promise<DumpResult> {
    const req: Record<string, unknown> = { op: "dump" };
    if (pattern) req.pattern = pattern;
    return decodeDumpResponse(await this.call(req, options));
  }

  /** Gracefully end the connection: send the close op, and regardless of
   *  whether a response arrives, follow through with the transport's own
   *  shutdown so nothing is left dangling. Safe to call more than once. */
  async close(options?: CloseOptions): Promise<void> {
    if (this.#closePromise) return this.#closePromise;
    this.#closePromise = this.#doClose(options?.timeoutMs ?? CLOSE_TIMEOUT_MS);
    return this.#closePromise;
  }

  async #doClose(timeoutMs: number): Promise<void> {
    this.#closed = true;
    // Best effort: send the close op so a well-behaved server exits
    // promptly. Its response (or the lack of one) doesn't change what
    // happens next -- the shutdown below ends the connection either way.
    try {
      await this.#transport.writeLine(encodeRequestLine({ op: "close" }));
    } catch {
      // ignored
    }
    if (this.#teardown) {
      await this.#teardown;
    }
    await this.#transport.close(timeoutMs);
  }

  async [Symbol.asyncDispose](): Promise<void> {
    await this.close();
  }
}

/** A direct-connect connection to a running SanDBox process.
 *
 *  Not safe for concurrent use by multiple callers: the protocol has no
 *  request id, so responses can only be matched to requests by strict
 *  ordering -- Session serializes calls with an internal promise queue
 *  rather than exposing that footgun. */
export class Client extends Session implements Connection {
  #direct: DirectTransport;

  private constructor(
    direct: DirectTransport,
    hello: Hello,
    defaultTimeoutMs: number | null,
  ) {
    super(direct, hello, defaultTimeoutMs);
    this.#direct = direct;
  }

  static async open(
    command: string,
    args: readonly string[],
    options: ConnectOptions,
  ): Promise<Client> {
    const timeoutMs =
      options.timeoutMs === undefined ? DEFAULT_TIMEOUT_MS : options.timeoutMs;
    const directOptions: DirectOptions = {
      ...(options.env !== undefined && { env: options.env }),
      ...(options.cwd !== undefined && { cwd: options.cwd }),
      ...(options.stderr !== undefined && { stderr: options.stderr }),
    };
    const direct = DirectTransport.start(command, args, directOptions);
    const hello = await readHello(direct, timeoutMs);
    return new Client(direct, hello, timeoutMs);
  }

  /** Replaces the running process's own executable with one embedding the
   *  current database, then exits. This only makes sense over a
   *  direct-connect process -- socat-fronted sockets can have several
   *  clients connect through the same listener, and multiple processes
   *  writing the same executable path at once is exactly what this op
   *  shouldn't risk -- so it lives here on Client, not on Connection, and
   *  SocketClient has no overwrite method to call. */
  async overwrite(options?: CallOptions): Promise<void> {
    await this.call({ op: "overwrite" }, options);
    // A successful overwrite ends the connection from the server's side;
    // reap it so no zombie process is left behind.
    await this.close();
  }

  /** The child process's exit code. Only meaningful after close() or
   *  overwrite() has returned. Only available over a direct connection --
   *  SocketClient has no child process to report on. */
  get exitCode(): number | null {
    return this.#direct.exitCode;
  }
}

/** A socket connection to a SanDBox process, typically fronted by something
 *  like socat. Has no overwrite or exitCode -- see Client. */
export class SocketClient extends Session implements Connection {
  private constructor(
    socket: SocketTransport,
    hello: Hello,
    defaultTimeoutMs: number | null,
  ) {
    super(socket, hello, defaultTimeoutMs);
  }

  static async wrap(
    transport: SocketTransport,
    defaultTimeoutMs: number | null,
  ): Promise<SocketClient> {
    const hello = await readHello(transport, defaultTimeoutMs);
    return new SocketClient(transport, hello, defaultTimeoutMs);
  }
}

/** Launches command with args as a child process, reads its hello line, and
 *  returns a ready-to-use Client.
 *
 *  timeoutMs bounds the connection attempt (reading the hello line) and
 *  becomes the default for methods that don't pass their own timeout. */
export function connect(
  command: string,
  args: readonly string[] = [],
  options: ConnectOptions = {},
): Promise<Client> {
  return Client.open(command, args, options);
}

/** Dials a TCP socket exposing SanDBox's stdio protocol (e.g. via socat),
 *  reads its hello line, and returns a ready-to-use SocketClient. */
export async function connectTcp(
  host: string,
  port: number,
  options: SocketConnectOptions = {},
): Promise<SocketClient> {
  const timeoutMs =
    options.timeoutMs === undefined ? DEFAULT_TIMEOUT_MS : options.timeoutMs;
  const transport = await SocketTransport.connectTcp(host, port, timeoutMs);
  return SocketClient.wrap(transport, timeoutMs);
}

/** Dials a UNIX domain socket exposing SanDBox's stdio protocol (e.g. via
 *  socat), reads its hello line, and returns a ready-to-use SocketClient. */
export async function connectUnix(
  path: string,
  options: SocketConnectOptions = {},
): Promise<SocketClient> {
  const timeoutMs =
    options.timeoutMs === undefined ? DEFAULT_TIMEOUT_MS : options.timeoutMs;
  const transport = await SocketTransport.connectUnix(path, timeoutMs);
  return SocketClient.wrap(transport, timeoutMs);
}

/** Wraps an already-connected Duplex, reads its hello line, and returns a
 *  ready-to-use SocketClient. This is the seam for connections
 *  connectTcp/connectUnix can't build directly -- most notably a
 *  TLS-wrapped socket (tls.connect()'s TLSSocket) for mutual-TLS
 *  authentication -- without this module needing a TLS-specific
 *  constructor. */
export async function connectSocket(
  stream: Duplex,
  options: SocketConnectOptions = {},
): Promise<SocketClient> {
  const timeoutMs =
    options.timeoutMs === undefined ? DEFAULT_TIMEOUT_MS : options.timeoutMs;
  const transport = new SocketTransport(stream);
  return SocketClient.wrap(transport, timeoutMs);
}
