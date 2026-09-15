package io.github.amisonnet8.sandbox.usage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;

/**
 * A deliberate, small duplicate of the root test package's {@code
 * TestSupport}: this package can only see the public API (that is the
 * point of it living here, separately from {@code ClientTest}/{@code
 * SocketTest}'s package-private siblings), so it cannot reach that
 * package-private helper. Mirrors {@code rust/tests/common/mod.rs}'s own
 * documented duplication of {@code rust/src/tests/support.rs}.
 */
final class UsageTestSupport {

    /** Every call in this suite gets this timeout, so a flush/read bug hangs the affected test, not the whole suite. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private UsageTestSupport() {
    }

    static Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).getParent();
    }

    static Path sanDbOxBin() {
        String env = System.getenv("SAN_DB_OX_BIN");
        if (env != null) {
            return Paths.get(env);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String name = windows ? "san-db-ox.exe" : "san-db-ox";
        Path candidate = repoRoot().resolve("bin").resolve(name);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    static Path copyOfBinary(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        if (!target.toFile().setExecutable(true)) {
            throw new IOException("could not mark " + target + " executable");
        }
        return target;
    }

    static Path isolatedDir() throws IOException {
        String name = "san-db-ox-java-itest-" + ProcessHandle.current().pid() + "-" + System.nanoTime();
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
}
