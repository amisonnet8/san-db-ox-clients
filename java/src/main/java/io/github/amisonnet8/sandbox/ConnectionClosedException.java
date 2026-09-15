package io.github.amisonnet8.sandbox;

/**
 * Thrown by any call made after the connection has closed, whether by an
 * explicit {@link Connection#close()}, a prior {@link ReadTimeoutException},
 * or the server process exiting.
 */
public final class ConnectionClosedException extends SanDbOxException {

    private static final long serialVersionUID = 1L;

    ConnectionClosedException(String message) {
        super(message);
    }

    ConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
