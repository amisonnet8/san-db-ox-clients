package io.github.amisonnet8.sandbox;

import java.time.Duration;

/**
 * Optional settings for {@link SanDbOx#connectTcp}, {@link
 * SanDbOx#connectUnix}, and {@link SanDbOx#connectSocket}.
 */
public final class SocketOptions {

    private Duration timeout = SanDbOx.DEFAULT_TIMEOUT;

    public SocketOptions() {
    }

    /** The connection's initial timeout. {@code null} means wait forever. Defaults to {@link SanDbOx#DEFAULT_TIMEOUT}. */
    public SocketOptions timeout(Duration t) {
        this.timeout = t;
        return this;
    }

    Duration timeout() {
        return timeout;
    }
}
