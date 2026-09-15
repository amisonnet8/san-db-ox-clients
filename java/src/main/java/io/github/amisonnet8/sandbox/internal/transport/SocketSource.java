package io.github.amisonnet8.sandbox.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * Wraps a {@link Socket} (plain TCP, or anything the caller connects
 * itself including a TLS socket, since {@code SSLSocket extends Socket}).
 * {@code setSoTimeout} gives this driver a real per-read deadline; unlike
 * the Rust driver, which could not set this on an arbitrary caller-owned
 * {@code Read + Write}, this <em>can</em> set it here even on a stream the
 * caller passed to {@code connectSocket}, because {@code Socket} always
 * exposes it.
 */
final class SocketSource implements TimedByteSource {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    SocketSource(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    @Override
    public int read(byte[] buf, Duration remaining) throws IOException {
        // Socket.setSoTimeout(0) means "wait forever"; a remaining
        // duration that has already rounded down to zero milliseconds is
        // instead given a minimal 1ms wait, so it reads as "try once,
        // essentially immediately" rather than accidentally blocking
        // forever.
        int timeoutMs;
        if (remaining == null) {
            timeoutMs = 0;
        } else {
            long ms = remaining.toMillis();
            timeoutMs = (int) Math.min(Math.max(ms, 1), Integer.MAX_VALUE);
        }
        socket.setSoTimeout(timeoutMs);
        try {
            return in.read(buf);
        } catch (SocketTimeoutException e) {
            return TIMED_OUT;
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort: the socket is being torn down either way.
        }
    }
}
