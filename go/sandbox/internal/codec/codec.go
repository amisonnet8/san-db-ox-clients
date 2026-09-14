// Package codec implements the pure encode/decode half of the stdio
// protocol (.claude/rules/protocol.md): JSON Lines request/response framing
// and the value representations (BLOB, REAL, 64-bit integers) it specifies.
//
// This package must not import net, net/http, or os/exec, and must not know
// how a connection is transported (pipe, socket, ...). It only ever talks to
// io.Reader/io.Writer. `make go-netcheck` enforces the net/net-http half of
// that rule mechanically (.claude/rules/architecture.md).
package codec

import (
	"encoding/json"
	"fmt"
	"io"
)

// Protocol is the protocol number this driver implements
// (.claude/rules/protocol.md).
const Protocol = 1

// Error is a stdio protocol error response. Its Code is one of the five
// stable codes below; Message is upstream's own text and must never be
// branch-matched on (.claude/rules/protocol.md: "message の文言に依存した
// 分岐を書かない").
type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string {
	return fmt.Sprintf("sandbox: %s: %s", e.Code, e.Message)
}

// The five stable error codes (.claude/rules/protocol.md). Compare Error.Code
// against these, never Error.Message.
const (
	CodeSQLite        = "sqlite_error"
	CodeBadRequest    = "bad_request"
	CodeIOError       = "io_error"
	CodeUnsupportedOp = "unsupported_op"
	CodeReadOnly      = "read_only"
)

// Hello is the greeting line a SanDBox process sends before any request is
// sent (.claude/rules/protocol.md: "hello 行を必ず読む").
type Hello struct {
	Protocol int    `json:"protocol"`
	Version  string `json:"version"`
	Product  string `json:"product"`
}

// DecodeHello parses a hello line. It does not check the protocol number;
// callers must do that (a driver must refuse to talk further over a protocol
// number it doesn't know).
func DecodeHello(line []byte) (Hello, error) {
	var h Hello
	if err := json.Unmarshal(line, &h); err != nil {
		return Hello{}, fmt.Errorf("decoding hello line: %w", err)
	}
	return h, nil
}

// Request is the flat wire request object. Only the fields relevant to Op
// are set; the rest are omitted (mirrors upstream's own single stdioRequest
// struct, seen in a san-db-ox v0.1.0 panic trace during Phase 2).
type Request struct {
	Op        string            `json:"op"`
	SQL       string            `json:"sql,omitempty"`
	Params    []json.RawMessage `json:"params,omitempty"`
	Filename  string            `json:"filename,omitempty"`
	SQLite    bool              `json:"sqlite,omitempty"`
	Timestamp bool              `json:"timestamp,omitempty"`
	Path      string            `json:"path,omitempty"`
	Table     string            `json:"table,omitempty"`
	Pattern   string            `json:"pattern,omitempty"`
}

// WriteRequest encodes req as one JSON line and writes it in a single Write
// call. Callers must not wrap w in a buffered writer that isn't flushed
// after every write (.claude/rules/protocol.md: "1リクエスト送るごとに必ず
// フラッシュする") -- a single Write of a complete line onto the raw pipe
// satisfies that without needing an explicit flush step.
func WriteRequest(w io.Writer, req *Request) error {
	b, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("encoding request: %w", err)
	}
	b = append(b, '\n')
	_, err = w.Write(b)
	if err != nil {
		return fmt.Errorf("writing request: %w", err)
	}
	return nil
}

// DecodeResponseLine parses one response line into its "ok"/"error" envelope
// plus the full set of top-level fields (including "ok" and "error"
// themselves, which is convenient for the conformance runner's partial-match
// comparisons). When ok is false, errResp is non-nil.
func DecodeResponseLine(line []byte) (ok bool, fields map[string]json.RawMessage, errResp *Error, err error) {
	if unmarshalErr := json.Unmarshal(line, &fields); unmarshalErr != nil {
		return false, nil, nil, fmt.Errorf("decoding response line: %w", unmarshalErr)
	}
	if raw, present := fields["ok"]; present {
		if unmarshalErr := json.Unmarshal(raw, &ok); unmarshalErr != nil {
			return false, nil, nil, fmt.Errorf("decoding response line's ok field: %w", unmarshalErr)
		}
	}
	if !ok {
		if raw, present := fields["error"]; present {
			var e Error
			if unmarshalErr := json.Unmarshal(raw, &e); unmarshalErr != nil {
				return false, nil, nil, fmt.Errorf("decoding response line's error field: %w", unmarshalErr)
			}
			errResp = &e
		}
	}
	return ok, fields, errResp, nil
}
