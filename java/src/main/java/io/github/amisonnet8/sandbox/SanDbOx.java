package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.transport.DirectTransport;
import io.github.amisonnet8.sandbox.internal.transport.SocketTransport;
import io.github.amisonnet8.sandbox.internal.transport.TransportException;

import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Entry point for connecting to a running {@code san-db-ox --serve-stdio}
 * process, either as a child process (direct-connect) or over a TCP/UNIX
 * socket exposed by something like socat.
 */
public final class SanDbOx {

    /** The stdio protocol version this driver speaks. */
    public static final int PROTOCOL = 1;

    /**
     * This library's own version. Read from the packaged jar's manifest
     * at runtime, so it is only meaningful once built into a jar; under
     * {@code mvn test} (classes are not yet packaged) this falls back to
     * {@code "0.0.0-dev"}.
     */
    public static final String VERSION;

    static {
        String v = SanDbOx.class.getPackage().getImplementationVersion();
        VERSION = (v != null) ? v : "0.0.0-dev";
    }

    /** The default timeout applied to a new connection's calls. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** The bound placed on {@code close()}'s own shutdown steps. */
    public static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private SanDbOx() {
    }

    /** Equivalent to {@link #connect(String, List, ConnectOptions)} with default options. */
    public static SanDbOxClient connect(String command, List<String> args) throws SanDbOxException {
        return connect(command, args, new ConnectOptions());
    }

    /**
     * Launches {@code command} (with {@code args}) as a child process and
     * connects to it over its stdin/stdout. This takes the command to
     * launch, not a fixed "local" assumption, so the same call works over
     * SSH, through Docker, or via {@code kubectl exec} just by changing
     * the command and args -- see {@code docs/usage/connecting.md} for
     * worked examples of each.
     */
    public static SanDbOxClient connect(String command, List<String> args, ConnectOptions opts) throws SanDbOxException {
        DirectTransport transport;
        try {
            transport = DirectTransport.start(command, args, opts.env(), opts.cwd(), opts.stderr());
        } catch (TransportException e) {
            throw Session.translate(e);
        }
        return new SanDbOxClient(transport, opts.timeout());
    }

    /** Equivalent to {@link #connectTcp(String, int, SocketOptions)} with default options. */
    public static SocketClient connectTcp(String host, int port) throws SanDbOxException {
        return connectTcp(host, port, new SocketOptions());
    }

    /** Connects to a TCP endpoint, typically exposed by socat. */
    public static SocketClient connectTcp(String host, int port, SocketOptions opts) throws SanDbOxException {
        SocketTransport transport;
        try {
            transport = SocketTransport.connectTcp(host, port, opts.timeout());
        } catch (TransportException e) {
            throw Session.translate(e);
        }
        return new SocketClient(transport, opts.timeout());
    }

    /** Equivalent to {@link #connectUnix(Path, SocketOptions)} with default options. */
    public static SocketClient connectUnix(Path path) throws SanDbOxException {
        return connectUnix(path, new SocketOptions());
    }

    /** Connects to a UNIX domain socket endpoint, typically exposed by socat. */
    public static SocketClient connectUnix(Path path, SocketOptions opts) throws SanDbOxException {
        SocketTransport transport;
        try {
            transport = SocketTransport.connectUnix(path);
        } catch (TransportException e) {
            throw Session.translate(e);
        }
        return new SocketClient(transport, opts.timeout());
    }

    /**
     * Wraps an already-connected socket, including a TLS socket ({@code
     * javax.net.ssl.SSLSocket} is a {@code java.net.Socket}), as a
     * connection. This crate depends on no TLS library and has no
     * TLS-specific constructor: bring your own {@code SSLContext}/{@code
     * SSLSocketFactory}, connect it, and hand the resulting socket here.
     *
     * <p>Unlike this driver's Rust counterpart, which cannot set a read
     * timeout on an arbitrary caller-supplied stream itself, this
     * <em>does</em> call {@link Socket#setSoTimeout} on {@code socket} as
     * calls are made (any pre-existing timeout you set is overwritten):
     * {@code Socket} always exposes it, TLS sockets included.
     */
    public static SocketClient connectSocket(Socket socket, SocketOptions opts) throws SanDbOxException {
        SocketTransport transport;
        try {
            transport = SocketTransport.fromSocket(socket);
        } catch (TransportException e) {
            throw Session.translate(e);
        }
        return new SocketClient(transport, opts.timeout());
    }
}
