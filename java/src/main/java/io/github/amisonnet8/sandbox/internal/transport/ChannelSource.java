package io.github.amisonnet8.sandbox.internal.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.time.Duration;

/**
 * Wraps a UNIX-domain {@link SocketChannel}. There is no {@code
 * SO_TIMEOUT} for this socket type in Java, so a per-read deadline is
 * implemented by hand: the channel is put in non-blocking mode and a
 * {@link Selector} is used to wait up to the remaining duration for
 * readability before attempting the read.
 */
final class ChannelSource implements TimedByteSource {

    private final SocketChannel channel;
    private final Selector selector;
    private final SelectionKey key;

    ChannelSource(SocketChannel channel) throws IOException {
        this.channel = channel;
        channel.configureBlocking(false);
        this.selector = Selector.open();
        this.key = channel.register(selector, SelectionKey.OP_READ);
    }

    @Override
    public int read(byte[] buf, Duration remaining) throws IOException {
        // Selector.select(0) means "block forever" (its own convention,
        // matching Socket.setSoTimeout(0)); a remaining duration already
        // rounded down to zero is bumped to 1ms for the same reason as
        // SocketSource.
        long ms;
        if (remaining == null) {
            ms = 0;
        } else {
            ms = Math.max(remaining.toMillis(), 1);
        }
        key.interestOps(SelectionKey.OP_READ);
        selector.selectedKeys().clear();
        int ready = selector.select(ms);
        if (ready == 0) {
            return TIMED_OUT;
        }
        selector.selectedKeys().clear();
        ByteBuffer bb = ByteBuffer.wrap(buf);
        return channel.read(bb);
    }

    @Override
    public void write(byte[] data) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data);
        while (bb.hasRemaining()) {
            int n = channel.write(bb);
            if (n == 0) {
                // The OS send buffer is full (unlikely for this protocol's
                // small request lines, but must not be dropped silently):
                // wait for writability instead of busy-spinning.
                key.interestOps(SelectionKey.OP_WRITE);
                selector.selectedKeys().clear();
                selector.select();
            }
        }
    }

    @Override
    public void close() {
        try {
            selector.close();
        } catch (IOException ignored) {
            // Best-effort: the channel is being torn down either way.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Same as above.
        }
    }
}
