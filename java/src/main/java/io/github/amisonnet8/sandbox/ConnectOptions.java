package io.github.amisonnet8.sandbox;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional settings for {@link SanDbOx#connect(String, java.util.List,
 * ConnectOptions)}. A fluent, mutating builder (each setter returns {@code
 * this}) rather than an immutable value type: unlike the Rust driver,
 * ownership does not force an immutable style here, and this is the
 * conventional shape for a Java options builder.
 */
public final class ConnectOptions {

    private Map<String, String> env;
    private String cwd;
    private StderrSink stderr = StderrSink.NULL;
    private Duration timeout = SanDbOx.DEFAULT_TIMEOUT;

    public ConnectOptions() {
    }

    /** Extra environment variables for the child process, added to (not replacing) this JVM's own. */
    public ConnectOptions env(Map<String, String> vars) {
        this.env = new LinkedHashMap<>(vars);
        return this;
    }

    /** The child process's working directory. Defaults to this JVM's own. */
    public ConnectOptions cwd(String dir) {
        this.cwd = dir;
        return this;
    }

    /** Where the child process's stderr goes. Defaults to {@link StderrSink#NULL}. */
    public ConnectOptions stderr(StderrSink sink) {
        this.stderr = sink;
        return this;
    }

    /** The connection's initial timeout. {@code null} means wait forever. Defaults to {@link SanDbOx#DEFAULT_TIMEOUT}. */
    public ConnectOptions timeout(Duration t) {
        this.timeout = t;
        return this;
    }

    Map<String, String> env() {
        return env;
    }

    String cwd() {
        return cwd;
    }

    StderrSink stderr() {
        return stderr;
    }

    Duration timeout() {
        return timeout;
    }
}
