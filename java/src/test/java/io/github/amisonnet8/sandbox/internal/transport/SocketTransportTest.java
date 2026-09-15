package io.github.amisonnet8.sandbox.internal.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketTransportTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Thread tcpEchoServer(ServerSocket server) {
        Thread t = new Thread(() -> {
            try (Socket conn = server.accept()) {
                byte[] buf = new byte[4096];
                var in = conn.getInputStream();
                var out = conn.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // Test server: best-effort only.
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void tcp_echo_round_trip() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            tcpEchoServer(server);
            SocketTransport t = SocketTransport.connectTcp("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5));
            t.writeLine(b("hello\n"));
            byte[] line = t.readLine(Duration.ofSeconds(5));
            assertEquals("hello", new String(line, StandardCharsets.UTF_8));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unix_echo_round_trip() throws IOException {
        Path dir = Files.createTempDirectory("san-db-ox-java-test-");
        Path sock = dir.resolve("echo.sock");
        try (var server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            server.bind(java.net.UnixDomainSocketAddress.of(sock));
            Thread t = new Thread(() -> {
                try (var conn = server.accept()) {
                    var buf = java.nio.ByteBuffer.allocate(4096);
                    while (conn.read(buf) >= 0) {
                        buf.flip();
                        conn.write(buf);
                        buf.clear();
                    }
                } catch (IOException ignored) {
                    // Test server: best-effort only.
                }
            });
            t.setDaemon(true);
            t.start();

            SocketTransport transport = SocketTransport.connectUnix(sock);
            transport.writeLine(b("hello\n"));
            byte[] line = transport.readLine(Duration.ofSeconds(5));
            assertEquals("hello", new String(line, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(sock);
            Files.deleteIfExists(dir);
        }
    }

    /** The TLS seam without TLS: a loopback socket pair, exactly what {@code connectSocket} wraps. */
    @Test
    void from_socket_over_a_loopback_pair_is_the_tls_seam_without_tls() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            Socket client = new Socket();
            client.connect(server.getLocalSocketAddress(), 5000);
            Socket accepted = server.accept();

            Thread echo = new Thread(() -> {
                try {
                    byte[] buf = new byte[64];
                    int n = accepted.getInputStream().read(buf);
                    if (n > 0) {
                        accepted.getOutputStream().write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                    // Test server: best-effort only.
                } finally {
                    try {
                        accepted.close();
                    } catch (IOException ignored) {
                        // Best-effort.
                    }
                }
            });
            echo.setDaemon(true);
            echo.start();

            SocketTransport transport = SocketTransport.fromSocket(client);
            transport.writeLine(b("ping\n"));
            byte[] line = transport.readLine(Duration.ofSeconds(5));
            assertEquals("ping", new String(line, StandardCharsets.UTF_8));
        }
    }

    @Test
    void read_timeout_fires() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            tcpEchoServer(server);
            SocketTransport t = SocketTransport.connectTcp("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(5));
            TransportException e = assertThrows(TransportException.class, () -> t.readLine(Duration.ofMillis(50)));
            assertEquals(TransportException.Kind.TIMEOUT, e.kind());
        }
    }

    @Test
    void partial_line_survives_a_timeout_and_completes_next_read() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            Thread writer = new Thread(() -> {
                try (Socket conn = server.accept()) {
                    conn.getOutputStream().write(b("par"));
                    conn.getOutputStream().flush();
                    Thread.sleep(150);
                    conn.getOutputStream().write(b("tial\n"));
                    conn.getOutputStream().flush();
                } catch (IOException | InterruptedException ignored) {
                    // Test server: best-effort only.
                }
            });
            writer.setDaemon(true);
            writer.start();

            SocketTransport t = SocketTransport.connectTcp("127.0.0.1", port, Duration.ofSeconds(5));
            TransportException e = assertThrows(TransportException.class, () -> t.readLine(Duration.ofMillis(50)));
            assertEquals(TransportException.Kind.TIMEOUT, e.kind());
            byte[] line = t.readLine(Duration.ofSeconds(5));
            assertEquals("partial", new String(line, StandardCharsets.UTF_8));
        }
    }

    @Test
    void connect_tcp_bounds_the_connection_attempt_by_its_own_timeout() {
        // 192.0.2.0/24 is reserved for documentation (RFC 5737) and routes
        // nowhere real, so this either times out or fails fast -- either
        // way it must return well within the bound given.
        Instant start = Instant.now();
        assertThrows(TransportException.class, () -> SocketTransport.connectTcp("192.0.2.1", 1, Duration.ofMillis(300)));
        assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0);
    }
}
