package io.github.amisonnet8.sandbox.internal.codec;

import java.util.List;

/**
 * A parsed JSON value. {@code permits} is omitted deliberately: every
 * implementation is nested here in the same file, so the compiler infers
 * the sealed permits list without it.
 *
 * <p>{@link Obj} holds members as an ordered {@code List}, not a {@code
 * Map}: preserving key order lets {@link JsonWriter} re-emit a parsed
 * object byte-for-byte, which the conformance and match tests rely on.
 *
 * <p>Public only for this repository's own conformance/match test suite,
 * which needs to build and inspect raw request/response trees that the
 * typed {@link io.github.amisonnet8.sandbox.Connection} API has no way to
 * construct (malformed requests, unknown ops); see the package javadoc.
 */
public sealed interface Json {

    record Null() implements Json {
    }

    record Bool(boolean value) implements Json {
    }

    record Num(NumberToken token) implements Json {
    }

    record Str(String value) implements Json {
    }

    record Arr(List<Json> items) implements Json {
    }

    record Obj(List<Member> members) implements Json {
        public record Member(String key, Json value) {
        }
    }
}
