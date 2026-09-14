package sandbox_test

// Socket transport tests. TestSocketClientOverBridge uses a small
// in-process bridge (net.Listen -> transport.StartDirect -> io.Copy in
// both directions), which stands in for socat without requiring an
// external tool, so it always runs in CI. TestSocketClientViaSocat
// exercises the real deployment shape (.claude/rules/architecture.md,
// .claude/rules/connectivity.md) and skips cleanly where socat isn't
// installed.

import (
	"context"
	"errors"
	"io"
	"net"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"

	"github.com/amisonnet8/san-db-ox-clients/go/sandbox"
	"github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/transport"
)

// startBridge launches its own san-db-ox child process per accepted
// connection and pipes bytes between the connection and the child's
// stdin/stdout, playing the same role `socat ...,fork EXEC:"..."` plays in
// connectivity.md. It returns the address to dial.
func startBridge(t *testing.T, network string) string {
	t.Helper()
	bin := findBinary(t)

	var ln net.Listener
	var err error
	switch network {
	case "tcp":
		ln, err = net.Listen("tcp", "127.0.0.1:0")
	case "unix":
		ln, err = net.Listen("unix", filepath.Join(t.TempDir(), "bridge.sock"))
	default:
		t.Fatalf("unsupported network %q", network)
	}
	if err != nil {
		t.Fatalf("net.Listen(%q): %v", network, err)
	}
	t.Cleanup(func() { _ = ln.Close() })

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go bridgeOneConn(t, bin, conn)
		}
	}()

	return ln.Addr().String()
}

func bridgeOneConn(t *testing.T, bin string, conn net.Conn) {
	defer conn.Close()

	ctx, cancel := context.WithTimeout(context.Background(), callTimeout)
	defer cancel()

	d, err := transport.StartDirect(ctx, bin, []string{"--serve-stdio"})
	if err != nil {
		t.Logf("bridge: StartDirect: %v", err)
		return
	}
	defer d.Close(2 * time.Second)

	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(d, conn); done <- struct{}{} }()
	go func() { _, _ = io.Copy(conn, d); done <- struct{}{} }()
	<-done
}

func TestSocketClientOverBridge(t *testing.T) {
	for _, network := range []string{"tcp", "unix"} {
		network := network
		t.Run(network, func(t *testing.T) {
			if network == "unix" && runtime.GOOS == "windows" {
				t.Skip("UNIX domain sockets are not exercised on windows here")
			}
			addr := startBridge(t, network)

			ctx := withTimeout(t)
			c, err := sandbox.OpenSocket(ctx, network, addr)
			if err != nil {
				t.Fatalf("OpenSocket: %v", err)
			}
			t.Cleanup(func() { _ = c.Close(withTimeout(t)) })

			if c.Hello.Protocol != 1 {
				t.Errorf("Hello.Protocol = %d, want 1", c.Hello.Protocol)
			}

			if _, err := c.Exec(ctx, "CREATE TABLE t(x INTEGER)"); err != nil {
				t.Fatalf("Exec: %v", err)
			}
			if _, err := c.Exec(ctx, "INSERT INTO t VALUES (42)"); err != nil {
				t.Fatalf("Exec: %v", err)
			}
			q, err := c.Query(ctx, "SELECT x FROM t")
			if err != nil {
				t.Fatalf("Query: %v", err)
			}
			if len(q.Rows) != 1 || q.Rows[0][0] != int64(42) {
				t.Errorf("Rows = %#v", q.Rows)
			}

			if err := c.Close(ctx); err != nil {
				t.Fatalf("Close: %v", err)
			}
		})
	}
}

func TestSocketClientViaSocat(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("socat is not available on windows here")
	}
	socatPath, err := exec.LookPath("socat")
	if err != nil {
		t.Skip("socat not found in PATH; skipping real socat integration test")
	}
	bin := findBinary(t)

	// Keep the socket path short: AF_UNIX's sun_path has a ~108 byte limit
	// on Linux, and t.TempDir() alone can already eat a good chunk of that.
	sockPath := filepath.Join(t.TempDir(), "s.sock")

	ctx, cancel := context.WithTimeout(context.Background(), callTimeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, socatPath,
		"UNIX-LISTEN:"+sockPath+",fork",
		"EXEC:"+bin+" --serve-stdio")
	if err := cmd.Start(); err != nil {
		t.Fatalf("starting socat: %v", err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	})

	waitForSocket(t, sockPath)

	c, err := sandbox.OpenSocket(withTimeout(t), "unix", sockPath)
	if err != nil {
		t.Fatalf("OpenSocket: %v", err)
	}
	t.Cleanup(func() { _ = c.Close(withTimeout(t)) })

	if c.Hello.Protocol != 1 {
		t.Errorf("Hello.Protocol = %d, want 1", c.Hello.Protocol)
	}
	q, err := c.Query(withTimeout(t), "SELECT 1")
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(q.Rows) != 1 || q.Rows[0][0] != int64(1) {
		t.Errorf("Rows = %#v", q.Rows)
	}
}

// waitForSocket polls for sockPath to appear, since socat's listener comes
// up asynchronously after cmd.Start returns.
func waitForSocket(t *testing.T, sockPath string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if conn, err := net.Dial("unix", sockPath); err == nil {
			_ = conn.Close()
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("socat's socket at %s never appeared", sockPath)
}

// overwriter and exitCoder mirror the direct-only methods
// (.claude/rules/architecture.md) so their presence/absence can be checked
// by type assertion below.
type overwriter interface{ Overwrite(context.Context) error }
type exitCoder interface{ ExitCode() int }

var _ overwriter = (*sandbox.Client)(nil)
var _ exitCoder = (*sandbox.Client)(nil)

// TestSocketClientOmitsDirectOnlyAPIs guards the type-level split
// architecture.md requires: if Overwrite or ExitCode were ever added to
// SocketClient (directly or via an embedding change), this test catches
// it, since the whole point is that these must not be reachable from a
// socket connection at compile time -- but a *reflection*-based check like
// this one is the next best thing for a test that wants to fail loudly if
// that guarantee regresses.
func TestSocketClientOmitsDirectOnlyAPIs(t *testing.T) {
	var c any = (*sandbox.SocketClient)(nil)
	if _, ok := c.(overwriter); ok {
		t.Error("*SocketClient must not implement Overwrite (.claude/rules/architecture.md)")
	}
	if _, ok := c.(exitCoder); ok {
		t.Error("*SocketClient must not implement ExitCode (.claude/rules/architecture.md)")
	}
}

func TestOpenSocketConnWrapsExistingNetConn(t *testing.T) {
	addr := startBridge(t, "tcp")
	nc, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatalf("net.Dial: %v", err)
	}

	c, err := sandbox.OpenSocketConn(withTimeout(t), nc)
	if err != nil {
		t.Fatalf("OpenSocketConn: %v", err)
	}
	t.Cleanup(func() { _ = c.Close(withTimeout(t)) })

	if _, err := c.Query(withTimeout(t), "SELECT 1"); err != nil {
		t.Fatalf("Query: %v", err)
	}
}

func TestOpenSocketRejectsUnreachableAddress(t *testing.T) {
	_, err := sandbox.OpenSocket(withTimeout(t), "tcp", "127.0.0.1:1")
	if err == nil {
		t.Fatal("OpenSocket to a closed port: want error, got nil")
	}
	var netErr net.Error
	if !errors.As(err, &netErr) {
		t.Logf("error is not a net.Error (still fine, just noting): %v", err)
	}
}
