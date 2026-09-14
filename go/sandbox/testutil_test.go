package sandbox_test

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// repoRoot returns the san-db-ox-clients repository root, computed from
// this package's own location (go/sandbox) rather than the working
// directory, so it works regardless of how `go test` is invoked.
func repoRoot(t *testing.T) string {
	t.Helper()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatalf("os.Getwd: %v", err)
	}
	return filepath.Join(wd, "..", "..")
}

// findBinary locates the san-db-ox binary to test against, following
// .claude/rules/testing.md: SAN_DB_OX_BIN overrides everything, otherwise
// fall back to the bin/ directory scripts/fetch-san-db-ox.sh populates.
// Tests that need a real binary call this and skip cleanly if one isn't
// available, rather than failing the whole suite for a missing fixture.
func findBinary(t *testing.T) string {
	t.Helper()
	if p := os.Getenv("SAN_DB_OX_BIN"); p != "" {
		return p
	}
	name := "san-db-ox"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	p := filepath.Join(repoRoot(t), "bin", name)
	if _, err := os.Stat(p); err != nil {
		t.Skipf("san-db-ox binary not found at %s; run `make fetch` or set SAN_DB_OX_BIN (.claude/rules/testing.md)", p)
	}
	return p
}
