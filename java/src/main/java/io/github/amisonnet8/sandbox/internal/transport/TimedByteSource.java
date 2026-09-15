package io.github.amisonnet8.sandbox.internal.transport;

import java.io.IOException;
import java.time.Duration;

/**
 * The one abstraction {@link SocketTransport} needs over a byte stream
 * with a per-read deadline. Two implementations exist because Java has
 * two unrelated timeout mechanisms for the two kinds of socket this
 * driver connects itself: {@link SocketSource} wraps {@link
 * java.net.Socket#setSoTimeout}, used for TCP and for any stream the
 * caller hands to {@code connectSocket} (including TLS, since {@code
 * javax.net.ssl.SSLSocket} is a {@code java.net.Socket} subclass);
 * {@link ChannelSource} wraps a non-blocking {@link
 * java.nio.channels.SocketChannel} plus a {@link java.nio.channels.Selector},
 * used for UNIX domain sockets, which have no {@code SO_TIMEOUT}
 * equivalent at all.
 */
interface TimedByteSource {

    int EOF = -1;
    int TIMED_OUT = -2;

    /** {@code remaining == null} means wait forever. Returns a byte count &gt; 0, {@link #EOF}, or {@link #TIMED_OUT}. */
    int read(byte[] buf, Duration remaining) throws IOException;

    void write(byte[] data) throws IOException;

    void close();
}
