package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.codec.Json;
import io.github.amisonnet8.sandbox.internal.codec.JsonParser;
import io.github.amisonnet8.sandbox.internal.codec.JsonWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@code matchJson} and the literal-preserving parse/write round trip. */
class MatchTest {

    private static Json j(String s) {
        return JsonParser.parse(s);
    }

    @Test
    void number_tokens_88_and_88_point_0_do_not_match() {
        assertNotNull(TestSupport.matchJson(j("88"), j("88.0"), "$"));
        assertNull(TestSupport.matchJson(j("88"), j("88"), "$"));
        assertNull(TestSupport.matchJson(j("88.0"), j("88.0"), "$"));
    }

    @Test
    void object_partial_match_ignores_extra_actual_keys() {
        Json expect = j("{\"ok\":true}");
        Json actual = j("{\"ok\":true,\"extra\":\"field\"}");
        assertNull(TestSupport.matchJson(expect, actual, "$"));
    }

    @Test
    void object_partial_match_reports_missing_key() {
        Json expect = j("{\"a\":1,\"b\":2}");
        Json actual = j("{\"a\":1}");
        String m = TestSupport.matchJson(expect, actual, "$");
        assertNotNull(m);
        assertTrue(m.contains(".b"));
    }

    @Test
    void array_match_requires_exact_length_and_order() {
        assertNotNull(TestSupport.matchJson(j("[1,2,3]"), j("[1,2]"), "$"));
        assertNotNull(TestSupport.matchJson(j("[1,2]"), j("[2,1]"), "$"));
        assertNull(TestSupport.matchJson(j("[1,2]"), j("[1,2]"), "$"));
    }

    @Test
    void nested_mismatch_path_is_reported() {
        Json expect = j("{\"a\":[{\"b\":1}]}");
        Json actual = j("{\"a\":[{\"b\":2}]}");
        String m = TestSupport.matchJson(expect, actual, "$");
        assertNotNull(m);
        assertTrue(m.contains("$.a[0].b"), "unexpected path in: " + m);
    }

    @Test
    void strings_bools_and_null_compare_by_equality() {
        assertNotNull(TestSupport.matchJson(j("\"a\""), j("\"b\""), "$"));
        assertNull(TestSupport.matchJson(j("true"), j("true"), "$"));
        assertNull(TestSupport.matchJson(j("null"), j("null"), "$"));
        assertNotNull(TestSupport.matchJson(j("null"), j("false"), "$"));
    }

    @Test
    void large_integer_token_is_compared_verbatim() {
        Json expect = j("9223372036854775807");
        Json actual = j("9223372036854775807");
        assertNull(TestSupport.matchJson(expect, actual, "$"));
    }

    @Test
    void every_case_file_round_trips_byte_for_byte() throws IOException {
        Path casesDir = TestSupport.repoRoot().resolve("conformance").resolve("cases");
        int[] checked = {0};
        try (Stream<Path> files = Files.list(casesDir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String text = Files.readString(path);
                Json parsed = JsonParser.parse(text);
                Json steps = ConformanceSupport.get(parsed, "steps");
                if (!(steps instanceof Json.Arr stepsArr)) {
                    continue;
                }
                for (Json step : stepsArr.items()) {
                    Json request = ConformanceSupport.get(step, "request");
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
