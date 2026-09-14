// Package transport implements the direct-connect transport
// (.claude/rules/architecture.md): spawning a child process and talking to
// it over stdin/stdout. It knows nothing about the JSON Lines protocol
// itself -- codec sits on top of this, talking only to the io.Reader/
// io.Writer this package hands back.
//
// The command to launch is entirely caller-supplied (name + args), which is
// what lets local, SSH, Docker, and kubectl-exec connections all go through
// this one transport (.claude/rules/architecture.md, .claude/rules/
// connectivity.md) -- there is no SSH- or Docker-specific constructor here.
package transport

import (
	"context"
	"fmt"
	"io"
	"os/exec"
	"syscall"
	"time"
)

// Direct is a running child process wired up for the stdio protocol.
type Direct struct {
	cmd        *exec.Cmd
	stdin      io.WriteCloser
	stdout     io.ReadCloser
	stderrDone chan struct{}
}

// Option configures StartDirect.
type Option func(*options)

type options struct {
	stderr io.Writer
	env    []string
	dir    string
}

// WithStderr copies the child's stderr to w instead of discarding it. The
// transport always drains stderr regardless (.claude/rules/protocol.md:
// "子プロセスの stderr を塞がない") -- this only controls where the drained
// bytes go.
func WithStderr(w io.Writer) Option {
	return func(o *options) { o.stderr = w }
}

// WithEnv sets the child process's environment (same semantics as
// exec.Cmd.Env: nil means inherit the current process's environment).
func WithEnv(env []string) Option {
	return func(o *options) { o.env = env }
}

// WithDir sets the child process's working directory.
func WithDir(dir string) Option {
	return func(o *options) { o.dir = dir }
}

// StartDirect launches name with args as a child process and wires up its
// stdin/stdout for the stdio protocol. ctx governs the process's lifetime:
// canceling it will kill the process (exec.CommandContext semantics).
func StartDirect(ctx context.Context, name string, args []string, opts ...Option) (*Direct, error) {
	var o options
	for _, opt := range opts {
		opt(&o)
	}

	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Env = o.env
	cmd.Dir = o.dir

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("sandbox/transport: stdin pipe: %w", err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("sandbox/transport: stdout pipe: %w", err)
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, fmt.Errorf("sandbox/transport: stderr pipe: %w", err)
	}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("sandbox/transport: starting %s: %w", name, err)
	}

	d := &Direct{
		cmd:        cmd,
		stdin:      stdin,
		stdout:     stdout,
		stderrDone: make(chan struct{}),
	}

	sink := o.stderr
	if sink == nil {
		sink = io.Discard
	}
	go func() {
		// Draining stderr to completion is a hard contract, not a
		// convenience: leaving it unread risks the child blocking once
		// its stderr pipe buffer fills (.claude/rules/protocol.md).
		_, _ = io.Copy(sink, stderr)
		close(d.stderrDone)
	}()

	return d, nil
}

// Reader returns the child's stdout.
func (d *Direct) Reader() io.Reader { return d.stdout }

// Writer returns the child's stdin. Each Write is expected to be a
// complete, already-newline-terminated request line -- see codec.
// WriteRequest, which writes exactly that in one call so no separate flush
// step is needed.
func (d *Direct) Writer() io.Writer { return d.stdin }

// Close performs the staged shutdown .claude/rules/testing.md calls for:
// close stdin, wait, and if the process is still alive after timeout,
// escalate to SIGTERM and then SIGKILL. It is safe to call after the
// process has already exited on its own (e.g. after a successful
// `overwrite`, which the protocol has already dictated ends the
// connection).
func (d *Direct) Close(timeout time.Duration) error {
	_ = d.stdin.Close()

	done := make(chan error, 1)
	go func() { done <- d.cmd.Wait() }()

	if err, ok := d.waitFor(done, timeout); ok {
		<-d.stderrDone
		return err
	}

	// SIGTERM has no real equivalent on Windows; os.Process.Signal there
	// only supports os.Kill and os.Interrupt. Ignoring the error here and
	// falling through to Kill below still gets the process reaped -- it
	// just skips straight to the forceful step on platforms without
	// SIGTERM.
	_ = d.cmd.Process.Signal(syscall.SIGTERM)
	if err, ok := d.waitFor(done, timeout); ok {
		<-d.stderrDone
		return err
	}

	_ = d.cmd.Process.Kill()
	err := <-done
	<-d.stderrDone
	return err
}

func (d *Direct) waitFor(done <-chan error, timeout time.Duration) (error, bool) {
	select {
	case err := <-done:
		return err, true
	case <-time.After(timeout):
		return nil, false
	}
}

// ExitCode returns the exited child process's exit code. It is only
// meaningful after Close has returned (.claude/rules/protocol.md: "EOF は
// 接続断を意味する。検出して子プロセスの終了コードを回収すること") --
// before that, or if the process could not be started, it returns -1.
func (d *Direct) ExitCode() int {
	if d.cmd.ProcessState == nil {
		return -1
	}
	return d.cmd.ProcessState.ExitCode()
}
