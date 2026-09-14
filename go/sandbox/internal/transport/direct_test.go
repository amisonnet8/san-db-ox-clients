package transport

import (
	"bufio"
	"bytes"
	"context"
	"runtime"
	"strings"
	"testing"
	"time"
)

// skipOnWindows marks fixtures that rely on POSIX shell utilities and real
// SIGTERM semantics, neither of which this package's staged-shutdown
// contract can rely on on Windows (see Direct.Close's comment on
// syscall.SIGTERM there).
func skipOnWindows(t *testing.T) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("this fixture needs a POSIX shell and SIGTERM")
	}
}

func TestStartDirectEchoesStdinToStdout(t *testing.T) {
	skipOnWindows(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	d, err := StartDirect(ctx, "cat", nil)
	if err != nil {
		t.Fatalf("StartDirect: %v", err)
	}
	defer d.Close(2 * time.Second)

	if _, err := d.Writer().Write([]byte("hello\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	r := bufio.NewReader(d.Reader())
	line, err := r.ReadString('\n')
	if err != nil {
		t.Fatalf("ReadString: %v", err)
	}
	if line != "hello\n" {
		t.Errorf("read %q, want %q", line, "hello\n")
	}
}

func TestStartDirectDrainsStderrWithoutBlocking(t *testing.T) {
	skipOnWindows(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// A process that writes a lot to stderr before echoing stdin back.
	// If this package failed to drain stderr concurrently, the child would
	// block once its stderr pipe buffer filled, and this test would hang
	// until the outer timeout -- this is the trap
	// .claude/rules/testing.md calls the easiest one to fall into.
	script := "i=0; while [ $i -lt 20000 ]; do echo \"warning $i\" >&2; i=$((i+1)); done; cat"
	var stderr bytes.Buffer
	d, err := StartDirect(ctx, "sh", []string{"-c", script}, WithStderr(&stderr))
	if err != nil {
		t.Fatalf("StartDirect: %v", err)
	}
	defer d.Close(2 * time.Second)

	if _, err := d.Writer().Write([]byte("still alive\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	r := bufio.NewReader(d.Reader())
	line, err := r.ReadString('\n')
	if err != nil {
		t.Fatalf("ReadString (likely means stderr blocked the child): %v", err)
	}
	if line != "still alive\n" {
		t.Errorf("read %q, want %q", line, "still alive\n")
	}

	if err := d.Close(2 * time.Second); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if !strings.Contains(stderr.String(), "warning 19999") {
		t.Error("stderr sink did not receive the full drained output")
	}
}

func TestDirectCloseReapsProcessOnStdinClose(t *testing.T) {
	skipOnWindows(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// cat exits cleanly once its stdin is closed -- Close should reap it
	// in the first stage, without needing to escalate to SIGTERM.
	d, err := StartDirect(ctx, "cat", nil)
	if err != nil {
		t.Fatalf("StartDirect: %v", err)
	}

	start := time.Now()
	if err := d.Close(2 * time.Second); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Errorf("Close took %v; want it to reap promptly on stdin close, not wait out the escalation timeout", elapsed)
	}
	if code := d.ExitCode(); code != 0 {
		t.Errorf("ExitCode = %d, want 0", code)
	}
}

func TestDirectCloseEscalatesToSIGTERM(t *testing.T) {
	skipOnWindows(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// A process that ignores stdin entirely (never exits on EOF) but has
	// installed no SIGTERM trap, so the default action (terminate) applies.
	d, err := StartDirect(ctx, "sh", []string{"-c", "while true; do sleep 0.05; done"})
	if err != nil {
		t.Fatalf("StartDirect: %v", err)
	}

	start := time.Now()
	if err := d.Close(200 * time.Millisecond); err != nil {
		// The process is expected to die by signal, which Cmd.Wait
		// reports as an error (*exec.ExitError) -- that is success here,
		// not a test failure.
		t.Logf("Close returned (expected, process died by signal): %v", err)
	}
	elapsed := time.Since(start)
	if elapsed < 200*time.Millisecond {
		t.Errorf("Close returned in %v, before the first escalation timeout even elapsed", elapsed)
	}
	if elapsed > 5*time.Second {
		t.Errorf("Close took %v; SIGTERM escalation should have ended it quickly", elapsed)
	}
}
