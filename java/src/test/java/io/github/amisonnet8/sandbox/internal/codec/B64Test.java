package io.github.amisonnet8.sandbox.internal.codec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class B64Test {

    @Test
    void empty_round_trips_as_empty_string() {
        assertEquals("", B64.encode(new byte[0]));
        assertArrayEquals(new byte[0], B64.decode(""));
    }

    @Test
    void round_trips_with_padding() {
        byte[] data = "hi".getBytes(StandardCharsets.UTF_8);
        String encoded = B64.encode(data);
        assertEquals("aGk=", encoded);
        assertArrayEquals(data, B64.decode(encoded));
    }

    @Test
    void java_util_base64_leniency_about_missing_padding_is_closed() {
        // java.util.Base64's own decoder accepts this (it treats a
        // length not a multiple of 4 as simply lacking trailing bits),
        // which this driver must not: every other driver in this
        // repository rejects it, so this one must too.
        assertThrows(CodecException.class, () -> B64.decode("QQ"));
        // Confirm the premise: the standard decoder alone is indeed lenient here.
        assertArrayEquals(new byte[] {0x41}, Base64.getDecoder().decode("QQ"));
    }

    @Test
    void invalid_character_is_rejected() {
        assertThrows(CodecException.class, () -> B64.decode("!!!!"));
    }

    @Test
    void length_not_a_multiple_of_four_is_rejected() {
        assertThrows(CodecException.class, () -> B64.decode("QQQ"));
    }
}
