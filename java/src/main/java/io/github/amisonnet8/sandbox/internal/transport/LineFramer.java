package io.github.amisonnet8.sandbox.internal.transport;

import java.util.Arrays;

/**
 * A pure byte to line state machine, shared by both transports. Splits
 * only on {@code '\n'} (a lone {@code '\r'} never ends a line), and never
 * re-scans bytes it has already scanned for a newline, so a large
 * multi-chunk response (e.g. a 1 MiB {@code dump} result) does not become
 * O(n^2).
 */
final class LineFramer {

    /** Sized for upstream's stated 1 MiB line limit, with headroom above it. */
    static final int MAX_LINE_BYTES = 2 * 1024 * 1024;

    private byte[] buf = new byte[256];
    private int len;
    /** How much of {@code buf[0, len)}, from the front, has already been scanned for a {@code '\n'} and found none. */
    private int scanned;

    void push(byte[] chunk, int off, int chunkLen) {
        ensureCapacity(len + chunkLen);
        System.arraycopy(chunk, off, buf, len, chunkLen);
        len += chunkLen;
    }

    void push(byte[] chunk) {
        push(chunk, 0, chunk.length);
    }

    private void ensureCapacity(int needed) {
        if (needed <= buf.length) {
            return;
        }
        int newCap = buf.length;
        while (newCap < needed) {
            newCap *= 2;
        }
        buf = Arrays.copyOf(buf, newCap);
    }

    /**
     * Pops one complete line (newline stripped), if the buffer holds one.
     * {@code null} means "keep reading"; throws {@link TransportException}
     * (kind {@link TransportException.Kind#PROTOCOL}) if the still-open
     * line has exceeded {@link #MAX_LINE_BYTES} without a newline -- a
     * terminal condition the caller must not call {@code push}/{@code
     * takeLine} again after.
     */
    byte[] takeLine() {
        for (int i = scanned; i < len; i++) {
            if (buf[i] == '\n') {
                byte[] line = Arrays.copyOfRange(buf, 0, i);
                int remaining = len - (i + 1);
                System.arraycopy(buf, i + 1, buf, 0, remaining);
                len = remaining;
                scanned = 0;
                return line;
            }
        }
        scanned = len;
        if (len > MAX_LINE_BYTES) {
            throw new TransportException(
                    TransportException.Kind.PROTOCOL,
                    "response line exceeds " + MAX_LINE_BYTES + " bytes without a newline");
        }
        return null;
    }
}
