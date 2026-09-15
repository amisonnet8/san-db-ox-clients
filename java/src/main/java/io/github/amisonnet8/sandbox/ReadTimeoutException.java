package io.github.amisonnet8.sandbox;

/**
 * Thrown when a call does not receive a response within the connection's
 * configured timeout (see {@link Connection#setTimeout(java.time.Duration)}).
 *
 * <p>There is no way to interrupt a single in-flight read without risking
 * desynchronizing the line-based protocol, so a timeout renders the whole
 * connection unusable: every subsequent call throws
 * {@link ConnectionClosedException} instead of retrying.
 */
public final class ReadTimeoutException extends SanDbOxException {

    private static final long serialVersionUID = 1L;

    ReadTimeoutException(String message) {
        super(message);
    }

    ReadTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
