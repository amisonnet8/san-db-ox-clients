package io.github.amisonnet8.sandbox.internal.codec;

import java.util.Base64;

/**
 * Base64 encode/decode for BLOB values (protocol.md: standard alphabet,
 * padded, no newlines).
 *
 * <p>{@link java.util.Base64}'s basic decoder rejects out-of-alphabet
 * characters but is lenient about a different mistake: it accepts input
 * whose length is not a multiple of 4, silently treating missing padding
 * as absent trailing bits (for example {@code Base64.getDecoder().decode(
 * "QQ")} succeeds). TypeScript's driver hit the same class of leniency in
 * {@code Buffer.from(s, "base64")} and documented it as a trap; here it is
 * closed with an explicit length check before delegating to the standard
 * decoder, so decoding is exactly as strict as the other four drivers in
 * this repository.
 */
final class B64 {

    private B64() {
    }

    static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    static byte[] decode(String s) {
        if (s.length() % 4 != 0) {
            throw new CodecException("invalid base64: length " + s.length() + " is not a multiple of 4");
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid base64: " + e.getMessage(), e);
        }
    }
}
