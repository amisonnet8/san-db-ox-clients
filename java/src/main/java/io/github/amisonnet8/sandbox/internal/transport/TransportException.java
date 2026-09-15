package io.github.amisonnet8.sandbox.internal.transport;

/**
 * Thrown for any transport-level failure. Unchecked, matching {@code
 * internal.codec.CodecException}: neither internal layer throws checked
 * exceptions, so {@code Session} (the one public boundary) is the only
 * place that translates failures into this library's checked {@code
 * SanDbOxException} family, keyed off {@link #kind()}.
 */
public final class TransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Which of this library's checked exception types this should become at the {@code Session} boundary. */
    public enum Kind {
        /** No response arrived within the connection's configured timeout. */
        TIMEOUT,
        /** The connection is closed: EOF, a prior terminal failure, or an explicit close. */
        CLOSED,
        /** A line exceeded the maximum size without a newline. */
        PROTOCOL,
        /** Any other I/O failure: a broken pipe, a failed spawn, and the like. */
        IO
    }

    private final Kind kind;

    public TransportException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public TransportException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
