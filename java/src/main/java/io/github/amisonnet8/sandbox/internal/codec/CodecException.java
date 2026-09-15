package io.github.amisonnet8.sandbox.internal.codec;

/**
 * Thrown for any wire-format violation: malformed JSON, an integer token
 * out of {@code long} range, a BLOB array whose length is not exactly one,
 * a non-finite REAL, and the like. Unchecked so it does not have to be
 * threaded through the parser's recursive descent; {@code Session}
 * (outside this package) catches it and wraps it in the public, checked
 * {@code ProtocolViolationException}.
 */
public final class CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    CodecException(String message) {
        super(message);
    }

    CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
