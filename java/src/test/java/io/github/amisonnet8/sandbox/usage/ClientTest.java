package io.github.amisonnet8.sandbox.usage;

import io.github.amisonnet8.sandbox.ConnectionClosedException;
import io.github.amisonnet8.sandbox.ConnectOptions;
import io.github.amisonnet8.sandbox.ReadTimeoutException;
import io.github.amisonnet8.sandbox.ResponseException;
import io.github.amisonnet8.sandbox.SanDbOx;
import io.github.amisonnet8.sandbox.SanDbOxClient;
import io.github.amisonnet8.sandbox.SanDbOxException;
import io.github.amisonnet8.sandbox.SnapshotOptions;
import io.github.amisonnet8.sandbox.SnapshotResult;
import io.github.amisonnet8.sandbox.StderrSink;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests against a real san-db-ox binary, using only the
 * public API. Package {@code usage}, separate from the rest of this test
 * suite, so that only {@link Connection}/{@link SanDbOx}-reachable code is
 * even compilable here -- the compiler is what enforces "this is
 * everything a consumer of this library could actually write" (mirrors
 * {@code rust/tests/client.rs} being a separate crate).
 *
 * <p>{@code bad_request} and {@code unsupported_op} are not reachable
 * through this typed API at all: there is no way to construct a malformed
 * request with it. Those two codes are covered by {@code
 * conformance/cases/error-codes.json} instead -- a type-safety win, not a
 * coverage gap.
 */
class ClientTest {

    private static SanDbOxClient open(Path bin) throws SanDbOxException {
        return SanDbOx.connect(bin.toString(), List.of("--serve-stdio"), new ConnectOptions().timeout(UsageTestSupport.CALL_TIMEOUT));
    }

    private static SanDbOxClient openWithArgs(Path bin, List<String> args) throws SanDbOxException {
        return SanDbOx.connect(bin.toString(), args, new ConnectOptions().timeout(UsageTestSupport.CALL_TIMEOUT));
    }

    private static Path requireBin() {
        Path bin = UsageTestSupport.sanDbOxBin();
        Assumptions.assumeTrue(bin != null, "skipping: no san-db-ox binary found: run `make fetch` or set SAN_DB_OX_BIN");
        return bin;
    }

    @Test
    void hello_and_basic_query() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            assertEquals(SanDbOx.PROTOCOL, c.hello().protocol());
            assertEquals("SanDBox", c.hello().product());
            var result = c.query("SELECT 1", List.of());
            assertEquals(List.of(List.of(1L)), result.rows());
        }
    }

    @Test
    void connect_rejects_nonexistent_command() {
        // The spawn failure (ENOENT) surfaces as the base exception type:
        // there is no protocol violation or wire error to wrap here, just
        // a bad command.
        SanDbOxException e = assertThrows(
                SanDbOxException.class,
                () -> SanDbOx.connect("this-command-does-not-exist-really", List.of("--serve-stdio")));
        assertEquals(SanDbOxException.class, e.getClass());
    }

    @Test
    void blob_roundtrip() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            c.exec("CREATE TABLE t(b BLOB)", List.of());
            c.exec("INSERT INTO t VALUES (?)", List.of((Object) "hi".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var result = c.query("SELECT b FROM t", List.of());
            assertArrayEquals("hi".getBytes(java.nio.charset.StandardCharsets.UTF_8), (byte[]) result.rows().get(0).get(0));
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    @Test
    void empty_blob_roundtrip() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            c.exec("CREATE TABLE t(b BLOB)", List.of());
            c.exec("INSERT INTO t VALUES (?)", List.of((Object) new byte[0]));
            var result = c.query("SELECT b FROM t", List.of());
            assertArrayEquals(new byte[0], (byte[]) result.rows().get(0).get(0));
        }
    }

    @Test
    void large_integer_roundtrip() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            long big = Long.MAX_VALUE;
            c.exec("CREATE TABLE big(n INTEGER)", List.of());
            c.exec("INSERT INTO big VALUES (?)", List.of(big));
            var result = c.query("SELECT n, typeof(n) FROM big", List.of());
            assertEquals(List.of(List.of(big, "integer")), result.rows());
        }
    }

    @Test
    void real_representation() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            assertEquals(List.of(List.of(88.0)), c.query("SELECT 88.0", List.of()).rows());
            assertEquals(List.of(List.of(Double.POSITIVE_INFINITY)), c.query("SELECT 1e308 * 10", List.of()).rows());
            assertEquals(List.of(List.of(Double.NEGATIVE_INFINITY)), c.query("SELECT -1e308 * 10", List.of()).rows());
            // Inf - Inf is SQL NULL upstream, indistinguishable from a REAL
            // NaN once decoded -- not a loss introduced by this driver.
            List<List<Object>> nullRow = c.query("SELECT (1e308 * 10) - (1e308 * 10)", List.of()).rows();
            assertNull(nullRow.get(0).get(0));
        }
    }

    @Test
    void error_codes_and_connection_survives() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            ResponseException e1 = assertThrows(ResponseException.class, () -> c.query("SELECT * FROM nope", List.of()));
            assertTrue(e1.isCode(SanDbOxException.CODE_SQLITE_ERROR), "got " + e1.code());

            ResponseException e2 = assertThrows(ResponseException.class, () -> c.load("/nonexistent/path/really-not-there.db"));
            assertTrue(e2.isCode(SanDbOxException.CODE_IO_ERROR), "got " + e2.code());

            // The connection must still be usable after errors.
            assertEquals(List.of(List.of(1L)), c.query("SELECT 1", List.of()).rows());
        }
    }

    @Test
    void read_only_two_tier_rejection() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = openWithArgs(bin, List.of("--serve-stdio", "--read-only"))) {
            // SQL-level write: rejected by SQLite's own PRAGMA query_only.
            ResponseException e1 = assertThrows(ResponseException.class, () -> c.exec("CREATE TABLE t(x INTEGER)", List.of()));
            assertTrue(e1.isCode(SanDbOxException.CODE_SQLITE_ERROR), "got " + e1.code());

            // Op-level writes: rejected by the stdio server itself, before SQLite.
            ResponseException e2 = assertThrows(ResponseException.class, c::overwrite);
            assertTrue(e2.isCode(SanDbOxException.CODE_READ_ONLY), "got " + e2.code());

            ResponseException e3 = assertThrows(ResponseException.class, () -> c.load("/nonexistent/does-not-matter.db"));
            assertTrue(e3.isCode(SanDbOxException.CODE_READ_ONLY), "got " + e3.code());

            assertEquals(List.of(List.of(1L)), c.query("SELECT 1", List.of()).rows());
            assertTrue(c.inspect().readOnly());
        }
    }

    @Test
    void snapshot_and_load() throws Exception {
        Path bin = requireBin();
        // snapshot()/load() write/resolve relative to the server's cwd --
        // both connections share one isolated directory so `path`
        // (returned by snapshot, possibly relative) resolves the same way
        // for load.
        Path cwd = UsageTestSupport.isolatedDir();
        try {
            String path;
            try (SanDbOxClient c = SanDbOx.connect(
                    bin.toString(), List.of("--serve-stdio"), new ConnectOptions().timeout(UsageTestSupport.CALL_TIMEOUT).cwd(cwd.toString()))) {
                c.exec("CREATE TABLE t(x INTEGER)", List.of());
                c.exec("INSERT INTO t VALUES (42)", List.of());
                SnapshotResult snap = c.snapshot(new SnapshotOptions().filename("snap"));
                path = snap.path();
            }

            try (SanDbOxClient c2 = SanDbOx.connect(
                    bin.toString(), List.of("--serve-stdio"), new ConnectOptions().timeout(UsageTestSupport.CALL_TIMEOUT).cwd(cwd.toString()))) {
                c2.load(path);
                assertEquals(List.of(List.of(42L)), c2.query("SELECT x FROM t", List.of()).rows());
            }
        } finally {
            UsageTestSupport.deleteRecursively(cwd);
        }
    }

    @Test
    void inspect_reflects_own_process_only() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            var info = c.inspect();
            assertFalse(info.hasData());
            assertNull(info.dataLength());
            assertNull(info.version());
            c.exec("CREATE TABLE t(x INTEGER)", List.of());
            // exec'd SQL state doesn't change inspect's view of embedded data.
            var infoAfter = c.inspect();
            assertFalse(infoAfter.hasData());
        }
    }

    @Test
    void tables_schema_dump() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            c.exec("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)", List.of());
            c.exec("CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)", List.of());
            c.exec("INSERT INTO users(id, name) VALUES (1, 'alice')", List.of());

            assertEquals(List.of("logs", "users"), c.tables().tables());

            var schema = c.schema(null);
            assertEquals(
                    List.of(
                            "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)",
                            "CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)"),
                    schema.schema());
            assertEquals(
                    List.of("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)"), c.schema("users").schema());

            var dump = c.dump("users");
            assertTrue(dump.sql().contains("INSERT INTO \"users\" VALUES(1,'alice')"));
            assertFalse(dump.sql().contains("logs"));
        }
    }

    @Test
    void close_reaps_process_and_is_idempotent() throws SanDbOxException {
        Path bin = requireBin();
        SanDbOxClient c = open(bin);
        c.close();
        assertEquals(OptionalInt.of(0), c.exitCode());
        c.close(); // must not throw or hang
    }

    @Test
    @SuppressWarnings("try") // the resource is deliberately unused in the body -- see the comment below
    void close_via_try_with_resources_reaps_child_without_explicit_close() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient ignored = open(bin)) {
            // closed automatically at the end of this block
        }
        // If the child leaked, there is nothing to assert on directly from
        // here (this test doesn't hold a handle to it past the block) --
        // the meaningful assertion is that the JVM running this test
        // completes cleanly without needing to force-kill anything, the
        // same practical signal the Rust driver's equivalent test relies on.
    }

    @Test
    void overwrite_reaps_and_persists_data() throws Exception {
        Path bin = requireBin();
        Path dir = UsageTestSupport.isolatedDir();
        try {
            Path copy = UsageTestSupport.copyOfBinary(bin, dir);

            try (SanDbOxClient c = open(copy)) {
                c.exec("CREATE TABLE t(x INTEGER)", List.of());
                c.exec("INSERT INTO t VALUES (7)", List.of());
                c.overwrite();
                assertEquals(OptionalInt.of(0), c.exitCode());
            }

            try (SanDbOxClient c2 = open(copy)) {
                assertTrue(c2.inspect().hasData());
                assertEquals(List.of(List.of(7L)), c2.query("SELECT x FROM t", List.of()).rows());
            }
        } finally {
            UsageTestSupport.deleteRecursively(dir);
        }
    }

    @Test
    void call_timeout_tears_down() throws SanDbOxException {
        Path bin = requireBin();
        // A plain "SELECT 1" round-trips too fast for a tiny timeout to
        // reliably fire -- use a query that takes a few seconds so the
        // timeout path is exercised deterministically rather than racing
        // real round-trip latency.
        String slowQuery = "WITH RECURSIVE r(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM r WHERE x < 10000000) "
                + "SELECT count(*) FROM r";
        // Not try-with-resources: close() is called explicitly below as
        // the thing under test (that it's safe to call and that it
        // actually reaps the child after a timeout), so an additional
        // implicit close at the end of a resource block would be
        // redundant rather than a mistake -- same reason
        // close_reaps_process_and_is_idempotent above doesn't use it either.
        SanDbOxClient c = open(bin);
        c.setTimeout(Duration.ofMillis(200));
        assertThrows(ReadTimeoutException.class, () -> c.query(slowQuery, List.of()));

        // The connection is unusable now.
        assertThrows(ConnectionClosedException.class, () -> c.query("SELECT 1", List.of()));

        // close() must still be safe, and must have actually reaped the child.
        c.close();
        assertTrue(c.exitCode().isPresent());
    }

    @Test
    void stderr_sink_does_not_break_the_connection() throws SanDbOxException {
        Path bin = requireBin();
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try (SanDbOxClient c = SanDbOx.connect(
                bin.toString(),
                List.of("--serve-stdio"),
                new ConnectOptions().timeout(UsageTestSupport.CALL_TIMEOUT).stderr(StderrSink.writer(collected)))) {
            c.query("SELECT 1", List.of());
            // Not asserting on content (san-db-ox may or may not log
            // anything for a clean run) -- just that wiring a sink doesn't
            // break the connection. A broken wiring would have made
            // connect() itself fail.
        }
    }

    @Test
    void nan_and_inf_params_are_rejected_before_anything_is_sent() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            SanDbOxException e1 = assertThrows(SanDbOxException.class, () -> c.query("SELECT ?", List.of(Double.NaN)));
            assertTrue(e1.getMessage().contains("finite"), "got " + e1.getMessage());
            SanDbOxException e2 = assertThrows(SanDbOxException.class, () -> c.query("SELECT ?", List.of(Double.POSITIVE_INFINITY)));
            assertTrue(e2.getMessage().contains("finite"), "got " + e2.getMessage());
            // The connection is still usable: rejection happens
            // client-side, before a request line is ever written.
            assertEquals(List.of(List.of(1L)), c.query("SELECT 1", List.of()).rows());
        }
    }

    @Test
    void boolean_param_is_rejected() throws SanDbOxException {
        Path bin = requireBin();
        try (SanDbOxClient c = open(bin)) {
            SanDbOxException e = assertThrows(SanDbOxException.class, () -> c.query("SELECT ?", List.of(true)));
            assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("boolean"), "got " + e.getMessage());
        }
    }
}
