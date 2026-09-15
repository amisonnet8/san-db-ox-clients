package io.github.amisonnet8.sandbox.internal.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one request line ({@code op} plus its fields, JSON object with
 * a trailing newline), keeping the {@link Json} tree this package builds
 * out of {@code Session}'s view. The Rust driver's equivalent is a plain
 * {@code Vec<(&str, Json)>} passed to a free function; this is the same
 * idea as a small fluent builder, since {@code Session} lives outside
 * this package and cannot construct {@link Json.Obj.Member} directly.
 */
public final class RequestBuilder {

    private final List<Json.Obj.Member> fields = new ArrayList<>();

    public RequestBuilder(String op) {
        fields.add(new Json.Obj.Member("op", new Json.Str(op)));
    }

    /** Omits the field entirely when {@code value} is {@code null}. */
    public RequestBuilder field(String key, String value) {
        if (value != null) {
            fields.add(new Json.Obj.Member(key, new Json.Str(value)));
        }
        return this;
    }

    /** Omits the field entirely when {@code value} is {@code null}. */
    public RequestBuilder field(String key, Boolean value) {
        if (value != null) {
            fields.add(new Json.Obj.Member(key, new Json.Bool(value)));
        }
        return this;
    }

    /** Omits the "params" field entirely when {@code params} is empty, matching the other drivers. */
    public RequestBuilder params(List<Object> params) {
        Json encoded = Encode.encodeParams(params);
        if (encoded != null) {
            fields.add(new Json.Obj.Member("params", encoded));
        }
        return this;
    }

    /** The finished request line, UTF-8 encoded with exactly one trailing {@code '\n'}. */
    public byte[] build() {
        String s = JsonWriter.write(new Json.Obj(fields)) + "\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
