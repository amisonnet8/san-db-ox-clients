package io.github.amisonnet8.sandbox.internal.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A TCP/UNIX socket, typically fronted by something like socat.
 *
 * <p>Unlike {@link DirectTransport}, this reads inline on the caller's own
 * thread: no background reader thread. {@link TimedByteSource} is what
 * gives a real per-read deadline regardless of which timeout mechanism the
 * underlying socket type actually has.
 *
 * <p>The TLS seam ({@code connectSocket}, on the public {@code SanDbOx}
 * facade) is why sockets are read inline rather than through a background
 * thread, mirroring the Rust driver's own reasoning: a synchronous TLS
 * stream generally cannot be split into independent read/write halves or
 * safely shared across threads, so a threaded reader would force the seam
 * into a shape that does not work for TLS. Unlike Rust, though, Java's
 * {@code SSLSocket extends Socket}, so this driver <em>can</em> set a read
 * timeout on a caller-supplied socket itself -- see {@link SocketSource}.
 */
public final class SocketTransport implements Transport {

    private final TimedByteSource source;
    private final LineFramer framer = new LineFramer();
    private TransportException terminal;
    private boolean closed;

    private SocketTransport(TimedByteSource source) {
        this.source = source;
    }

    /** Wraps an already-connected socket (including a TLS socket): the caller is responsible for {@code setSoTimeout}. */
    public static SocketTransport fromSocket(Socket socket) {
        try {
            return new SocketTransport(new SocketSource(socket));
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.IO, "wrapping socket: " + e.getMessage(), e);
        }
    }

    /** {@code connectTimeout == null} means wait forever for the connection to establish. */
    public static SocketTransport connectTcp(String host, int port, Duration connectTimeout) {
        Socket socket = new Socket();
        try {
            if (connectTimeout == null) {
                socket.connect(new InetSocketAddress(host, port));
            } else {
                socket.connect(new InetSocketAddress(host, port), (int) Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE));
            }
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.IO, "connecting to " + host + ":" + port + ": " + e.getMessage(), e);
        }
        return fromSocket(socket);
    }

    public static SocketTransport connectUnix(Path path) {
        try {
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(path));
            return new SocketTransport(new ChannelSource(channel));
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.IO, "connecting to " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void writeLine(byte[] line) {
        if (closed) {
            throw new TransportException(TransportException.Kind.CLOSED, "connection is closed");
        }
        try {
            source.write(line);
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.IO, "writing request: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] readLine(Duration timeout) {
        if (terminal != null) {
            throw terminal;
        }
        byte[] line = tryTakeLine();
        if (line != null) {
            return line;
        }

        Long deadlineNanos = (timeout == null) ? null : System.nanoTime() + timeout.toNanos();
        byte[] buf = new byte[64 * 1024];
        while (true) {
            Duration remaining =
                    (deadlineNanos == null) ? null : Duration.ofNanos(Math.max(deadlineNanos - System.nanoTime(), 0));
            int n;
            try {
                n = source.read(buf, remaining);
            } catch (IOException e) {
                TransportException te = new TransportException(TransportException.Kind.IO, e.getMessage(), e);
                terminal = te;
                throw te;
            }
            if (n == TimedByteSource.TIMED_OUT) {
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                    // Not memoized as terminal: this aborts only this call.
                    // The framer's partial line (if any) survives in place
                    // for the next readLine() to keep filling.
                    throw new TransportException(TransportException.Kind.TIMEOUT, "timed out waiting for a response line");
                }
                continue;
            }
            if (n == TimedByteSource.EOF) {
                TransportException te = new TransportException(TransportException.Kind.CLOSED, "connection closed (EOF)");
                terminal = te;
                throw te;
            }
            framer.push(buf, 0, n);
            byte[] taken = tryTakeLine();
            if (taken != null) {
                return taken;
            }
        }
    }

    private byte[] tryTakeLine() {
        try {
            return framer.takeLine();
        } catch (TransportException e) {
            terminal = e;
            throw e;
        }
    }

    /**
     * No staged escalation here, unlike {@link DirectTransport}: there is
     * no child process to signal or reap. Session's own call-serializing
     * lock already makes "a pending read concurrent with close"
     * unrepresentable, so marking the transport terminal and releasing the
     * socket is enough.
     */
    @Override
    public void close(Duration timeout) {
        if (closed) {
            return;
        }
        closed = true;
        terminal = new TransportException(TransportException.Kind.CLOSED, "connection is closed");
        source.close();
    }
}
