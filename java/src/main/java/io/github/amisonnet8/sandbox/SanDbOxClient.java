package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.transport.DirectTransport;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

/**
 * A direct-connect connection: a child process, talked to over its
 * stdin/stdout. Not safe for concurrent use by multiple threads without
 * external synchronization (see {@link Connection}'s javadoc); a {@link
 * java.util.concurrent.locks.ReentrantLock} inside {@link Session}
 * prevents corruption but does not give calls from different threads any
 * meaningful ordering.
 */
public final class SanDbOxClient implements Connection {

    private final Session session;
    private final DirectTransport transport;

    SanDbOxClient(DirectTransport transport, Duration timeout) throws SanDbOxException {
        this.transport = transport;
        this.session = new Session(transport, timeout);
    }

    /**
     * Replaces the running process's own executable with one embedding
     * the current database, then exits. This only makes sense over a
     * direct connection: a socat-fronted socket can have several clients
     * connect through the same listener, and multiple processes writing
     * the same executable path at once is exactly what this op must
     * avoid, so it lives here rather than on {@link Connection}, and
     * {@link SocketClient} has no {@code overwrite} method to call.
     */
    public void overwrite() throws SanDbOxException {
        session.overwrite();
        // A successful overwrite ends the connection from the server's
        // side; reap the child so no zombie is left behind.
        session.close();
    }

    /**
     * The child process's exit code, once known ({@link OptionalInt#empty()}
     * while still running or before {@link #close()}/{@link #overwrite()}
     * has returned). Only available over a direct connection: {@link
     * SocketClient} has no child process to report on.
     *
     * <p>A signal death is reported as {@code 128 + signum}, matching the
     * JVM's own {@link Process#exitValue()} convention on POSIX (and the
     * shell's {@code $?}) -- kept as-is rather than translated to the
     * {@code -signum} convention this repository's other drivers use, so
     * this never disagrees with a {@code Process} the caller might also be
     * observing directly.
     */
    public OptionalInt exitCode() {
        Integer code = transport.exitCode();
        return (code == null) ? OptionalInt.empty() : OptionalInt.of(code);
    }

    @Override
    public Hello hello() {
        return session.hello();
    }

    @Override
    public QueryResult query(String sql, List<Object> params) throws SanDbOxException {
        return session.query(sql, params);
    }

    @Override
    public ExecResult exec(String sql, List<Object> params) throws SanDbOxException {
        return session.exec(sql, params);
    }

    @Override
    public SnapshotResult snapshot(SnapshotOptions opts) throws SanDbOxException {
        return session.snapshot(opts);
    }

    @Override
    public void load(String path) throws SanDbOxException {
        session.load(path);
    }

    @Override
    public InspectResult inspect() throws SanDbOxException {
        return session.inspect();
    }

    @Override
    public TablesResult tables() throws SanDbOxException {
        return session.tables();
    }

    @Override
    public SchemaResult schema(String table) throws SanDbOxException {
        return session.schema(table);
    }

    @Override
    public DumpResult dump(String pattern) throws SanDbOxException {
        return session.dump(pattern);
    }

    @Override
    public void setTimeout(Duration timeout) {
        session.setTimeout(timeout);
    }

    @Override
    public Duration timeout() {
        return session.timeout();
    }

    @Override
    public void close() {
        session.close();
    }
}
