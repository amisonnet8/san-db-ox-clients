package io.github.amisonnet8.sandbox;

/**
 * Base class for every checked exception this driver throws.
 *
 * <p>This mirrors {@code java.sql.SQLException}'s role: a single checked
 * exception type that callers can catch broadly, with subclasses that let
 * them narrow when they need to. It is checked (extends {@link Exception},
 * not {@link RuntimeException}) because a failed database call is an
 * expected outcome a caller should be forced to consider, the same
 * reasoning behind {@code SQLException}.
 */
public class SanDbOxException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The SanDBox protocol's error code for a SQLite-level error. */
    public static final String CODE_SQLITE_ERROR = "sqlite_error";

    /** The SanDBox protocol's error code for a malformed request. */
    public static final String CODE_BAD_REQUEST = "bad_request";

    /** The SanDBox protocol's error code for an I/O failure on the server side. */
    public static final String CODE_IO_ERROR = "io_error";

    /** The SanDBox protocol's error code for an op the server does not support. */
    public static final String CODE_UNSUPPORTED_OP = "unsupported_op";

    /** The SanDBox protocol's error code for a write attempted against a read-only server. */
    public static final String CODE_READ_ONLY = "read_only";

    SanDbOxException(String message) {
        super(message);
    }

    SanDbOxException(String message, Throwable cause) {
        super(message, cause);
    }
}
