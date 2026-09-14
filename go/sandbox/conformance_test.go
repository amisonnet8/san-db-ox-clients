package sandbox_test

// TestConformanceSuite is the Go-side test runner for the language-agnostic
// case definitions in conformance/cases/ (conformance/README_ja.md). It
// talks to the real san-db-ox binary through the internal codec/transport
// layers directly, rather than through the public sandbox.Client API,
// because cases can send arbitrary or deliberately malformed requests
// (unknown ops, wrong-typed params) that the typed public API has no way
// to construct.

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/transport"
)

type conformanceCase struct {
	Name         string            `json:"name"`
	Description  string            `json:"description"`
	KnownFailing string            `json:"known_failing"`
	Args         []string          `json:"args"`
	Steps        []conformanceStep `json:"steps"`
}

type conformanceStep struct {
	// Request/Expect are captured as raw bytes rather than decoded into a
	// generic Go value, which sidesteps the case-file-parsing pitfall
	// conformance/README_ja.md warns about (a naive number decoder
	// corrupting a large integer literal at file-read time, before the
	// case is ever sent) -- json.RawMessage preserves the literal text
	// verbatim.
	Request   json.RawMessage `json:"request"`
	Expect    json.RawMessage `json:"expect"`
	ExpectRaw []string        `json:"expect_raw"`
}

func TestConformanceSuite(t *testing.T) {
	bin := findBinary(t)
	casesDir := filepath.Join(repoRoot(t), "conformance", "cases")
	entries, err := os.ReadDir(casesDir)
	if err != nil {
		t.Fatalf("reading %s: %v", casesDir, err)
	}

	found := 0
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".json") {
			continue
		}
		found++
		kase := loadConformanceCase(t, filepath.Join(casesDir, entry.Name()))
		t.Run(kase.Name, func(t *testing.T) {
			runConformanceCase(t, bin, kase)
		})
	}
	if found == 0 {
		t.Fatalf("no *.json cases found under %s", casesDir)
	}
}

func loadConformanceCase(t *testing.T, path string) conformanceCase {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("reading %s: %v", path, err)
	}
	var kase conformanceCase
	if err := json.Unmarshal(b, &kase); err != nil {
		t.Fatalf("parsing %s: %v", path, err)
	}
	return kase
}

func runConformanceCase(t *testing.T, bin string, kase conformanceCase) {
	t.Helper()
	if kase.Description != "" {
		t.Log(kase.Description)
	}
	if kase.KnownFailing != "" {
		t.Logf("known_failing: %s", kase.KnownFailing)
	}

	ctx, cancel := context.WithTimeout(context.Background(), callTimeout)
	defer cancel()

	args := append([]string{"--serve-stdio"}, kase.Args...)
	d, err := transport.StartDirect(ctx, bin, args)
	if err != nil {
		t.Fatalf("StartDirect: %v", err)
	}
	defer d.Close(5 * time.Second)

	sc := newConformanceScanner(d)
	if _, err := scan(ctx, sc, d); err != nil {
		t.Fatalf("reading hello line: %v", err)
	}

	for i, step := range kase.Steps {
		step := step
		t.Run(fmt.Sprintf("step-%d", i), func(t *testing.T) {
			checkFail := t.Errorf
			if kase.KnownFailing != "" {
				// xfail (conformance/README_ja.md): run for real, but a
				// wrong value doesn't fail the suite. A transport-level
				// failure below (write/read error, timeout) still does --
				// that would mean the bug's nature changed, which is
				// exactly what known_failing is supposed to catch.
				checkFail = t.Logf
			}

			line := append(append([]byte(nil), step.Request...), '\n')
			if _, err := d.Write(line); err != nil {
				t.Fatalf("writing request %s: %v", step.Request, err)
			}
			raw, err := scan(ctx, sc, d)
			if err != nil {
				t.Fatalf("reading response to %s: %v", step.Request, err)
			}

			if len(step.Expect) > 0 {
				if ok, msg := matchJSON(step.Expect, raw); !ok {
					checkFail("expect mismatch: %s\n  request:  %s\n  response: %s", msg, step.Request, raw)
				}
			}
			for _, want := range step.ExpectRaw {
				if !strings.Contains(string(raw), want) {
					checkFail("expect_raw %q not found in response: %s", want, raw)
				}
			}
		})
	}
}

// newConformanceScanner sizes the scanner for the protocol's stated 1MiB
// line limit, same as sandbox.Client's own reader
// (.claude/rules/protocol.md).
func newConformanceScanner(r io.Reader) *bufio.Scanner {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 2*1024*1024)
	return sc
}

// scan reads one line with a hang-detection timeout
// (.claude/rules/testing.md: "すべての読み取りにタイムアウトを設ける"). On
// timeout it kills the process so the blocked read (and this goroutine)
// unblock rather than leaking.
func scan(ctx context.Context, sc *bufio.Scanner, d *transport.Direct) ([]byte, error) {
	done := make(chan bool, 1)
	go func() { done <- sc.Scan() }()
	select {
	case ok := <-done:
		if !ok {
			if err := sc.Err(); err != nil {
				return nil, err
			}
			return nil, io.EOF
		}
		return append([]byte(nil), sc.Bytes()...), nil
	case <-ctx.Done():
		_ = d.Close(2 * time.Second)
		return nil, ctx.Err()
	}
}

// matchJSON implements conformance/README_ja.md's partial-match rule:
// a JSON *object* matches partially (only keys present in expect are
// checked, recursively); anything else (array, string, number, bool, null)
// must match exactly. Numbers are compared as their literal decimal text
// (via json.Number) rather than as float64, so a large integer or a
// REAL's "88.0" vs "88" distinction isn't lost to (or hidden by) a lossy
// comparison.
func matchJSON(expect json.RawMessage, actual json.RawMessage) (bool, string) {
	expVal, err := decodeNumberSafe(expect)
	if err != nil {
		return false, fmt.Sprintf("bad expect JSON: %v", err)
	}
	return matchValue(expVal, actual)
}

func decodeNumberSafe(raw json.RawMessage) (any, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	return v, nil
}

func matchValue(expect any, actualRaw json.RawMessage) (bool, string) {
	switch exp := expect.(type) {
	case map[string]any:
		var actual map[string]json.RawMessage
		if err := json.Unmarshal(actualRaw, &actual); err != nil {
			return false, fmt.Sprintf("expected an object, got %s", actualRaw)
		}
		for key, expSub := range exp {
			actSub, present := actual[key]
			if !present {
				return false, fmt.Sprintf("missing key %q in %s", key, actualRaw)
			}
			if ok, msg := matchValue(expSub, actSub); !ok {
				return false, fmt.Sprintf("%q: %s", key, msg)
			}
		}
		return true, ""
	case []any:
		var actual []json.RawMessage
		if err := json.Unmarshal(actualRaw, &actual); err != nil {
			return false, fmt.Sprintf("expected an array, got %s", actualRaw)
		}
		if len(actual) != len(exp) {
			return false, fmt.Sprintf("array length %d, want %d (%s)", len(actual), len(exp), actualRaw)
		}
		for i, expSub := range exp {
			if ok, msg := matchValue(expSub, actual[i]); !ok {
				return false, fmt.Sprintf("[%d]: %s", i, msg)
			}
		}
		return true, ""
	case json.Number:
		var actual json.Number
		if err := json.Unmarshal(actualRaw, &actual); err != nil {
			return false, fmt.Sprintf("expected a number, got %s", actualRaw)
		}
		if actual.String() != exp.String() {
			return false, fmt.Sprintf("number %s, want %s", actual, exp)
		}
		return true, ""
	case string:
		var actual string
		if err := json.Unmarshal(actualRaw, &actual); err != nil {
			return false, fmt.Sprintf("expected a string, got %s", actualRaw)
		}
		if actual != exp {
			return false, fmt.Sprintf("string %q, want %q", actual, exp)
		}
		return true, ""
	case bool:
		var actual bool
		if err := json.Unmarshal(actualRaw, &actual); err != nil {
			return false, fmt.Sprintf("expected a bool, got %s", actualRaw)
		}
		if actual != exp {
			return false, fmt.Sprintf("bool %v, want %v", actual, exp)
		}
		return true, ""
	case nil:
		if strings.TrimSpace(string(actualRaw)) != "null" {
			return false, fmt.Sprintf("expected null, got %s", actualRaw)
		}
		return true, ""
	default:
		return false, fmt.Sprintf("unsupported expect value type %T", expect)
	}
}
