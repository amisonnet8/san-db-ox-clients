package io.github.amisonnet8.sandbox.internal.transport;

import io.github.amisonnet8.sandbox.StderrSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses {@code cat}/{@code sh} directly (no san-db-ox binary needed), same
 * as this repository's Rust driver. POSIX-only, matching the Rust
 * driver's {@code #[cfg(all(test, unix))]}: {@code DirectTransport}'s
 * signal-based escalation is inherently Unix-specific.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class DirectTransportTest {

    private static DirectTransport start(String command, String... args) {
        return DirectTransport.start(command, List.of(args), null, null, StderrSink.NULL);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void write_read_echo_via_cat() {
        DirectTransport t = start("cat");
        t.writeLine(b("hello\n"));
        byte[] line = t.readLine(Duration.ofSeconds(5));
        assertEquals("hello", new String(line, StandardCharsets.UTF_8));
        t.close(Duration.ofSeconds(5));
    }

    @Test
    void stderr_flood_does_not_deadlock() {
        // Bounded generator (20000 lines), not an infinite one: an
        // unbounded generator in a test pipeline has previously pinned
        // CPU in this repository's other drivers.
        DirectTransport t = DirectTransport.start(
                "sh",
                List.of("-c", "i=0; while [ $i -lt 20000 ]; do echo \"warning $i\" >&2; i=$((i+1)); done; cat"),
                null,
                null,
                StderrSink.NULL);
        t.writeLine(b("still alive\n"));
        byte[] line = t.readLine(Duration.ofSeconds(10));
        assertEquals("still alive", new String(line, StandardCharsets.UTF_8));
        t.close(Duration.ofSeconds(5));
    }

    @Test
    void stderr_drained_to_provided_sink() {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        OutputStream sink = new OutputStream() {
            @Override
            public synchronized void write(int b) {
                collected.write(b);
            }

            @Override
            public synchronized void write(byte[] bytes, int off, int len) {
                collected.write(bytes, off, len);
            }
        };
        DirectTransport t = DirectTransport.start(
                "sh", List.of("-c", "echo one >&2; echo two >&2; cat"), null, null, StderrSink.writer(sink));
        t.writeLine(b("x\n"));
        t.readLine(Duration.ofSeconds(5));
        t.close(Duration.ofSeconds(5));
        assertEquals("one\ntwo\n", collected.toString(StandardCharsets.UTF_8));
    }

    @Test
    void close_via_stdin_reaps_promptly() {
        DirectTransport t = start("cat");
        Instant startTime = Instant.now();
        t.close(Duration.ofSeconds(5));
        assertTrue(Duration.between(startTime, Instant.now()).compareTo(Duration.ofSeconds(1)) < 0);
        assertEquals(0, t.exitCode());
    }

    @Test
    void close_escalates_to_sigterm() {
        DirectTransport t = start("sh", "-c", "while true; do sleep 0.05; done");
        Instant startTime = Instant.now();
        t.close(Duration.ofMillis(200));
        Duration elapsed = Duration.between(startTime, Instant.now());
        assertTrue(elapsed.compareTo(Duration.ofMillis(150)) >= 0, "closed too fast: " + elapsed);
        assertTrue(elapsed.compareTo(Duration.ofSeconds(2)) < 0, "closed too slow: " + elapsed);
        // 128 + SIGTERM(15), the JVM's own convention (see DirectTransport's class javadoc).
        assertEquals(143, t.exitCode());
    }

    @Test
    void close_escalates_to_sigkill() {
        DirectTransport t = start("sh", "-c", "trap '' TERM; while true; do sleep 0.05; done");
        t.close(Duration.ofMillis(200));
        // 128 + SIGKILL(9).
        assertEquals(137, t.exitCode());
    }

    @Test
    void double_close_is_safe() {
        DirectTransport t = start("cat");
        t.close(Duration.ofSeconds(5));
        t.close(Duration.ofSeconds(5)); // must not throw or hang
    }

    @Test
    void read_line_timeout_does_not_hang() {
        DirectTransport t = start("cat");
        TransportException e = assertThrows(TransportException.class, () -> t.readLine(Duration.ofMillis(100)));
        assertEquals(TransportException.Kind.TIMEOUT, e.kind());
        t.close(Duration.ofSeconds(5));
    }

    @Test
    void nonexistent_command_surfaces_the_spawn_error() {
        TransportException e = assertThrows(
                TransportException.class,
                () -> DirectTransport.start("san-db-ox-definitely-does-not-exist", List.of(), null, null, StderrSink.NULL));
        assertEquals(TransportException.Kind.IO, e.kind());
    }
}
