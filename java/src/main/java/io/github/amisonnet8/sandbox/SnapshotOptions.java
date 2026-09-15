package io.github.amisonnet8.sandbox;

/** Optional parameters for {@link Connection#snapshot(SnapshotOptions)}. */
public final class SnapshotOptions {

    private String filename;
    private Boolean sqlite;
    private Boolean timestamp;

    public SnapshotOptions() {
    }

    /** The snapshot's filename. Omitted (server default) when {@code null}. */
    public SnapshotOptions filename(String filename) {
        this.filename = filename;
        return this;
    }

    /** Whether to write a plain SQLite file. Omitted (server default) when {@code null}. */
    public SnapshotOptions sqlite(Boolean sqlite) {
        this.sqlite = sqlite;
        return this;
    }

    /** Whether to append a timestamp to the filename. Omitted (server default) when {@code null}. */
    public SnapshotOptions timestamp(Boolean timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    String filenameValue() {
        return filename;
    }

    Boolean sqliteValue() {
        return sqlite;
    }

    Boolean timestampValue() {
        return timestamp;
    }
}
