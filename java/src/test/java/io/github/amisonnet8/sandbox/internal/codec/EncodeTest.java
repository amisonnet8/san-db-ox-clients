package io.github.amisonnet8.sandbox.internal.codec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodeTest {

    @Test
    void real_token_table() {
        assertEquals("88.0", Encode.realToken(88.0));
        assertEquals("-0.0", Encode.realToken(-0.0));
        // 1e21 and 5e-7 land in Double.toString's exponential-notation
        // range and always carry a decimal point before "E", unlike Go's
        // formatReal, which needed its own fallback for this case.
        assertTrue(Encode.realToken(1e21).contains("E"));
        assertTrue(Encode.realToken(5e-7).contains("E"));
        assertEquals("0.1", Encode.realToken(0.1));
    }

    @Test
    void real_token_round_trips_bit_exact() {
        double[] values = {
            0.0, -0.0, 1.0, -1.0, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, 1e308, -1e-308, 3.5
        };
        for (double v : values) {
            String token = Encode.realToken(v);
            double parsed = Double.parseDouble(token);
            assertEquals(Double.doubleToLongBits(v), Double.doubleToLongBits(parsed), "round trip mismatch for " + v + " -> " + token);
        }
    }

    @Test
    void non_finite_real_is_rejected() {
        CodecException e = assertThrows(CodecException.class, () -> Encode.encodeValue(Double.NaN));
        assertTrue(e.getMessage().contains("finite"));
        assertThrows(CodecException.class, () -> Encode.encodeValue(Double.POSITIVE_INFINITY));
        assertThrows(CodecException.class, () -> Encode.encodeValue(Double.NEGATIVE_INFINITY));
    }

    @Test
    void integer_encodes_as_bare_digits() {
        assertEquals("9223372036854775807", JsonWriter.write(Encode.encodeValue(Long.MAX_VALUE)));
        assertEquals("-9223372036854775808", JsonWriter.write(Encode.encodeValue(Long.MIN_VALUE)));
        assertEquals("42", JsonWriter.write(Encode.encodeValue(42)));
    }

    @Test
    void empty_blob_encodes_as_single_empty_string_element() {
        Json j = Encode.encodeValue(new byte[0]);
        assertEquals("[\"\"]", JsonWriter.write(j));
    }

    @Test
    void empty_params_omits_the_field() {
        assertNull(Encode.encodeParams(List.of()));
    }

    @Test
    void param_error_is_wrapped_with_index() {
        CodecException e = assertThrows(CodecException.class, () -> Encode.encodeParams(List.of(1L, Double.NaN)));
        assertTrue(e.getMessage().startsWith("params[1]:"));
    }

    @Test
    void boolean_param_is_rejected() {
        CodecException e = assertThrows(CodecException.class, () -> Encode.encodeValue(true));
        assertTrue(e.getMessage().contains("Boolean"));
    }

    @Test
    void request_line_ends_with_exactly_one_newline() {
        byte[] line = new RequestBuilder("query").field("sql", "SELECT 1").build();
        String s = new String(line, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("{\"op\":\"query\",\"sql\":\"SELECT 1\"}\n", s);
    }
}
