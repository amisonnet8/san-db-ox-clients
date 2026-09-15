package io.github.amisonnet8.sandbox;

/**
 * Thrown when the server sends something that does not conform to the
 * stdio protocol: malformed JSON, a hello line with an unsupported
 * {@code protocol} number, an integer token out of {@code long} range, a
 * non-finite REAL, or a BLOB array whose length is not exactly one.
 */
public final class ProtocolViolationException extends SanDbOxException {

    private static final long serialVersionUID = 1L;

    ProtocolViolationException(String message) {
        super(message);
    }

    ProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
