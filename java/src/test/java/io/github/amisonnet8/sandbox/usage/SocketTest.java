package io.github.amisonnet8.sandbox.usage;

import io.github.amisonnet8.sandbox.SanDbOx;
import io.github.amisonnet8.sandbox.SocketClient;
import io.github.amisonnet8.sandbox.SocketOptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SocketClient} integration tests, over an in-process bridge
 * standing in for socat (plus a real-socat test that runs only when socat
 * is installed). Mirrors {@code rust/tests/socket.rs}.
 *
 * <p>The bridge below talks to the child process with a plain {@link
 * ProcessBuilder}, exactly mirroring what an external socat does.
 * POSIX-only (UNIX sockets, socat).
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class SocketTest {

    private static Path requireBin() {
        Path bin = UsageTestSupport.sanDbOxBin();
        Assumptions.assumeTrue(bin != null, "skipping: no san-db-ox binary found: run `make fetch` or set SAN_DB_OX_BIN");
        return bin;
    }

    private static void pump(InputStream in, OutputStream out) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // Bridge: best-effort only, mirrors socat's own behavior on a broken pipe.
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static Process bridgeChild(Path bin, List<String> args) throws IOException {
        List<String> full = new java.util.ArrayList<>();
        full.add(bin.toString());
        full.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(full);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    private static int tcpEchoBridge(Path bin, List<String> args) throws IOException {
        ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        int port = server.getLocalPort();
        Thread t = new Thread(() -> {
            try (ServerSocket s = server) {
                Socket conn = s.accept();
                Process child = bridgeChild(bin, args);
                pump(conn.getInputStream(), child.getOutputStream());
                pump(child.getInputStream(), conn.getOutputStream());
                child.waitFor();
            } catch (IOException | InterruptedException ignored) {
                // Bridge: best-effort only.
            }
        });
        t.setDaemon(true);
        t.start();
        return port;
    }

    /**
     * Pumps a UNIX-domain {@link SocketChannel} to an {@link OutputStream}
     * using raw {@link ByteBuffer} reads rather than {@link
     * java.nio.channels.Channels#newInputStream}: that wrapper was tried
     * first and found to silently lose data on this JDK when two {@code
     * Channels}-wrapper streams pump the same {@code SocketChannel}
     * concurrently in opposite directions (reproduced in isolation --
     * writes via {@code Channels.newOutputStream} reported success but
     * never reached the peer). Raw channel I/O, the same approach {@link
     * io.github.amisonnet8.sandbox.internal.transport.ChannelSource} uses
     * in the driver itself, does not have this problem.
     */
    private static void pumpChannelToStream(SocketChannel in, OutputStream out) {
        Thread t = new Thread(() -> {
            ByteBuffer buf = ByteBuffer.allocate(65536);
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    buf.flip();
                    byte[] chunk = new byte[buf.remaining()];
                    buf.get(chunk);
                    out.write(chunk);
                    out.flush();
                    buf.clear();
                }
            } catch (IOException ignored) {
                // Bridge: best-effort only, mirrors socat's own behavior on a broken pipe.
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void pumpStreamToChannel(InputStream in, SocketChannel out) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(ByteBuffer.wrap(buf, 0, n));
                }
            } catch (IOException ignored) {
                // Bridge: best-effort only.
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static Path unixEchoBridge(Path bin, List<String> args) throws IOException {
        Path dir = UsageTestSupport.isolatedDir();
        Path sock = dir.resolve("bridge.sock");
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock));
        Thread t = new Thread(() -> {
            try (ServerSocketChannel s = server) {
                SocketChannel conn = s.accept();
                Process child = bridgeChild(bin, args);
                pumpChannelToStream(conn, child.getOutputStream());
                pumpStreamToChannel(child.getInputStream(), conn);
                child.waitFor();
            } catch (IOException | InterruptedException ignored) {
                // Bridge: best-effort only.
            }
        });
        t.setDaemon(true);
        t.start();
        return sock;
    }

    @Test
    void socket_client_over_tcp_bridge() throws Exception {
        Path bin = requireBin();
        int port = tcpEchoBridge(bin, List.of("--serve-stdio"));
        try (SocketClient c = SanDbOx.connectTcp("127.0.0.1", port, new SocketOptions().timeout(UsageTestSupport.CALL_TIMEOUT))) {
            assertEquals(SanDbOx.PROTOCOL, c.hello().protocol());
            c.exec("CREATE TABLE t(x INTEGER)", List.of());
            c.exec("INSERT INTO t VALUES (1)", List.of());
            assertEquals(List.of(List.of(1L)), c.query("SELECT x FROM t", List.of()).rows());
        }
    }

    @Test
    void socket_client_over_unix_bridge() throws Exception {
        Path bin = requireBin();
        Path sock = unixEchoBridge(bin, List.of("--serve-stdio"));
        try (SocketClient c = SanDbOx.connectUnix(sock, new SocketOptions().timeout(UsageTestSupport.CALL_TIMEOUT))) {
            assertEquals(SanDbOx.PROTOCOL, c.hello().protocol());
            c.exec("CREATE TABLE t(x INTEGER)", List.of());
            assertEquals(List.of(List.of(1L)), c.query("SELECT 1", List.of()).rows());
        }
    }

    @Test
    void socket_client_close_reaches_bridged_process_and_is_idempotent() throws Exception {
        Path bin = requireBin();
        Path sock = unixEchoBridge(bin, List.of("--serve-stdio"));
        SocketClient c = SanDbOx.connectUnix(sock, new SocketOptions().timeout(UsageTestSupport.CALL_TIMEOUT));
        c.close();
        c.close(); // must not throw or hang
    }

    @Test
    void socket_client_via_real_socat() throws Exception {
        Path bin = requireBin();
        boolean hasSocat;
        try {
            hasSocat = new ProcessBuilder("which", "socat").redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException e) {
            hasSocat = false;
        }
        Assumptions.assumeTrue(hasSocat, "skipping: socat is not installed");

        Path dir = UsageTestSupport.isolatedDir();
        Path sock = dir.resolve("socat.sock");
        Process socat = new ProcessBuilder("socat", "UNIX-LISTEN:" + sock + ",fork", "EXEC:" + bin + " --serve-stdio").start();
        try {
            Instant deadline = Instant.now().plusSeconds(5);
            while (!Files.exists(sock)) {
                if (Instant.now().isAfter(deadline)) {
                    socat.destroy();
                    throw new AssertionError("socat did not create the socket in time");
                }
                Thread.sleep(50);
            }

            try (SocketClient c = SanDbOx.connectUnix(sock, new SocketOptions().timeout(UsageTestSupport.CALL_TIMEOUT))) {
                assertEquals(SanDbOx.PROTOCOL, c.hello().protocol());
                assertEquals(List.of(List.of(1L)), c.query("SELECT 1", List.of()).rows());
            }
        } finally {
            socat.destroy();
            socat.waitFor();
        }
    }
}
