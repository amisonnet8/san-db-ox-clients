package io.github.amisonnet8.sandbox.internal.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * Encoding: turning a param/cell {@code Object} (null, {@link Long},
 * {@link Integer}, {@link Double}, {@link String}, or {@code byte[]}) into
 * wire JSON.
 */
final class Encode {

    private Encode() {
    }

    /**
     * Formats a finite {@code double} as a token that always binds REAL
     * rather than INTEGER upstream. {@code Double.toString} always
     * contains a {@code .} or an exponent ({@code "88.0"}, {@code "-0.0"},
     * {@code "1.0E21"}, {@code "5.0E-7"}), so unlike Go's {@code
     * formatReal} or Rust's {@code real_token}, no fallback branch is
     * needed for a bare-integer-looking result -- the guard below is
     * belt-and-braces only, since the JDK does not formally guarantee
     * {@code toString}'s exact output shape.
     */
    static String realToken(double v) {
        String s = Double.toString(v);
        if (s.indexOf('.') < 0 && s.indexOf('E') < 0) {
            s = s + ".0";
        }
        return s;
    }

    /**
     * Encodes a single param/cell value. {@code Integer} is accepted
     * alongside {@code Long} purely for caller convenience (an unadorned
     * integer literal autoboxes to {@code Integer}, not {@code Long}) --
     * both produce the identical bare-digits wire token. Non-finite REALs
     * and unsupported types (including {@code Boolean}: SQLite has no
     * boolean storage class) are rejected before anything reaches the
     * wire.
     */
    static Json encodeValue(Object v) {
        if (v == null) {
            return new Json.Null();
        }
        if (v instanceof Long l) {
            return new Json.Num(new NumberToken(Long.toString(l)));
        }
        if (v instanceof Integer n) {
            return new Json.Num(new NumberToken(Long.toString(n.longValue())));
        }
        if (v instanceof Double d) {
            if (!Double.isFinite(d)) {
                throw new CodecException(
                        "REAL param must be finite (san-db-ox rejects NaN/Inf in params): " + d);
            }
            return new Json.Num(new NumberToken(realToken(d)));
        }
        if (v instanceof String s) {
            return new Json.Str(s);
        }
        if (v instanceof byte[] b) {
            return new Json.Arr(List.of(new Json.Str(B64.encode(b))));
        }
        throw new CodecException(
                "unsupported param type " + v.getClass().getName()
                        + " (want null, Long, Double, String, or byte[])");
    }

    /** Returns {@code null} when {@code params} is empty, meaning the "params" field should be omitted entirely. */
    static Json encodeParams(List<Object> params) {
        if (params.isEmpty()) {
            return null;
        }
        List<Json> items = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            try {
                items.add(encodeValue(params.get(i)));
            } catch (CodecException e) {
                throw new CodecException("params[" + i + "]: " + e.getMessage(), e);
            }
        }
        return new Json.Arr(items);
    }
}
