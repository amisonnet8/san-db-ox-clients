package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.codec.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Shared test-only helpers: binary discovery, an isolated temp dir, and
 * {@code matchJson}, a partial-match comparison over the codec's own
 * {@link Json} tree (so number tokens compare by their literal text --
 * {@code 88} never matches {@code 88.0}). Mirrors this repository's other
 * drivers' equivalent test support modules.
 */
final class TestSupport {

    static final long CALL_TIMEOUT_SECS = 10;

    private TestSupport() {
    }

    /** {@code user.dir} is {@code java/} when Maven runs tests, so the repository root is one level up. */
    static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    /**
     * Path to the san-db-ox binary under test, or {@code null} if not
     * found. Callers must skip (never fail) the individual test when this
     * is {@code null} (testing.md).
     */
    static Path sanDbOxBin() {
        String env = System.getenv("SAN_DB_OX_BIN");
        if (env != null) {
            return Paths.get(env);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String name = windows ? "san-db-ox.exe" : "san-db-ox";
        Path candidate = repoRoot().resolve("bin").resolve(name);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    /**
     * A private, writable copy of the binary, for the {@code overwrite}
     * test. Not every test class that uses {@code TestSupport} exercises
     * {@code overwrite}, hence the {@code @SuppressWarnings} rather than
     * deleting this for whichever class currently doesn't.
     */
    @SuppressWarnings("unused")
    static Path copyOfBinary(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        if (!target.toFile().setExecutable(true)) {
            throw new IOException("could not mark " + target + " executable");
        }
        return target;
    }

    /**
     * A fresh, isolated temporary directory. Tests that exercise {@code
     * snapshot}/{@code overwrite} must run the server with this as its
     * cwd: an earlier isolation bug in this repository's history (the
     * TypeScript driver's test suite) let a snapshot land inside the
     * repository itself when a test ran with an un-isolated cwd.
     */
    static Path isolatedDir() throws IOException {
        String name = "san-db-ox-java-test-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
        Path dir = Paths.get(System.getProperty("java.io.tmpdir")).resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    private static Json objectGet(Json.Obj obj, String key) {
        for (Json.Obj.Member m : obj.members()) {
            if (m.key().equals(key)) {
                return m.value();
            }
        }
        return null;
    }

    /**
     * Partial-match {@code expect} against {@code actual}
     * (conformance/README.md): objects match on the keys {@code expect}
     * specifies (extra {@code actual} keys are fine); arrays must match
     * length and order exactly; numbers compare by their literal token, so
     * {@code 88} and {@code 88.0} never match each other; everything else
     * compares by equality. Returns {@code null} on a match, or a
     * description of the first mismatch found.
     */
    static String matchJson(Json expect, Json actual, String path) {
        if (expect instanceof Json.Obj expObj) {
            if (!(actual instanceof Json.Obj)) {
                return path + ": expected an object, got " + actual;
            }
            Json.Obj actObj = (Json.Obj) actual;
            for (Json.Obj.Member m : expObj.members()) {
                Json actVal = objectGet(actObj, m.key());
                if (actVal == null) {
                    return path + "." + m.key() + ": missing in response";
                }
                String mismatch = matchJson(m.value(), actVal, path + "." + m.key());
                if (mismatch != null) {
                    return mismatch;
                }
            }
            return null;
        }
        if (expect instanceof Json.Arr expArr) {
            if (!(actual instanceof Json.Arr actArr)) {
                return path + ": expected an array, got " + actual;
            }
            if (expArr.items().size() != actArr.items().size()) {
                return path + ": expected " + expArr.items().size() + " elements, got " + actArr.items().size();
            }
            for (int i = 0; i < expArr.items().size(); i++) {
                String mismatch = matchJson(expArr.items().get(i), actArr.items().get(i), path + "[" + i + "]");
                if (mismatch != null) {
                    return mismatch;
                }
            }
            return null;
        }
        if (expect instanceof Json.Num expNum) {
            if (actual instanceof Json.Num actNum && expNum.token().raw().equals(actNum.token().raw())) {
                return null;
            }
            return path + ": expected number token " + expNum.token().raw() + ", got " + actual;
        }
        if (expect.equals(actual)) {
            return null;
        }
        return path + ": expected " + expect + ", got " + actual;
    }

}
