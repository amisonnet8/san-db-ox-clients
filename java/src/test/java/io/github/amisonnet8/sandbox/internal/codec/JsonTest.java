package io.github.amisonnet8.sandbox.internal.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    private static Json parse(String s) {
        return JsonParser.parse(s);
    }

    @Test
    void literals() {
        assertInstanceOf(Json.Null.class, parse("null"));
        assertEquals(new Json.Bool(true), parse("true"));
        assertEquals(new Json.Bool(false), parse("false"));
    }

    @Test
    void string_escapes() {
        Json v = parse("\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"");
        assertEquals(new Json.Str("a\"b\\c/d\be\ff\ng\rh\ti"), v);
    }

    @Test
    void unicode_escape_and_surrogate_pair() {
        assertEquals(new Json.Str("\u00e9"), parse("\"\\u00e9\""));
        // U+1F600 GRINNING FACE as a UTF-16 surrogate pair.
        assertEquals(new Json.Str("\ud83d\ude00"), parse("\"\\ud83d\\ude00\""));
    }

    @Test
    void unescaped_control_character_is_rejected() {
        assertThrows(CodecException.class, () -> parse("\"a\u0001b\""));
    }

    @Test
    void object_preserves_key_order() {
        Json.Obj o = (Json.Obj) parse("{\"b\":1,\"a\":2}");
        assertEquals("b", o.members().get(0).key());
        assertEquals("a", o.members().get(1).key());
    }

    @Test
    void nested_array_and_object() {
        Json v = parse("{\"a\":[1,2,{\"b\":true}]}");
        Json.Obj o = (Json.Obj) v;
        Json.Arr a = (Json.Arr) o.members().get(0).value();
        assertEquals(3, a.items().size());
        Json.Obj inner = (Json.Obj) a.items().get(2);
        assertEquals("b", inner.members().get(0).key());
    }

    @Test
    void integer_and_real_tokens_are_distinguished() {
        Json.Num integer = (Json.Num) parse("88");
        assertFalse(integer.token().isReal());
        assertEquals("88", integer.token().raw());

        Json.Num real = (Json.Num) parse("88.0");
        assertTrue(real.token().isReal());
        assertEquals("88.0", real.token().raw());
    }

    @Test
    void int64_range_round_trips_exactly() {
        Json.Num max = (Json.Num) parse("9223372036854775807");
        assertEquals(Long.MAX_VALUE, max.token().asLong());
        Json.Num min = (Json.Num) parse("-9223372036854775808");
        assertEquals(Long.MIN_VALUE, min.token().asLong());
    }

    @Test
    void integer_out_of_int64_range_fails_to_parse_as_long() {
        Json.Num n = (Json.Num) parse("9223372036854775808");
        assertThrows(CodecException.class, n.token()::asLong);
    }

    @Test
    void nine_e_999_saturates_to_infinity() {
        Json.Num n = (Json.Num) parse("9e999");
        assertEquals(Double.POSITIVE_INFINITY, n.token().asDouble());
        Json.Num neg = (Json.Num) parse("-9e999");
        assertEquals(Double.NEGATIVE_INFINITY, neg.token().asDouble());
    }

    @Test
    void bareword_nan_and_infinity_are_rejected() {
        assertThrows(CodecException.class, () -> parse("NaN"));
        assertThrows(CodecException.class, () -> parse("Infinity"));
        assertThrows(CodecException.class, () -> parse("-Infinity"));
    }

    @Test
    void trailing_comma_is_rejected() {
        assertThrows(CodecException.class, () -> parse("[1,2,]"));
        assertThrows(CodecException.class, () -> parse("{\"a\":1,}"));
    }

    @Test
    void leading_plus_is_rejected() {
        assertThrows(CodecException.class, () -> parse("+1"));
    }

    @Test
    void leading_zero_followed_by_digit_is_rejected() {
        assertThrows(CodecException.class, () -> parse("01"));
    }

    @Test
    void comments_are_rejected() {
        assertThrows(CodecException.class, () -> parse("1 // comment"));
        assertThrows(CodecException.class, () -> parse("/* c */ 1"));
    }

    @Test
    void trailing_data_after_value_is_rejected() {
        assertThrows(CodecException.class, () -> parse("1 2"));
    }

    @Test
    void empty_object_and_array() {
        assertEquals(new Json.Obj(java.util.List.of()), parse("{}"));
        assertEquals(new Json.Arr(java.util.List.of()), parse("[]"));
    }

    @Test
    void write_round_trips_a_simple_object() {
        String src = "{\"ok\":true,\"n\":88,\"r\":88.0,\"s\":\"hi\",\"a\":[1,2],\"z\":null}";
        Json v = parse(src);
        assertEquals(src, JsonWriter.write(v));
    }

    @Test
    void write_escapes_special_characters() {
        Json v = new Json.Str("a\"b\\c\nd\u0001e");
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001e\"", JsonWriter.write(v));
    }

    @Test
    void every_conformance_case_file_round_trips_byte_for_byte() throws IOException {
        Path casesDir = TestSupport.repoRoot().resolve("conformance").resolve("cases");
        int[] checked = {0};
        try (Stream<Path> files = Files.list(casesDir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(path);
                Json parsed = JsonParser.parse(text);
                if (!(parsed instanceof Json.Obj obj)) {
                    continue;
                }
                Json steps = Decode.get(obj, "steps");
                if (!(steps instanceof Json.Arr stepsArr)) {
                    continue;
                }
                for (Json step : stepsArr.items()) {
                    if (!(step instanceof Json.Obj stepObj)) {
                        continue;
                    }
                    Json request = Decode.get(stepObj, "request");
                    if (request == null) {
                        continue;
                    }
                    String rewritten = JsonWriter.write(request);
                    Json reparsed = JsonParser.parse(rewritten);
                    assertEquals(request, reparsed, "round trip mismatch in " + path);
                }
                checked[0]++;
            }
        }
        assertTrue(checked[0] > 0, "no conformance case files found under " + casesDir);
    }
}
