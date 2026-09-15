package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.codec.Json;
import io.github.amisonnet8.sandbox.internal.codec.JsonParser;
import io.github.amisonnet8.sandbox.internal.codec.JsonWriter;
import io.github.amisonnet8.sandbox.internal.transport.DirectTransport;
import io.github.amisonnet8.sandbox.internal.transport.TransportException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs {@code conformance/cases/*.json} against a real san-db-ox binary.
 * See {@code conformance/README.md} for the case format and the rules
 * this runner implements ({@code expect} partial match, {@code
 * expect_raw}, {@code known_failing} as xfail-but-never-swallow-a-
 * transport-failure).
 *
 * <p>Uses {@link DirectTransport} directly rather than the public {@link
 * SanDbOxClient} API: some cases deliberately send malformed requests
 * (wrong param shapes, unknown ops) that the typed API has no way to
 * construct.
 */
class ConformanceTest {

    private static Path casesDir() {
        return TestSupport.repoRoot().resolve("conformance").resolve("cases");
    }

    static List<Path> casePaths() throws IOException {
        try (var files = Files.list(casesDir())) {
            List<Path> v = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            return v;
        }
    }

    @Test
    void conformance_cases_directory_is_not_empty() throws IOException {
        assertTrue(!casePaths().isEmpty(), "no conformance cases found in " + casesDir());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casePaths")
    void conformance_case(Path path) throws IOException {
        Path bin = TestSupport.sanDbOxBin();
        Assumptions.assumeTrue(bin != null, "skipping conformance suite: no san-db-ox binary found (run `make fetch` or set SAN_DB_OX_BIN)");

        String text = Files.readString(path);
        Json caseJson = JsonParser.parse(text);

        List<String> args = new ArrayList<>();
        args.add("--serve-stdio");
        args.addAll(ConformanceSupport.asStringList(ConformanceSupport.get(caseJson, "args")));
        String knownFailing = ConformanceSupport.asString(ConformanceSupport.get(caseJson, "known_failing"));
        Json steps = ConformanceSupport.get(caseJson, "steps");
        if (!(steps instanceof Json.Arr stepsArr)) {
            fail("case has no steps array");
            return;
        }

        Path cwd = TestSupport.isolatedDir();
        DirectTransport transport =
                DirectTransport.start(bin.toString(), args, null, cwd.toString(), StderrSink.NULL);
        List<String> mismatches;
        try {
            mismatches = runSteps(transport, stepsArr.items());
        } finally {
            transport.close(Duration.ofSeconds(2));
            TestSupport.deleteRecursively(cwd);
        }

        if (knownFailing != null) {
            if (mismatches.isEmpty()) {
                fail("known_failing is set (" + knownFailing + ") but every step passed -- remove the marker");
            }
            // xfail: the case is allowed to fail, but the failure must
            // still be visible so a change in *how* it fails doesn't go
            // unnoticed.
            System.err.println("known_failing (" + knownFailing + "):\n  " + String.join("\n  ", mismatches));
            return;
        }
        if (!mismatches.isEmpty()) {
            fail(String.join("\n", mismatches));
        }
    }

    private static List<String> runSteps(DirectTransport transport, List<Json> steps) {
        Duration timeout = Duration.ofSeconds(TestSupport.CALL_TIMEOUT_SECS);
        try {
            transport.readLine(timeout); // hello line, discarded
        } catch (TransportException e) {
            fail("reading hello line: " + e.getMessage());
        }

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Json step = steps.get(i);
            Json request = ConformanceSupport.get(step, "request");
            if (request == null) {
                fail("step " + i + ": case has no request");
                return mismatches;
            }
            byte[] line = (JsonWriter.write(request) + "\n").getBytes(StandardCharsets.UTF_8);
            byte[] raw;
            try {
                transport.writeLine(line);
                raw = transport.readLine(timeout);
            } catch (TransportException e) {
                // A transport-level failure (unlike a wrong answer) is
                // never a "known" failure: it means the bug's shape
                // changed, which known_failing must not hide.
                fail("step " + i + ": transport failure: " + e.getMessage());
                return mismatches;
            }
            String rawText = new String(raw, StandardCharsets.UTF_8);

            Json expect = ConformanceSupport.get(step, "expect");
            if (expect != null) {
                Json actual = JsonParser.parse(rawText);
                String m = TestSupport.matchJson(expect, actual, "$");
                if (m != null) {
                    mismatches.add("step " + i + ": " + m + " (got " + rawText + ")");
                }
            }
            Json expectRaw = ConformanceSupport.get(step, "expect_raw");
            for (String snippet : ConformanceSupport.asStringList(expectRaw)) {
                if (!rawText.contains(snippet)) {
                    mismatches.add("step " + i + ": expected raw response to contain " + snippet + ", got " + rawText);
                }
            }
        }
        return mismatches;
    }
}
