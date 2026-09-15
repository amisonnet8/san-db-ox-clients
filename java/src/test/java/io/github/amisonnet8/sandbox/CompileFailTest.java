package io.github.amisonnet8.sandbox;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms, at test time, that {@code overwrite()}/{@code exitCode()} are
 * compile errors on {@link SocketClient} (this repository's
 * architecture.md: "呼び出せるが実行時に失敗する形にしない"). Uses {@link
 * javax.tools.JavaCompiler} (JDK-bundled, no extra dependency) to compile
 * small snippets in-process and assert on success/failure -- the Java
 * equivalent of the Rust driver's {@code compile_fail} doctest and the
 * TypeScript driver's {@code // @ts-expect-error}.
 *
 * <p>Every {@code compile_fail}-shaped check has a paired "must compile"
 * control that changes exactly one thing (the target class), guarding
 * against an unrelated typo making the snippet fail to compile for the
 * wrong reason -- the same precaution the Rust driver's doctest pairs
 * take.
 *
 * <p>Unlike Rust's {@code Value} enum, this driver's param type is a
 * plain {@code Object}, so rejecting a {@code Boolean} param cannot be a
 * compile error here; that is covered at runtime instead (see {@code
 * EncodeTest.boolean_param_is_rejected}), so there is no third pair for
 * it.
 */
class CompileFailTest {

    /**
     * Where compiled {@code .class} output goes. Without an explicit
     * {@code -d}, javac writes it into the JVM's current working
     * directory (this package's own source tree when run via {@code mvn
     * test}), which would otherwise leave a stray {@code Snippet.class}
     * behind in the repository on every run.
     */
    private static final Path OUT_DIR = newTempDir();

    private static Path newTempDir() {
        try {
            Path dir = java.nio.file.Files.createTempDirectory("san-db-ox-compile-fail-test-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean compiles(String body) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String source = "import io.github.amisonnet8.sandbox.*;\n"
                + "class Snippet {\n"
                + "  static void f() throws Exception {\n"
                + body + "\n"
                + "  }\n"
                + "}\n";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Snippet.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        String classpath = System.getProperty("java.class.path");
        StringWriter err = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
                err, null, null, List.of("-classpath", classpath, "-d", OUT_DIR.toString()), null, List.of(file));
        return task.call();
    }

    @Test
    void socket_client_overwrite_does_not_compile() {
        assertFalse(compiles("SocketClient c = null; c.overwrite();"));
    }

    @Test
    void direct_client_overwrite_compiles() {
        assertTrue(compiles("SanDbOxClient c = null; c.overwrite();"));
    }

    @Test
    void socket_client_exit_code_does_not_compile() {
        assertFalse(compiles("SocketClient c = null; c.exitCode();"));
    }

    @Test
    void direct_client_exit_code_compiles() {
        assertTrue(compiles("SanDbOxClient c = null; c.exitCode();"));
    }
}
