package io.github.amisonnet8.sandbox;

/**
 * Thrown when the server returns {@code "ok":false} for a request.
 *
 * <p>Branch on {@link #code()} (one of the {@code CODE_*} constants on
 * {@link SanDbOxException}), never on {@link #getMessage()}: the message
 * text is not part of the protocol's contract and may change, but the code
 * is.
 */
public final class ResponseException extends SanDbOxException {

    private static final long serialVersionUID = 1L;

    private final String code;

    ResponseException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The protocol error code, e.g. {@link SanDbOxException#CODE_SQLITE_ERROR}. */
    public String code() {
        return code;
    }

    /** Equivalent to {@code code().equals(candidate)}. */
    public boolean isCode(String candidate) {
        return code.equals(candidate);
    }
}
