package io.github.amisonnet8.sandbox;

/**
 * {@code inspect}'s result. It describes the running process's own
 * embedded data, not the live SQL state: {@code CREATE TABLE}/{@code
 * INSERT}/{@code load} against a running process do not change what a
 * later {@code inspect} reports. {@code version} and {@code dataLength}
 * are {@code null} when the process has no embedded snapshot data.
 */
public record InspectResult(
        boolean hasData, Long version, Long dataLength, String source, boolean readOnly) {
}
