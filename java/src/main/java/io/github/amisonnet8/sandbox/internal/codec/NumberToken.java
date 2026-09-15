package io.github.amisonnet8.sandbox.internal.codec;

/**
 * A JSON number, held as the exact text that was on the wire rather than
 * parsed eagerly into a {@code double}. This is what gives this codec
 * 64-bit integer fidelity without a JSON library: {@code
 * Double}-round-tripping a number larger than 2^53-1 loses precision, so
 * the raw token is kept until the caller decides (via {@link #isReal()})
 * whether it is a {@code long} or a {@code double}.
 *
 * <p>Equality and hashing are the raw text's, inherited from this being a
 * record: {@code "88"} and {@code "88.0"} are unequal tokens even though
 * they parse to numerically related values, exactly the distinction
 * {@link #isReal()} exists to make.
 *
 * <p>Public only because it can be reached through {@link Json.Num#token()}
 * from outside this package (this repository's conformance/match test
 * suite needs that); see the package javadoc.
 */
public record NumberToken(String raw) {

    /**
     * True if this token has a fractional part or an exponent (REAL),
     * false if it is a bare integer (INTEGER). Mirrors the same
     * classification in every other driver in this repository: a decimal
     * point or {@code e}/{@code E} makes it REAL.
     */
    public boolean isReal() {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') {
                return true;
            }
        }
        return false;
    }

    /** Parses this token as a 64-bit integer. Throws {@link CodecException} if it does not fit. */
    public long asLong() {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new CodecException("integer token out of range: " + raw, e);
        }
    }

    /**
     * Parses this token as a double. {@code Double.parseDouble} saturates
     * out-of-range magnitudes to {@link Double#POSITIVE_INFINITY} / {@link
     * Double#NEGATIVE_INFINITY} rather than failing, which is the desired
     * behavior here: the wire sends {@code 9e999}/{@code -9e999} for
     * ±Infinity (protocol.md), and this token was already validated as a
     * syntactically correct JSON number by the parser.
     */
    public double asDouble() {
        return Double.parseDouble(raw);
    }
}
