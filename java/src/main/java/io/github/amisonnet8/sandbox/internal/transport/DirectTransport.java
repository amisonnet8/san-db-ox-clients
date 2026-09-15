package io.github.amisonnet8.sandbox.internal.transport;

import io.github.amisonnet8.sandbox.StderrSink;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A child process, talked to over its stdin/stdout.
 *
 * <p>Three of the problems this repository's Rust driver had to solve by
 * hand (no {@code SIGTERM} in {@code std}, no bounded {@code
 * Child::wait()}, a hand-declared {@code kill(2)} FFI symbol) simply do
 * not exist here: {@link Process#destroy()} <em>is</em> {@code SIGTERM} on
 * Unix, {@link Process#waitFor(long, TimeUnit)} is bounded natively, and
 * {@link Process#destroyForcibly()} is {@code SIGKILL}.
 *
 * <p>{@link Process#exitValue()} on OpenJDK's POSIX implementation already
 * reports a signal death as {@code 128 + signum} (matching the shell's
 * {@code $?} convention), so unlike the other four drivers in this
 * repository, no manual signal-to-exit-code translation is needed here at
 * all -- see the {@code exitCode()} javadoc on the public {@code
 * SanDbOxClient} for why this convention was kept as-is rather than
 * translated to match the other drivers' {@code -signum}.
 */
public final class DirectTransport implements Transport {

    private final Process process;
    private OutputStream stdin;
    private ReaderThread reader;
    private Thread stderrThread;
    private Integer exitCode;
    private boolean closed;

    private DirectTransport(Process process, OutputStream stdin, ReaderThread reader, Thread stderrThread) {
        this.process = process;
        this.stdin = stdin;
        this.reader = reader;
        this.stderrThread = stderrThread;
    }

    /**
     * {@code env == null} means inherit this JVM's own environment
     * ({@link ProcessBuilder}'s own default); {@code env != null}
     * <em>replaces</em> it entirely rather than merging, matching the
     * other drivers in this repository. {@code cwd == null} means inherit
     * this JVM's own working directory.
     */
    public static DirectTransport start(String command, List<String> args, Map<String, String> env, String cwd, StderrSink stderr) {
        List<String> full = new ArrayList<>(args.size() + 1);
        full.add(command);
        full.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        switch (stderr.kind()) {
            case NULL -> pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            case INHERIT -> pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            case WRITER -> pb.redirectError(ProcessBuilder.Redirect.PIPE);
        }
        if (env != null) {
            pb.environment().clear();
            pb.environment().putAll(env);
        }
        if (cwd != null) {
            pb.directory(new File(cwd));
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.IO, "starting " + command + ": " + e.getMessage(), e);
        }

        OutputStream stdin = process.getOutputStream();
        ReaderThread reader = new ReaderThread(process.getInputStream());

        Thread stderrThread = null;
        if (stderr.kind() == StderrSink.Kind.WRITER) {
            InputStream childErr = process.getErrorStream();
            OutputStream sink = stderr.writerStream();
            stderrThread = new Thread(() -> drainStderr(childErr, sink), "san-db-ox-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();
        }

        return new DirectTransport(process, stdin, reader, stderrThread);
    }

    private static void drainStderr(InputStream in, OutputStream out) {
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // Best-effort drain: the pipe closing (child exit) or the
            // caller's sink failing both end up here, and neither is
            // something this thread can recover from.
        }
    }

    /**
     * The child's exit code, once known ({@code null} while still
     * running). See the class javadoc: a signal death is reported as
     * {@code 128 + signum}, the JVM's own convention, kept as-is.
     */
    public Integer exitCode() {
        return exitCode;
    }

    private Integer waitBounded(Duration timeout) {
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private int waitForever() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return process.waitFor();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void writeLine(byte[] line) {
        if (stdin == null) {
            throw new TransportException(TransportException.Kind.CLOSED, "connection is closed");
        }
        try {
            // protocol.md: flush after every request. Piped stdin is
            // buffered, so this is required, not optional.
            stdin.write(line);
            stdin.flush();
        } catch (IOException e) {
            throw new TransportException(TransportException.Kind.IO, "writing request: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] readLine(Duration timeout) {
        if (reader == null) {
            throw new TransportException(TransportException.Kind.CLOSED, "connection is closed");
        }
        return reader.readLine(timeout);
    }

    /**
     * Staged shutdown, matching protocol.md: close stdin, wait, {@code
     * SIGTERM}, wait, {@code SIGKILL}. Idempotent: calling this more than
     * once is safe and a no-op after the first call actually tears
     * anything down.
     */
    @Override
    public void close(Duration timeout) {
        if (closed) {
            return;
        }
        closed = true;

        if (stdin != null) {
            try {
                stdin.close(); // stage 1
            } catch (IOException ignored) {
                // A broken pipe on close is not an error worth surfacing:
                // the process is being torn down either way.
            }
            stdin = null;
        }

        Integer status = waitBounded(timeout);

        if (status == null) {
            process.destroy(); // stage 2: SIGTERM on Unix
            status = waitBounded(timeout);
        }

        if (status == null) {
            process.destroyForcibly(); // stage 3: SIGKILL, cannot be ignored
            status = waitForever();
        }

        exitCode = status;

        if (stderrThread != null) {
            try {
                stderrThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stderrThread = null;
        }
        if (reader != null) {
            reader.join(timeout);
            reader = null;
        }
    }
}
