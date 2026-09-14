package transport

import (
	"context"
	"io"
	"net"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

// echoOnce accepts one connection on ln and echoes whatever it reads back
// until the connection closes.
func echoOnce(t *testing.T, ln net.Listener) {
	t.Helper()
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		_, _ = io.Copy(conn, conn)
	}()
}

func TestDialSocketTCP(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("net.Listen: %v", err)
	}
	defer ln.Close()
	echoOnce(t, ln)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	s, err := DialSocket(ctx, "tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("DialSocket: %v", err)
	}
	defer s.Close(time.Second)

	assertEcho(t, s)
}

func TestDialSocketUnix(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("UNIX domain sockets are not exercised on windows here")
	}
	sockPath := filepath.Join(t.TempDir(), "s.sock")
	ln, err := net.Listen("unix", sockPath)
	if err != nil {
		t.Fatalf("net.Listen: %v", err)
	}
	defer ln.Close()
	echoOnce(t, ln)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	s, err := DialSocket(ctx, "unix", sockPath)
	if err != nil {
		t.Fatalf("DialSocket: %v", err)
	}
	defer s.Close(time.Second)

	assertEcho(t, s)
}

func TestNewSocketWrapsExistingConn(t *testing.T) {
	// net.Pipe gives two directly-connected net.Conn ends without touching
	// the network stack at all -- enough to prove NewSocket works with any
	// caller-supplied net.Conn (the seam a TLS dial would use), not just
	// one DialSocket produced itself.
	client, server := net.Pipe()
	defer server.Close()

	go func() { _, _ = io.Copy(server, server) }()

	s := NewSocket(client)
	defer s.Close(time.Second)

	assertEcho(t, s)
}

func assertEcho(t *testing.T, s *Socket) {
	t.Helper()
	if _, err := s.Write([]byte("ping")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(s, buf); err != nil {
		t.Fatalf("ReadFull: %v", err)
	}
	if string(buf) != "ping" {
		t.Errorf("echoed %q, want %q", buf, "ping")
	}
}

func TestSocketCloseUnblocksPendingRead(t *testing.T) {
	client, server := net.Pipe()
	defer server.Close()

	s := NewSocket(client)

	done := make(chan error, 1)
	go func() {
		_, err := s.Read(make([]byte, 1))
		done <- err
	}()

	// Give the goroutine a moment to actually block in Read before closing,
	// so this test exercises unblocking a pending read rather than a read
	// that hadn't started yet.
	time.Sleep(50 * time.Millisecond)

	start := time.Now()
	if err := s.Close(time.Second); err != nil {
		t.Fatalf("Close: %v", err)
	}

	select {
	case <-done:
		if elapsed := time.Since(start); elapsed > time.Second {
			t.Errorf("Close took %v to unblock the pending Read", elapsed)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("pending Read was not unblocked by Close")
	}
}
