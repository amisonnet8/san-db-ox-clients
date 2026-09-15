package io.github.amisonnet8.sandbox;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Confirms {@code internal.codec} stays free of networking, process,
 * filesystem, and threading machinery (this repository's architecture:
 * "コーデック層はネットワークに依存しないことを検証可能にする"), the same
 * idea as the Go driver's {@code go list -deps | grep} and the Rust
 * driver's textual scanner. Unlike those, this uses {@code jdeps}
 * (bundled with the JDK, reachable in-process via {@link ToolProvider} --
 * no subprocess needed) for a real compiled-bytecode dependency graph
 * rather than a textual approximation.
 *
 * <p>A <b>positive control</b> -- the identical scan run against {@code
 * internal.transport}, which must produce at least one hit -- guards
 * against the scanner itself silently breaking (a moved package, a
 * changed {@code jdeps} output format) and reporting a false "clean"
 * codec.
 */
class NetcheckTest {

    private static final String[] FORBIDDEN = {
        "java.net.",
        "java.nio.channels.",
        "java.nio.file.",
        "java.lang.Process",
        "java.lang.Thread",
        "java.io.",
        "java.util.concurrent."
    };

    private static Path classesDir() {
        return TestSupport.repoRoot().resolve("java").resolve("target").resolve("classes");
    }

    private static String runJdeps(Path packageDir) {
        Optional<ToolProvider> jdeps = ToolProvider.findFirst("jdeps");
        // Fails (does not skip) if jdeps is unavailable: a silently
        // disabled check must not be mistaken for a passing one.
        if (jdeps.isEmpty()) {
            fail("jdeps tool not found via ToolProvider.findFirst(\"jdeps\") -- cannot run netcheck");
        }
        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();
        int code = jdeps.get()
                .run(
                        new PrintWriter(outSw),
                        new PrintWriter(errSw),
                        "-verbose:class",
                        "-filter:none",
                        "-cp",
                        classesDir().toString(),
                        packageDir.toString());
        if (code != 0) {
            fail("jdeps exited with code " + code + ":\n" + errSw);
        }
        return outSw.toString();
    }

    private static List<String> forbiddenHits(String jdepsOutput) {
        List<String> hits = new ArrayList<>();
        for (String line : jdepsOutput.split("\n")) {
            for (String prefix : FORBIDDEN) {
                if (line.contains(prefix)) {
                    hits.add(line.trim());
                    break;
                }
            }
        }
        return hits;
    }

    @Test
    void internal_codec_has_no_forbidden_dependencies() {
        Path pkg = classesDir().resolve("io/github/amisonnet8/sandbox/internal/codec");
        List<String> hits = forbiddenHits(runJdeps(pkg));
        assertTrue(
                hits.isEmpty(),
                "internal.codec must not depend on networking/process/threading/filesystem, but found:\n" + String.join("\n", hits));
    }

    @Test
    void positive_control_the_scanner_detects_a_known_dirty_package() {
        Path pkg = classesDir().resolve("io/github/amisonnet8/sandbox/internal/transport");
        List<String> hits = forbiddenHits(runJdeps(pkg));
        assertTrue(
                !hits.isEmpty(),
                "positive control failed: internal.transport should depend on java.net/java.io/java.lang.Thread etc, "
                        + "but the scanner found nothing -- see this test's class doc comment");
    }
}
