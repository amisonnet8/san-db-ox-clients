package io.github.amisonnet8.sandbox.internal.transport;

import java.time.Duration;

/** What every connection to a SanDBox process can do at the byte level, regardless of transport. */
public interface Transport {

    void writeLine(byte[] line);

    /** {@code timeout == null} means wait forever. */
    byte[] readLine(Duration timeout);

    void close(Duration timeout);
}
