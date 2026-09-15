package io.github.amisonnet8.sandbox.internal.codec;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test-only helper local to this package. A small deliberate duplicate of
 * the root test package's own {@code TestSupport.repoRoot()}: this
 * package's tests need it too, and Java's package-private visibility
 * cannot share a single helper across packages without making it public
 * (see this package's javadoc).
 */
final class TestSupport {

    private TestSupport() {
    }

    static Path repoRoot() {
        // user.dir is java/ when Maven runs tests, so the repository root is one level up.
        return Paths.get(System.getProperty("user.dir")).getParent();
    }
}
