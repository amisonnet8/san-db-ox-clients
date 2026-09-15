// Byte transport for SanDBox's stdio protocol: moves bytes and owns
// connection lifetime. Knows nothing about JSON Lines framing semantics or
// the op vocabulary -- that's codec.ts's job. This layer is exactly where
// I/O, subprocess, and socket machinery are allowed to live
// (.claude/rules/architecture.md).

import { type ChildProcess, spawn } from "node:child_process";
import { createConnection, type Socket } from "node:net";
import { constants as osConstants } from "node:os";
import type { Duplex, Readable, Writable } from "node:stream";
import {
  MAX_LINE_BYTES,
  ProtocolError,
  SanDBoxError,
  SanDBoxTimeoutError,
} from "./codec.js";

export interface Transport {
  writeLine(line: Uint8Array): Promise<void>;
  readLine(timeoutMs: number | null, signal?: AbortSignal): Promise<Buffer>;
  close(timeoutMs: number): Promise<void>;
}

const LF = 0x0a;

/** One reader per connection, feeding readLine() callers a single
 *  line at a time. Backpressure is 1 slot deep: once a complete line is
 *  buffered the source stream is paused, and resumed only once that line
 *  is taken (the direct translation of Python's queue.Queue(maxsize=1)),
 *  so a misbehaving peer can't make this driver buffer unboundedly.
 *
 *  A terminal condition (EOF or a stream error) is memoized and re-thrown
 *  on every subsequent readLine -- once the stream ends there is no way to
 *  "get another line" from it, so every later call must see the same
 *  outcome rather than hang. */
class LineReader {
  #source: Readable;
  #pending: Buffer[] = [];
  #pendingBytes = 0;
  #queue: Buffer[] = [];
  #waiters: Array<{ resolve: (line: Buffer) => void; reject: (err: Error) => void }> =
    [];
  #terminal: Error | null = null;

  constructor(source: Readable) {
    this.#source = source;
    source.on("data", (chunk: Buffer) => this.#onData(chunk));
    source.on("end", () =>
      this.#terminate(new SanDBoxError("connection closed (EOF)")),
    );
    source.on("error", (err: Error) => this.#terminate(err));
  }

  #onData(chunk: Buffer): void {
    // Once terminal (EOF, an error, or a MAX_LINE_BYTES violation), ignore
    // any further data. Without this guard a peer that keeps writing after
    // a protocol violation -- or simply keeps writing forever without a
    // newline -- would make #pending grow without bound and keep this
    // callback running flat-out even though nothing can ever be delivered
    // again.
    if (this.#terminal) return;
    let start = 0;
    for (let i = 0; i < chunk.length; i++) {
      if (chunk[i] !== LF) continue;
      const piece = chunk.subarray(start, i);
      this.#pending.push(piece);
      const line = this.#pending.length === 1 ? piece : Buffer.concat(this.#pending);
      this.#pending = [];
      this.#pendingBytes = 0;
      this.#deliver(line);
      start = i + 1;
    }
    if (start < chunk.length) {
      const rest = chunk.subarray(start);
      this.#pending.push(rest);
      this.#pendingBytes += rest.length;
      if (this.#pendingBytes > MAX_LINE_BYTES) {
        this.#terminate(
          new ProtocolError(
            `response line exceeds ${MAX_LINE_BYTES} bytes without a newline`,
          ),
        );
        return;
      }
    }
    // 1-slot backpressure: stop reading more once a line is queued and
    // unclaimed.
    if (this.#queue.length > 0) {
      this.#source.pause();
    }
  }

  #deliver(line: Buffer): void {
    const waiter = this.#waiters.shift();
    if (waiter) {
      waiter.resolve(line);
    } else {
      this.#queue.push(line);
      this.#source.pause();
    }
  }

  #terminate(err: Error): void {
    if (this.#terminal) return;
    this.#terminal = err;
    // Nothing will ever be delivered again -- drop any partial line and
    // stop consuming the source so a peer that keeps writing (deliberately
    // or not) can't keep this reader spinning or growing #pending forever.
    // pause() also applies OS-level backpressure: a still-writing child
    // blocks on its next write() instead of burning CPU indefinitely.
    this.#pending = [];
    this.#pendingBytes = 0;
    this.#source.pause();
    const waiters = this.#waiters;
    this.#waiters = [];
    for (const w of waiters) w.reject(err);
  }

  /** Forces the terminal condition immediately, for a failure that doesn't
   *  arrive through the source stream itself -- e.g. the child process
   *  failing to spawn at all, in which case stdout will never emit
   *  anything on its own. */
  fail(err: Error): void {
    this.#terminate(err);
  }

  /** Resolves with the next complete line, or rejects with SanDBoxTimeoutError
   *  if timeoutMs elapses first (null = wait forever), or with the memoized
   *  terminal error once the stream has ended. */
  readLine(timeoutMs: number | null, signal?: AbortSignal): Promise<Buffer> {
    const queued = this.#queue.shift();
    if (queued !== undefined) {
      if (this.#queue.length === 0) this.#source.resume();
      return Promise.resolve(queued);
    }
    if (this.#terminal) return Promise.reject(this.#terminal);
    if (signal?.aborted) return Promise.reject(signal.reason ?? new Error("aborted"));

    return new Promise<Buffer>((resolve, reject) => {
      const waiter = { resolve, reject };
      this.#waiters.push(waiter);

      let timer: NodeJS.Timeout | undefined;
      const onAbort = () =>
        settle(() => reject(signal?.reason ?? new Error("aborted")));
      const cleanup = () => {
        if (timer) clearTimeout(timer);
        signal?.removeEventListener("abort", onAbort);
        const idx = this.#waiters.indexOf(waiter);
        if (idx !== -1) this.#waiters.splice(idx, 1);
      };
      const settle = (run: () => void) => {
        cleanup();
        run();
      };

      waiter.resolve = (line) => settle(() => resolve(line));
      waiter.reject = (err) => settle(() => reject(err));

      if (timeoutMs !== null) {
        timer = setTimeout(() => {
          settle(() =>
            reject(new SanDBoxTimeoutError("timed out waiting for a response line")),
          );
        }, timeoutMs);
      }
      if (signal) {
        signal.addEventListener("abort", onAbort, { once: true });
      }
    });
  }
}

/** Copies src to dst in the background to completion, so a child's stderr
 *  pipe never fills and blocks the child (protocol.md: stderr must always
 *  be drained). Errors from either end (the sink closing first, etc.) are
 *  swallowed -- draining is best-effort, not a contract the caller can
 *  observe failing. */
function drain(src: Readable, dst: Writable): void {
  src.on("error", () => {});
  dst.on("error", () => {});
  src.pipe(dst, { end: false });
}

function afterDeadline<T>(promise: Promise<T>, ms: number): Promise<T | "timeout"> {
  return Promise.race([
    promise,
    new Promise<"timeout">((resolve) => setTimeout(() => resolve("timeout"), ms)),
  ]);
}

export interface DirectOptions {
  readonly env?: NodeJS.ProcessEnv;
  readonly cwd?: string | URL;
  /** Where the child's stderr goes. Omit it and stderr is routed to the OS
   *  null device (no pipe to fill, nothing to drain). Supply a Writable and
   *  it is piped with {end:false}, so passing process.stderr is safe (this
   *  transport won't close it). */
  readonly stderr?: Writable;
}

/** Direct-connect transport: launches `command` with `args` as a child
 *  process and speaks the protocol over its stdin/stdout. The command to
 *  launch is entirely caller-supplied, which is what lets local,
 *  `ssh user@host san-db-ox --serve-stdio`, `docker run -i`, and
 *  `kubectl exec -i` all use this one code path -- there is deliberately no
 *  SSH- or Docker-specific constructor (.claude/rules/architecture.md). */
export class DirectTransport implements Transport {
  #child: ChildProcess;
  #reader: LineReader;
  // Resolves on the child's "close" event, which (unlike "exit") fires only
  // once every stdio stream has finished emitting -- waiting on this rather
  // than "exit" is what guarantees a provided stderr sink has received
  // everything before close() tears the streams down.
  #closed: Promise<void>;
  #closePromise: Promise<void> | null = null;

  private constructor(child: ChildProcess, reader: LineReader, closed: Promise<void>) {
    this.#child = child;
    this.#reader = reader;
    this.#closed = closed;
  }

  static start(
    command: string,
    args: readonly string[] = [],
    options: DirectOptions = {},
  ): DirectTransport {
    const child = spawn(command, args, {
      stdio: ["pipe", "pipe", options.stderr ? "pipe" : "ignore"],
      env: options.env,
      cwd: options.cwd,
    });
    // A write to a dead child raises EPIPE as an "error" event on stdin;
    // left unhandled that is an uncaught exception that kills the process.
    // Swallow it here -- the memoized reader terminal error (from the
    // matching stdout "end"/"error", or from the spawn-failure handler
    // below) is what surfaces to the next call.
    child.stdin?.on("error", () => {});
    // biome-ignore lint/style/noNonNullAssertion: stdio[1] is "pipe"
    const reader = new LineReader(child.stdout!);
    // Spawn failure (e.g. ENOENT) arrives as an async "error" event, not a
    // synchronous throw -- unlike Python's Popen, which raises
    // FileNotFoundError immediately. Feed it into the reader as the
    // terminal condition so the first readLine (reading hello) rejects
    // with it, which is what surfaces to callers of connect().
    child.on("error", (err) => {
      reader.fail(
        new SanDBoxError(`starting ${JSON.stringify(command)}: ${err.message}`, {
          cause: err,
        }),
      );
    });
    if (options.stderr && child.stderr) {
      drain(child.stderr, options.stderr);
    }
    const closed = new Promise<void>((resolve) => {
      child.once("close", () => resolve());
    });
    return new DirectTransport(child, reader, closed);
  }

  async writeLine(line: Uint8Array): Promise<void> {
    const stdin = this.#child.stdin;
    if (!stdin) throw new SanDBoxError("child process has no stdin");
    await new Promise<void>((resolve, reject) => {
      stdin.write(line, (err) =>
        err
          ? reject(new SanDBoxError(`writing request: ${err.message}`, { cause: err }))
          : resolve(),
      );
    });
  }

  readLine(timeoutMs: number | null, signal?: AbortSignal): Promise<Buffer> {
    return this.#reader.readLine(timeoutMs, signal);
  }

  /** The child's exit code. null while still running. Negative (-signal)
   *  when the child was terminated by a signal, mirroring Python's
   *  Popen.poll() convention (SIGTERM -> -15) -- Node reports exitCode and
   *  signalCode separately, and returning exitCode raw would make null
   *  ambiguous between "still running" and "killed by a signal". */
  get exitCode(): number | null {
    if (this.#child.exitCode !== null) return this.#child.exitCode;
    if (this.#child.signalCode) {
      const n =
        osConstants.signals[this.#child.signalCode as keyof typeof osConstants.signals];
      return typeof n === "number" ? -n : null;
    }
    return null;
  }

  /** Staged shutdown: close stdin, wait; SIGTERM, wait; SIGKILL, wait
   *  unconditionally (SIGKILL cannot be ignored). Safe to call more than
   *  once, and safe to call after the process already exited on its own
   *  (e.g. after a successful overwrite, which ends the connection from
   *  the server's side). */
  close(timeoutMs: number): Promise<void> {
    if (this.#closePromise) return this.#closePromise;
    this.#closePromise = this.#close(timeoutMs);
    return this.#closePromise;
  }

  async #close(timeoutMs: number): Promise<void> {
    const child = this.#child;
    if (child.exitCode === null && child.signalCode === null) {
      child.stdin?.end();
      if ((await afterDeadline(this.#closed, timeoutMs)) === "timeout") {
        child.kill("SIGTERM");
        if ((await afterDeadline(this.#closed, timeoutMs)) === "timeout") {
          child.kill("SIGKILL");
          await this.#closed;
        }
      }
    } else {
      // Already exited (e.g. after a successful overwrite), but "close"
      // (unlike "exit") may not have fired yet if stdio hasn't finished
      // flushing -- wait for it too, bounded by timeoutMs, so a provided
      // stderr sink still receives everything before the streams below are
      // torn down.
      await afterDeadline(this.#closed, timeoutMs);
    }
    child.stdout?.destroy();
    child.stderr?.destroy();
    child.stdin?.destroy();
  }
}

/** Races a freshly created socket's "connect" event against a deadline.
 *  Deliberately not socket.setTimeout(), which is an idle timeout applied
 *  after connecting, not a bound on the connection attempt itself. */
function connectWithDeadline(
  create: () => Socket,
  timeoutMs: number | null,
): Promise<Socket> {
  return new Promise((resolve, reject) => {
    const socket = create();
    let settled = false;
    let timer: NodeJS.Timeout | undefined;

    const settle = (run: () => void) => {
      if (settled) return;
      settled = true;
      socket.removeListener("connect", onConnect);
      socket.removeListener("error", onError);
      if (timer) clearTimeout(timer);
      run();
    };
    const onConnect = () => settle(() => resolve(socket));
    const onError = (err: Error) =>
      settle(() =>
        reject(new SanDBoxError(`connecting: ${err.message}`, { cause: err })),
      );

    socket.once("connect", onConnect);
    socket.once("error", onError);
    if (timeoutMs !== null) {
      timer = setTimeout(() => {
        settle(() => {
          socket.destroy();
          reject(new SanDBoxTimeoutError(`timed out connecting after ${timeoutMs}ms`));
        });
      }, timeoutMs);
    }
  });
}

/** Socket transport: TCP, UNIX domain socket, or any already-connected
 *  Duplex (the seam for connections this module can't build directly, most
 *  notably a TLS socket for mutual-TLS authentication -- no TLS-specific
 *  constructor exists here, and this module never imports "node:tls"). No
 *  staged shutdown: there is no child process on this end to signal or
 *  reap, so close is a single deadline-then-destroy. */
export class SocketTransport implements Transport {
  #stream: Duplex;
  #reader: LineReader;
  #closePromise: Promise<void> | null = null;

  constructor(stream: Duplex) {
    this.#stream = stream;
    this.#reader = new LineReader(stream);
  }

  static async connectTcp(
    host: string,
    port: number,
    timeoutMs: number | null,
  ): Promise<SocketTransport> {
    const socket = await connectWithDeadline(
      () => createConnection({ host, port }),
      timeoutMs,
    );
    return new SocketTransport(socket);
  }

  static async connectUnix(
    path: string,
    timeoutMs: number | null,
  ): Promise<SocketTransport> {
    const socket = await connectWithDeadline(
      () => createConnection({ path }),
      timeoutMs,
    );
    return new SocketTransport(socket);
  }

  async writeLine(line: Uint8Array): Promise<void> {
    await new Promise<void>((resolve, reject) => {
      this.#stream.write(line, (err) =>
        err
          ? reject(new SanDBoxError(`writing request: ${err.message}`, { cause: err }))
          : resolve(),
      );
    });
  }

  readLine(timeoutMs: number | null, signal?: AbortSignal): Promise<Buffer> {
    return this.#reader.readLine(timeoutMs, signal);
  }

  close(timeoutMs: number): Promise<void> {
    if (this.#closePromise) return this.#closePromise;
    this.#closePromise = this.#close(timeoutMs);
    return this.#closePromise;
  }

  async #close(timeoutMs: number): Promise<void> {
    const stream = this.#stream;
    const closed = new Promise<void>((resolve) => {
      if (stream.destroyed) {
        resolve();
        return;
      }
      stream.once("close", () => resolve());
    });
    stream.end();
    await afterDeadline(closed, timeoutMs);
    stream.destroy();
  }
}
