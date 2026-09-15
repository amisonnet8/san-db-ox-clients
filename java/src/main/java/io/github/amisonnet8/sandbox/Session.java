package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.codec.CodecException;
import io.github.amisonnet8.sandbox.internal.codec.Decode;
import io.github.amisonnet8.sandbox.internal.codec.DecodedResponse;
import io.github.amisonnet8.sandbox.internal.codec.RequestBuilder;
import io.github.amisonnet8.sandbox.internal.codec.ResponseError;
import io.github.amisonnet8.sandbox.internal.transport.Transport;
import io.github.amisonnet8.sandbox.internal.transport.TransportException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Machinery shared by every transport: hello validation and the
 * serialized call/response cycle. {@link SanDbOxClient} and {@link
 * SocketClient} each wrap a {@code Session}; neither exposes it publicly.
 *
 * <p>The protocol has no request id, so responses can only be matched to
 * requests by strict ordering. A {@link ReentrantLock} around every call
 * is what Go's {@code sync.Mutex}/Python's {@code threading.Lock} do for
 * the same reason; the Rust driver instead gets this for free from the
 * borrow checker ({@code &mut self}), which Java has no equivalent of.
 */
final class Session {

    private final Transport transport;
    private final Hello hello;
    private final ReentrantLock lock = new ReentrantLock();
    private Duration timeout;
    private boolean closed;

    Session(Transport transport, Duration timeout) throws SanDbOxException {
        this.transport = transport;
        this.timeout = timeout;

        byte[] line;
        try {
            line = transport.readLine(timeout);
        } catch (TransportException e) {
            transport.close(SanDbOx.CLOSE_TIMEOUT);
            throw translate(e);
        }
        Hello h;
        try {
            h = Decode.hello(line);
        } catch (CodecException e) {
            transport.close(SanDbOx.CLOSE_TIMEOUT);
            throw new ProtocolViolationException("invalid hello line: " + e.getMessage(), e);
        }
        if (h.protocol() != SanDbOx.PROTOCOL) {
            transport.close(SanDbOx.CLOSE_TIMEOUT);
            throw new ProtocolViolationException(
                    "unsupported protocol " + h.protocol() + " (this driver speaks " + SanDbOx.PROTOCOL + ")");
        }
        this.hello = h;
    }

    Hello hello() {
        return hello;
    }

    void setTimeout(Duration t) {
        lock.lock();
        try {
            timeout = t;
        } finally {
            lock.unlock();
        }
    }

    Duration timeout() {
        lock.lock();
        try {
            return timeout;
        } finally {
            lock.unlock();
        }
    }

    QueryResult query(String sql, List<Object> params) throws SanDbOxException {
        DecodedResponse r = call(() -> new RequestBuilder("query").field("sql", sql).params(params));
        return decode(() -> Decode.query(r));
    }

    ExecResult exec(String sql, List<Object> params) throws SanDbOxException {
        DecodedResponse r = call(() -> new RequestBuilder("exec").field("sql", sql).params(params));
        return decode(() -> Decode.exec(r));
    }

    SnapshotResult snapshot(SnapshotOptions opts) throws SanDbOxException {
        DecodedResponse r = call(() -> {
            RequestBuilder b = new RequestBuilder("snapshot");
            if (opts != null) {
                b.field("filename", opts.filenameValue());
                b.field("sqlite", opts.sqliteValue());
                b.field("timestamp", opts.timestampValue());
            }
            return b;
        });
        return decode(() -> Decode.snapshot(r));
    }

    void load(String path) throws SanDbOxException {
        call(() -> new RequestBuilder("load").field("path", path));
    }

    InspectResult inspect() throws SanDbOxException {
        DecodedResponse r = call(() -> new RequestBuilder("inspect"));
        return decode(() -> Decode.inspect(r));
    }

    TablesResult tables() throws SanDbOxException {
        DecodedResponse r = call(() -> new RequestBuilder("tables"));
        return decode(() -> Decode.tables(r));
    }

    SchemaResult schema(String table) throws SanDbOxException {
        DecodedResponse r = call(() -> new RequestBuilder("schema").field("table", table));
        return decode(() -> Decode.schema(r));
    }

    DumpResult dump(String pattern) throws SanDbOxException {
        DecodedResponse r = call(() -> new RequestBuilder("dump").field("pattern", pattern));
        return decode(() -> Decode.dump(r));
    }

    /** Sends the {@code overwrite} op. Callers (only {@link SanDbOxClient}) must {@link #close()} afterward. */
    void overwrite() throws SanDbOxException {
        call(() -> new RequestBuilder("overwrite"));
    }

    /**
     * Gracefully ends the connection: sends the close op best-effort, then
     * follows through with the transport's own shutdown regardless of
     * whether a response arrives. Idempotent.
     */
    void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            boolean sent;
            try {
                transport.writeLine(new RequestBuilder("close").build());
                sent = true;
            } catch (TransportException e) {
                sent = false;
            }
            if (sent) {
                // Best-effort: read (and discard) the close op's own
                // response line before tearing the transport down.
                // Whether this succeeds, times out, or errors doesn't
                // change what happens next, but skipping this read would
                // leave the direct transport's background reader thread
                // trying to deliver that line through its 1-slot queue
                // with nothing left to ever call readLine() and drain it
                // (see ReaderThread.join's doc comment for what that
                // would cost).
                try {
                    transport.readLine(SanDbOx.CLOSE_TIMEOUT);
                } catch (TransportException ignored) {
                    // See above: the outcome of this read is irrelevant.
                }
            }
            transport.close(SanDbOx.CLOSE_TIMEOUT);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sends one request and returns its decoded field set (or throws the
     * response's own error, typed).
     *
     * <p>A timeout is the one failure this driver cannot recover from
     * gracefully: a single stuck read cannot be cancelled without risking
     * desynchronizing the line-based protocol, so on timeout this closes
     * the transport itself (bounded by {@link SanDbOx#CLOSE_TIMEOUT},
     * which the direct transport's staged shutdown -- stdin close,
     * SIGTERM, SIGKILL -- guarantees terminates even a hung child) and
     * marks the session permanently closed. Every other read/write error
     * is returned as-is without an explicit close: the transport's own
     * terminal-error memoization already makes it return the same error
     * on every subsequent call.
     *
     * <p>{@code builderSupplier} is invoked (and its own {@link
     * io.github.amisonnet8.sandbox.internal.codec.CodecException}, if
     * any -- a non-finite REAL param or an unsupported param type -- is
     * translated) inside this method's own try block, not by the caller
     * before calling this method: building the request is exactly where
     * param encoding happens, and an exception thrown while evaluating an
     * argument expression at the call site would never reach a catch
     * clause inside this method's body at all.
     */
    private DecodedResponse call(Supplier<RequestBuilder> builderSupplier) throws SanDbOxException {
        lock.lock();
        try {
            if (closed) {
                throw new ConnectionClosedException("connection is closed");
            }
            byte[] requestLine;
            try {
                requestLine = builderSupplier.get().build();
            } catch (CodecException e) {
                throw new ProtocolViolationException(e.getMessage(), e);
            }
            try {
                transport.writeLine(requestLine);
            } catch (TransportException e) {
                throw translate(e);
            }
            byte[] line;
            try {
                line = transport.readLine(timeout);
            } catch (TransportException e) {
                if (e.kind() == TransportException.Kind.TIMEOUT) {
                    closed = true;
                    transport.close(SanDbOx.CLOSE_TIMEOUT);
                }
                throw translate(e);
            }
            DecodedResponse resp;
            try {
                resp = Decode.responseLine(line);
            } catch (CodecException e) {
                throw new ProtocolViolationException("invalid response line: " + e.getMessage(), e);
            }
            if (resp.ok()) {
                return resp;
            }
            ResponseError err = Decode.error(resp);
            if (err != null) {
                throw new ResponseException(err.code(), err.message());
            }
            throw new ProtocolViolationException("response has ok=false with no error field");
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T decode();
    }

    private static <T> T decode(Decoder<T> d) throws ProtocolViolationException {
        try {
            return d.decode();
        } catch (CodecException e) {
            throw new ProtocolViolationException(e.getMessage(), e);
        }
    }

    /** Also used by {@link SanDbOx} to translate a connect-time transport failure (spawn/dial), before any {@code Session} exists. */
    static SanDbOxException translate(TransportException e) {
        return switch (e.kind()) {
            case TIMEOUT -> new ReadTimeoutException(e.getMessage(), e);
            case CLOSED -> new ConnectionClosedException(e.getMessage(), e);
            case PROTOCOL -> new ProtocolViolationException(e.getMessage(), e);
            case IO -> new SanDbOxException(e.getMessage(), e);
        };
    }
}
