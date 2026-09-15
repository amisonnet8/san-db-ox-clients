package io.github.amisonnet8.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * A connection to a running SanDBox process, either direct-connect ({@link
 * SanDbOxClient}) or over a socket ({@link SocketClient}).
 *
 * <p>Every method here corresponds to one op in the stdio protocol; the op
 * names' word stems are kept unchanged across every language this project
 * provides a driver for (only the casing convention changes), so {@code
 * query}/{@code exec}/{@code snapshot}/{@code load}/{@code inspect}/{@code
 * tables}/{@code schema}/{@code dump}/{@code close} map directly.
 *
 * <p>A single connection is not safe for concurrent use from multiple
 * threads: calls are serialized internally with a lock, so they will not
 * corrupt each other, but interleaving them (for example, one thread
 * calling {@link #setTimeout} while another is blocked in {@link #query})
 * produces no meaningful ordering guarantee. Confine a connection to one
 * thread, or add your own external synchronization.
 */
public interface Connection extends AutoCloseable {

    /** The greeting this connection received when it was opened. */
    Hello hello();

    /** Runs a SELECT and returns every row. {@code params} may be empty but not {@code null}. */
    QueryResult query(String sql, List<Object> params) throws SanDbOxException;

    /** Runs an INSERT/UPDATE/DELETE/DDL statement. {@code params} may be empty but not {@code null}. */
    ExecResult exec(String sql, List<Object> params) throws SanDbOxException;

    /** Writes a snapshot of the current data. {@code opts} may be {@code null} for server defaults. */
    SnapshotResult snapshot(SnapshotOptions opts) throws SanDbOxException;

    /** Replaces the running process's data with the file at {@code path}. */
    void load(String path) throws SanDbOxException;

    /** Reports on the running process's own embedded data. */
    InspectResult inspect() throws SanDbOxException;

    /** Lists the tables in the current database. */
    TablesResult tables() throws SanDbOxException;

    /** Returns CREATE statements. {@code table} may be {@code null} for every table. */
    SchemaResult schema(String table) throws SanDbOxException;

    /** Returns a SQL dump. {@code pattern} may be {@code null} for the server's default ({@code "%"}). */
    DumpResult dump(String pattern) throws SanDbOxException;

    /**
     * Sets the timeout applied to every subsequent call on this
     * connection. {@code null} means wait forever.
     */
    void setTimeout(Duration timeout);

    /** The timeout currently applied to calls on this connection. */
    Duration timeout();

    /**
     * Closes the connection. Best-effort and idempotent: this does not
     * throw, matching {@link AutoCloseable}'s contract that a {@code
     * close()} used with try-with-resources should not risk suppressing
     * the exception that triggered the resource cleanup.
     */
    @Override
    void close();
}
