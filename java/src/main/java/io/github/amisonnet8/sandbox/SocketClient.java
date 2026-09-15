package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.transport.SocketTransport;

import java.time.Duration;
import java.util.List;

/**
 * A connection over a TCP/UNIX socket, typically fronted by something
 * like socat. Has neither {@code overwrite} nor {@code exitCode}: neither
 * means anything without a child process this driver itself started (see
 * {@link SanDbOxClient}).
 */
public final class SocketClient implements Connection {

    private final Session session;

    SocketClient(SocketTransport transport, Duration timeout) throws SanDbOxException {
        this.session = new Session(transport, timeout);
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
