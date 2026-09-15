package io.github.amisonnet8.sandbox;

/** {@code exec}'s result. */
public record ExecResult(long rowsAffected, long lastInsertId) {
}
