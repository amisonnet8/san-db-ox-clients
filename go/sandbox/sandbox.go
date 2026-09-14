// Package sandbox is a thin, direct-connect client for SanDBox
// (github.com/amisonnet8/san-db-ox): it launches the given command as a
// child process and talks to it over the stdio protocol
// (.claude/rules/protocol.md).
//
// A driver is a convenience, not a prerequisite -- the wire protocol is
// plain JSON Lines, and nothing here does more than give it a typed Go API
// (see the CLAUDE.md at the repository root). Op names mirror the
// protocol's own op names 1:1 (.claude/rules/naming.md): Query, Exec,
// Snapshot, Load, Inspect, Tables, Schema, Dump, Overwrite, Close.
//
// # Connecting anywhere the same way
//
// Open takes the command to launch, not a fixed "local" assumption, so the
// same call works locally, over SSH, through Docker, or via kubectl exec
// just by changing the command and args (.claude/rules/architecture.md,
// .claude/rules/connectivity.md):
//
//	sandbox.Open(ctx, "san-db-ox", []string{"--serve-stdio"})
//	sandbox.Open(ctx, "ssh", []string{"user@host", "san-db-ox", "--serve-stdio"})
//	sandbox.Open(ctx, "docker", []string{"run", "-i", "--rm", image, "--serve-stdio"})
//
// There are no SSH- or Docker-specific constructors.
package sandbox

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/codec"
	"github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/transport"
)

// supportedProtocol is the only protocol number this driver will talk to
// (.claude/rules/protocol.md: "protocol が自分の知らない番号であれば、
// それ以上通信せずエラーとする").
const supportedProtocol = codec.Protocol

// closeTimeout is how long each stage of the staged shutdown
// (.claude/rules/testing.md) waits before escalating.
const closeTimeout = 5 * time.Second

// Client is a direct-connect connection to a running SanDBox process.
// A Client is not safe for concurrent use by multiple goroutines: the
// protocol has no request id, so responses can only be matched to
// requests by strict ordering (.claude/rules/protocol.md) -- Client
// serializes calls with an internal lock rather than exposing that
// footgun.
type Client struct {
	Hello Hello

	d      *transport.Direct
	lines  chan lineOrErr
	mu     sync.Mutex
	closed bool
}

type lineOrErr struct {
	line []byte
	err  error
}

// Option configures Open.
type Option func(*openOptions)

type openOptions struct {
	transportOpts []transport.Option
}

// WithStderr copies the child process's stderr to w. Stderr is always
// drained regardless (.claude/rules/protocol.md) -- this only controls
// where the drained bytes go, e.g. for logging or diagnostics.
func WithStderr(w io.Writer) Option {
	return func(o *openOptions) {
		o.transportOpts = append(o.transportOpts, transport.WithStderr(w))
	}
}

// WithEnv sets the child process's environment (nil means inherit the
// current process's environment, matching os/exec.Cmd.Env).
func WithEnv(env []string) Option {
	return func(o *openOptions) {
		o.transportOpts = append(o.transportOpts, transport.WithEnv(env))
	}
}

// WithDir sets the child process's working directory.
func WithDir(dir string) Option {
	return func(o *openOptions) {
		o.transportOpts = append(o.transportOpts, transport.WithDir(dir))
	}
}

// Open launches name with args as a child process, reads its hello line,
// and returns a ready-to-use Client. ctx bounds only the connection
// attempt (spawning the process and reading the hello line); it is not
// retained for later calls -- each method below takes its own ctx.
func Open(ctx context.Context, name string, args []string, opts ...Option) (*Client, error) {
	var o openOptions
	for _, opt := range opts {
		opt(&o)
	}

	d, err := transport.StartDirect(ctx, name, args, o.transportOpts...)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}

	c := &Client{d: d, lines: make(chan lineOrErr, 1)}
	go c.readLoop()

	helloLine, err := c.readLine(ctx)
	if err != nil {
		_ = d.Close(closeTimeout)
		return nil, fmt.Errorf("sandbox: reading hello line: %w", err)
	}
	hello, err := codec.DecodeHello(helloLine)
	if err != nil {
		_ = d.Close(closeTimeout)
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	if hello.Protocol != supportedProtocol {
		_ = d.Close(closeTimeout)
		return nil, fmt.Errorf("sandbox: unsupported protocol %d (this driver speaks %d)", hello.Protocol, supportedProtocol)
	}
	c.Hello = Hello(hello)
	return c, nil
}

// readLoop is the Client's single background reader: responses are framed
// one per line and always arrive in request order
// (.claude/rules/protocol.md), so one goroutine feeding a channel is
// enough -- callers never need to race independent reads against each
// other.
func (c *Client) readLoop() {
	sc := newLineScanner(c.d.Reader())
	for sc.Scan() {
		line := append([]byte(nil), sc.Bytes()...) // Scanner reuses its buffer.
		c.lines <- lineOrErr{line: line}
	}
	err := sc.Err()
	if err == nil {
		err = io.EOF
	}
	c.lines <- lineOrErr{err: err}
	close(c.lines)
}

func (c *Client) readLine(ctx context.Context) ([]byte, error) {
	select {
	case le, ok := <-c.lines:
		if !ok {
			return nil, io.ErrClosedPipe
		}
		if le.err != nil {
			return nil, le.err
		}
		return le.line, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// call sends req and returns the decoded response's fields. On success,
// fields includes every top-level field of the response (including "ok"),
// which is what the conformance runner needs for partial-match comparisons;
// typed callers below only look at the fields relevant to their op.
func (c *Client) call(ctx context.Context, req *codec.Request) (codec.Fields, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.closed {
		return nil, fmt.Errorf("sandbox: %w", io.ErrClosedPipe)
	}

	if err := codec.WriteRequest(c.d.Writer(), req); err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}

	line, err := c.readLine(ctx)
	if err != nil {
		if ctx.Err() != nil {
			// The read is stuck; there is no way to interrupt a single
			// pending pipe Read short of tearing down the connection, so
			// do that -- the caller asked to give up, and a Client that
			// can never be used again is a fair price to guarantee no
			// goroutine or process is leaked.
			c.closed = true
			go c.d.Close(closeTimeout)
		}
		return nil, fmt.Errorf("sandbox: reading response: %w", err)
	}

	ok, fields, errResp, err := codec.DecodeResponseLine(line)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	if !ok {
		if errResp != nil {
			return fields, errResp
		}
		return fields, fmt.Errorf("sandbox: response has ok=false with no error field")
	}
	return fields, nil
}

// Query runs a SQL query and returns its result set in full (the protocol
// has no cursor -- .claude/rules/protocol.md deliberately does not offer
// one, see "こちら側で勝手に足さないもの").
func (c *Client) Query(ctx context.Context, sql string, params ...any) (*QueryResult, error) {
	encodedParams, err := codec.EncodeParams(params)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	fields, err := c.call(ctx, &codec.Request{Op: "query", SQL: sql, Params: encodedParams})
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeQueryResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	rows := make([]Row, len(resp.Rows))
	for i, r := range resp.Rows {
		rows[i] = Row(r)
	}
	return &QueryResult{Columns: resp.Columns, Rows: rows}, nil
}

// Exec runs a SQL statement and returns rows affected / last insert id.
func (c *Client) Exec(ctx context.Context, sql string, params ...any) (*ExecResult, error) {
	encodedParams, err := codec.EncodeParams(params)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	fields, err := c.call(ctx, &codec.Request{Op: "exec", SQL: sql, Params: encodedParams})
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeExecResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	return &ExecResult{RowsAffected: resp.RowsAffected, LastInsertID: resp.LastInsertID}, nil
}

// SnapshotOption configures Snapshot.
type SnapshotOption func(*codec.Request)

// WithSnapshotFilename sets snapshot's optional filename parameter.
func WithSnapshotFilename(name string) SnapshotOption {
	return func(r *codec.Request) { r.Filename = name }
}

// WithSnapshotSQLite requests a plain SQLite file instead of a
// self-contained SanDBox executable.
func WithSnapshotSQLite(sqlite bool) SnapshotOption {
	return func(r *codec.Request) { r.SQLite = sqlite }
}

// WithSnapshotTimestamp appends a timestamp to the saved filename.
func WithSnapshotTimestamp(timestamp bool) SnapshotOption {
	return func(r *codec.Request) { r.Timestamp = timestamp }
}

// Snapshot saves the current database and returns the path written.
func (c *Client) Snapshot(ctx context.Context, opts ...SnapshotOption) (*SnapshotResult, error) {
	req := &codec.Request{Op: "snapshot"}
	for _, opt := range opts {
		opt(req)
	}
	fields, err := c.call(ctx, req)
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeSnapshotResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	return &SnapshotResult{Path: resp.Path}, nil
}

// Load replaces the running database with the one at path.
func (c *Client) Load(ctx context.Context, path string) error {
	_, err := c.call(ctx, &codec.Request{Op: "load", Path: path})
	return err
}

// Inspect reports on the running process's own embedded data. It is not a
// general-purpose "inspect any path" op -- it only ever describes this
// process (.claude/rules/protocol.md).
func (c *Client) Inspect(ctx context.Context) (*InspectResult, error) {
	fields, err := c.call(ctx, &codec.Request{Op: "inspect"})
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeInspectResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	return &InspectResult{
		HasData:    resp.HasData,
		Version:    resp.Version,
		DataLength: resp.DataLength,
		Source:     resp.Source,
		ReadOnly:   resp.ReadOnly,
	}, nil
}

// Tables lists table names.
func (c *Client) Tables(ctx context.Context) (*TablesResult, error) {
	fields, err := c.call(ctx, &codec.Request{Op: "tables"})
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeTablesResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	return &TablesResult{Tables: resp.Tables}, nil
}

// Schema returns CREATE statements, optionally filtered to one table.
func (c *Client) Schema(ctx context.Context, table string) (*SchemaResult, error) {
	fields, err := c.call(ctx, &codec.Request{Op: "schema", Table: table})
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeSchemaResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	return &SchemaResult{Schema: resp.Schema}, nil
}

// Dump returns a SQL dump, optionally filtered by pattern (defaults to
// "%", i.e. everything, when pattern is empty).
func (c *Client) Dump(ctx context.Context, pattern string) (*DumpResult, error) {
	fields, err := c.call(ctx, &codec.Request{Op: "dump", Pattern: pattern})
	if err != nil {
		return nil, err
	}
	resp, err := codec.DecodeDumpResponse(fields)
	if err != nil {
		return nil, fmt.Errorf("sandbox: %w", err)
	}
	return &DumpResult{SQL: resp.SQL}, nil
}

// Overwrite replaces the running process's own executable with one
// embedding the current database, then exits. This only makes sense over a
// direct-connect process (.claude/rules/architecture.md: "overwrite は
// socat 経由で使わせない") -- it lives here on Client precisely because
// Client is direct-connect-only for now (Phase 4 will need to keep it off
// whatever type the socket transport introduces).
func (c *Client) Overwrite(ctx context.Context) error {
	if _, err := c.call(ctx, &codec.Request{Op: "overwrite"}); err != nil {
		return err
	}
	// A successful overwrite ends the connection from the server's side
	// (.claude/rules/protocol.md: "応答後にプロセス終了"); reap it so no
	// zombie is left behind.
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	return c.d.Close(closeTimeout)
}

// Close gracefully ends the connection: it sends the close op, and
// regardless of whether a response arrives, follows through with the
// staged process shutdown .claude/rules/testing.md requires (stdin close,
// then SIGTERM, then SIGKILL) so the child is never left running. Close is
// safe to call more than once.
func (c *Client) Close(ctx context.Context) error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	// Best effort: send the close op so a well-behaved server exits
	// promptly. Its response (or the lack of one) doesn't change what
	// happens next -- the staged shutdown below reaps the process either
	// way.
	_ = codec.WriteRequest(c.d.Writer(), &codec.Request{Op: "close"})
	c.mu.Unlock()

	return c.d.Close(closeTimeout)
}

// ExitCode returns the child process's exit code. Only meaningful after
// Close or Overwrite has returned.
func (c *Client) ExitCode() int {
	return c.d.ExitCode()
}

// maxLineSize is sized for the protocol's stated line limit
// (.claude/rules/protocol.md: "1行の上限は本体実装で 1 MiB"), with
// headroom above it -- the default 64KiB bufio.Scanner buffer would
// otherwise fail on a large query result well within the documented limit.
const maxLineSize = 2 * 1024 * 1024

// newLineScanner returns a bufio.Scanner sized for maxLineSize.
func newLineScanner(r io.Reader) *bufio.Scanner {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), maxLineSize)
	return sc
}
