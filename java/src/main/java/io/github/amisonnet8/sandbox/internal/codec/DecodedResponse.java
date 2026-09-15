package io.github.amisonnet8.sandbox.internal.codec;

/**
 * One decoded response line: whether it was {@code ok}, and the full
 * parsed object (every top-level field, including {@code "ok"} itself).
 * The parsed {@link Json} tree stays package-private even though this
 * class is public: callers outside this package pass a {@code
 * DecodedResponse} straight to {@link Decode}'s {@code op}-specific
 * methods rather than reading fields off it directly.
 */
public final class DecodedResponse {

    private final boolean ok;
    final Json fields;

    DecodedResponse(boolean ok, Json fields) {
        this.ok = ok;
        this.fields = fields;
    }

    public boolean ok() {
        return ok;
    }
}
