package io.github.amisonnet8.sandbox;

import java.io.OutputStream;
import java.util.Objects;

/**
 * Where a direct-connect child process's stderr goes. The protocol
 * requires stderr to always be drained or redirected away from a pipe
 * (protocol.md): leaving it connected to an unread pipe eventually blocks
 * the server process once its stderr buffer fills.
 */
public final class StderrSink {

    /** Discards stderr (backed by {@code ProcessBuilder.Redirect.DISCARD}, no thread needed). */
    public static final StderrSink NULL = new StderrSink(Kind.NULL, null);

    /** Passes stderr through to this JVM's own stderr, no thread needed. */
    public static final StderrSink INHERIT = new StderrSink(Kind.INHERIT, null);

    /**
     * Discriminates which of this sink's three shapes it is. Public only
     * so {@code io.github.amisonnet8.sandbox.internal.transport} (a
     * different package, per Java's flat package-private visibility) can
     * read it; see that package's javadoc for why it has to be public at
     * all.
     */
    public enum Kind {
        NULL,
        INHERIT,
        WRITER
    }

    private final Kind kind;
    private final OutputStream writer;

    private StderrSink(Kind kind, OutputStream writer) {
        this.kind = kind;
        this.writer = writer;
    }

    /**
     * Drains stderr into {@code out} on a background thread. Unlike {@link
     * #NULL} and {@link #INHERIT}, this sink needs a dedicated thread to
     * keep the pipe drained.
     */
    public static StderrSink writer(OutputStream out) {
        return new StderrSink(Kind.WRITER, Objects.requireNonNull(out, "out"));
    }

    /** For {@code internal.transport}'s use only; see {@link Kind}. */
    public Kind kind() {
        return kind;
    }

    /** For {@code internal.transport}'s use only; non-null exactly when {@link #kind()} is {@link Kind#WRITER}. */
    public OutputStream writerStream() {
        return writer;
    }
}
