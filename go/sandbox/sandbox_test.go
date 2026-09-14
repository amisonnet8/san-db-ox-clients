package sandbox_test

import (
	"context"
	"errors"
	"io"
	"math"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/amisonnet8/san-db-ox-clients/go/sandbox"
)

// callTimeout bounds every request in these tests
// (.claude/rules/testing.md: "すべての読み取りにタイムアウトを設ける").
const callTimeout = 10 * time.Second

func withTimeout(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), callTimeout)
	t.Cleanup(cancel)
	return ctx
}

// openClient opens a client against the fetched san-db-ox binary and
// registers a cleanup that closes it.
func openClient(t *testing.T, args ...string) *sandbox.Client {
	t.Helper()
	bin := findBinary(t)
	c, err := sandbox.Open(withTimeout(t), bin, args)
	if err != nil {
		t.Fatalf("sandbox.Open: %v", err)
	}
	t.Cleanup(func() {
		_ = c.Close(withTimeout(t))
	})
	return c
}

func TestOpenHello(t *testing.T) {
	c := openClient(t, "--serve-stdio")
	if c.Hello.Protocol != 1 {
		t.Errorf("Hello.Protocol = %d, want 1", c.Hello.Protocol)
	}
	if c.Hello.Product != "SanDBox" {
		t.Errorf("Hello.Product = %q, want SanDBox", c.Hello.Product)
	}
	if c.Hello.Version == "" {
		t.Error("Hello.Version is empty")
	}
}

func TestOpenRejectsUnknownCommand(t *testing.T) {
	_, err := sandbox.Open(withTimeout(t), "this-command-should-not-exist-anywhere", nil)
	if err == nil {
		t.Fatal("Open with a nonexistent command: want error, got nil")
	}
}

func TestQueryExecBLOBRoundTrip(t *testing.T) {
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	if _, err := c.Exec(ctx, "CREATE TABLE t(b BLOB)"); err != nil {
		t.Fatalf("Exec CREATE TABLE: %v", err)
	}
	blob := []byte("hi")
	res, err := c.Exec(ctx, "INSERT INTO t VALUES (?)", blob)
	if err != nil {
		t.Fatalf("Exec INSERT: %v", err)
	}
	if res.RowsAffected != 1 {
		t.Errorf("RowsAffected = %d, want 1", res.RowsAffected)
	}

	q, err := c.Query(ctx, "SELECT b FROM t")
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	if len(q.Rows) != 1 {
		t.Fatalf("Rows = %#v", q.Rows)
	}
	got, ok := q.Rows[0][0].([]byte)
	if !ok || string(got) != "hi" {
		t.Errorf("Rows[0][0] = %#v, want []byte(\"hi\")", q.Rows[0][0])
	}
}

func TestQueryLargeIntegerRoundTrip(t *testing.T) {
	// Regression guard for the params-large-integer bug fixed in v0.1.1
	// (amisonnet8/san-db-ox#1, PLAN.md) -- this only passes against a
	// server that decodes params through json.Number/int64, not float64.
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	if _, err := c.Exec(ctx, "CREATE TABLE big(n INTEGER)"); err != nil {
		t.Fatalf("Exec CREATE TABLE: %v", err)
	}
	const want = int64(9223372036854775807)
	if _, err := c.Exec(ctx, "INSERT INTO big VALUES (?)", want); err != nil {
		t.Fatalf("Exec INSERT: %v", err)
	}
	q, err := c.Query(ctx, "SELECT n, typeof(n) FROM big")
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	got, ok := q.Rows[0][0].(int64)
	if !ok || got != want {
		t.Errorf("n = %#v, want int64(%d)", q.Rows[0][0], want)
	}
	if q.Rows[0][1] != "integer" {
		t.Errorf("typeof(n) = %v, want integer", q.Rows[0][1])
	}
}

func TestQueryRealRepresentation(t *testing.T) {
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	q, err := c.Query(ctx, "SELECT 88.0, 1e308 * 10, -1e308 * 10, (1e308 * 10) - (1e308 * 10)")
	if err != nil {
		t.Fatalf("Query: %v", err)
	}
	row := q.Rows[0]
	if row[0] != 88.0 {
		t.Errorf("88.0 decoded as %#v", row[0])
	}
	if f, ok := row[1].(float64); !ok || !math.IsInf(f, 1) {
		t.Errorf("+Inf decoded as %#v", row[1])
	}
	if f, ok := row[2].(float64); !ok || !math.IsInf(f, -1) {
		t.Errorf("-Inf decoded as %#v", row[2])
	}
	if row[3] != nil {
		t.Errorf("NaN decoded as %#v, want nil (protocol.md: NaN is null)", row[3])
	}
}

func TestErrorCodes(t *testing.T) {
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	t.Run("sqlite_error", func(t *testing.T) {
		_, err := c.Query(ctx, "SELECT * FROM nope")
		assertCode(t, err, sandbox.CodeSQLite)
	})
	t.Run("io_error", func(t *testing.T) {
		err := c.Load(ctx, filepath.Join(t.TempDir(), "does-not-exist.db"))
		assertCode(t, err, sandbox.CodeIOError)
	})

	// The connection must survive all of the above
	// (.claude/rules/protocol.md: "不正なJSONを送っても接続は切れない").
	if _, err := c.Query(ctx, "SELECT 1"); err != nil {
		t.Errorf("Query after errors: %v", err)
	}
}

func assertCode(t *testing.T, err error, code string) {
	t.Helper()
	if err == nil {
		t.Fatalf("want error with code %s, got nil", code)
	}
	var sbErr *sandbox.Error
	if !errors.As(err, &sbErr) {
		t.Fatalf("error %v is not a *sandbox.Error", err)
	}
	if sbErr.Code != code {
		t.Errorf("Code = %s, want %s", sbErr.Code, code)
	}
}

func TestReadOnlyTwoTierRejection(t *testing.T) {
	// Verified against the real binary in Phase 2
	// (conformance/cases/read-only-mode.json) and now exercised through
	// the public API: op-level writes (overwrite/load/snapshot) are
	// rejected as read_only by the stdio server itself, while exec's
	// SQL-level writes are rejected by SQLite's own PRAGMA query_only and
	// surface as sqlite_error -- these are NOT the same code.
	ctx := withTimeout(t)
	c := openClient(t, "--read-only", "--serve-stdio")

	t.Run("exec write is sqlite_error", func(t *testing.T) {
		_, err := c.Exec(ctx, "CREATE TABLE t(x INTEGER)")
		assertCode(t, err, sandbox.CodeSQLite)
	})
	t.Run("overwrite is read_only", func(t *testing.T) {
		assertCode(t, c.Overwrite(ctx), sandbox.CodeReadOnly)
	})
	t.Run("load is read_only", func(t *testing.T) {
		err := c.Load(ctx, filepath.Join(t.TempDir(), "does-not-matter.db"))
		assertCode(t, err, sandbox.CodeReadOnly)
	})
	t.Run("snapshot is read_only", func(t *testing.T) {
		_, err := c.Snapshot(ctx, sandbox.WithSnapshotFilename(filepath.Join(t.TempDir(), "should-not-happen")))
		assertCode(t, err, sandbox.CodeReadOnly)
	})
	t.Run("reads still work", func(t *testing.T) {
		if _, err := c.Query(ctx, "SELECT 1"); err != nil {
			t.Errorf("Query: %v", err)
		}
	})
	t.Run("inspect reports read_only", func(t *testing.T) {
		insp, err := c.Inspect(ctx)
		if err != nil {
			t.Fatalf("Inspect: %v", err)
		}
		if !insp.ReadOnly {
			t.Error("Inspect.ReadOnly = false, want true")
		}
	})
}

func TestSnapshotLoad(t *testing.T) {
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	if _, err := c.Exec(ctx, "CREATE TABLE t(x INTEGER)"); err != nil {
		t.Fatalf("Exec CREATE TABLE: %v", err)
	}
	if _, err := c.Exec(ctx, "INSERT INTO t VALUES (99)"); err != nil {
		t.Fatalf("Exec INSERT: %v", err)
	}
	snapPath := filepath.Join(t.TempDir(), "snap1")
	snap, err := c.Snapshot(ctx, sandbox.WithSnapshotFilename(snapPath))
	if err != nil {
		t.Fatalf("Snapshot: %v", err)
	}
	if snap.Path != snapPath {
		t.Errorf("Snapshot.Path = %q, want %q", snap.Path, snapPath)
	}

	// Load it into a second, otherwise-fresh process.
	c2 := openClient(t, "--serve-stdio")
	if err := c2.Load(ctx, snapPath); err != nil {
		t.Fatalf("Load: %v", err)
	}
	q, err := c2.Query(ctx, "SELECT x FROM t")
	if err != nil {
		t.Fatalf("Query after Load: %v", err)
	}
	if len(q.Rows) != 1 || q.Rows[0][0] != int64(99) {
		t.Errorf("Rows after Load = %#v", q.Rows)
	}
}

func TestInspectReflectsOwnEmbeddedDataOnly(t *testing.T) {
	// Verified in Phase 2 (conformance/cases/introspection.json): Inspect
	// describes the running binary's own embedded snapshot, not live SQL
	// state changed via Exec/Load.
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	before, err := c.Inspect(ctx)
	if err != nil {
		t.Fatalf("Inspect: %v", err)
	}
	if before.HasData {
		t.Fatal("HasData = true on a freshly started process")
	}

	if _, err := c.Exec(ctx, "CREATE TABLE t(x INTEGER)"); err != nil {
		t.Fatalf("Exec: %v", err)
	}

	after, err := c.Inspect(ctx)
	if err != nil {
		t.Fatalf("Inspect: %v", err)
	}
	if after.HasData || after.Version != nil || after.DataLength != nil {
		t.Errorf("Inspect changed after Exec: %#v", after)
	}
}

func TestTablesSchemaDump(t *testing.T) {
	ctx := withTimeout(t)
	c := openClient(t, "--serve-stdio")

	if _, err := c.Exec(ctx, "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)"); err != nil {
		t.Fatalf("Exec: %v", err)
	}
	if _, err := c.Exec(ctx, "CREATE TABLE logs(id INTEGER PRIMARY KEY, msg TEXT)"); err != nil {
		t.Fatalf("Exec: %v", err)
	}
	if _, err := c.Exec(ctx, "INSERT INTO users(id, name) VALUES (1, 'alice')"); err != nil {
		t.Fatalf("Exec: %v", err)
	}

	tables, err := c.Tables(ctx)
	if err != nil {
		t.Fatalf("Tables: %v", err)
	}
	if len(tables.Tables) != 2 || tables.Tables[0] != "logs" || tables.Tables[1] != "users" {
		t.Errorf("Tables = %#v, want [logs users] (alphabetical)", tables.Tables)
	}

	schema, err := c.Schema(ctx, "users")
	if err != nil {
		t.Fatalf("Schema: %v", err)
	}
	if len(schema.Schema) != 1 {
		t.Errorf("Schema(users) = %#v", schema.Schema)
	}

	dump, err := c.Dump(ctx, "users")
	if err != nil {
		t.Fatalf("Dump: %v", err)
	}
	if dump.SQL == "" {
		t.Error("Dump.SQL is empty")
	}
}

func TestClose(t *testing.T) {
	bin := findBinary(t)
	ctx := withTimeout(t)
	c, err := sandbox.Open(ctx, bin, []string{"--serve-stdio"})
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := c.Close(ctx); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if code := c.ExitCode(); code != 0 {
		t.Errorf("ExitCode = %d, want 0", code)
	}
	// Closing twice must be safe.
	if err := c.Close(ctx); err != nil {
		t.Errorf("second Close: %v", err)
	}
}

func TestOverwrite(t *testing.T) {
	// Overwrite modifies the running process's own executable in place, so
	// this runs against a private copy of the fetched binary rather than
	// the shared bin/san-db-ox fixture other tests rely on.
	bin := findBinary(t)
	copyPath := filepath.Join(t.TempDir(), filepath.Base(bin))
	copyExecutable(t, bin, copyPath)

	ctx := withTimeout(t)
	c, err := sandbox.Open(ctx, copyPath, []string{"--serve-stdio"})
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if _, err := c.Exec(ctx, "CREATE TABLE t(x INTEGER)"); err != nil {
		t.Fatalf("Exec CREATE TABLE: %v", err)
	}
	if _, err := c.Exec(ctx, "INSERT INTO t VALUES (7)"); err != nil {
		t.Fatalf("Exec INSERT: %v", err)
	}
	if err := c.Overwrite(ctx); err != nil {
		t.Fatalf("Overwrite: %v", err)
	}
	if code := c.ExitCode(); code != 0 {
		t.Errorf("ExitCode after Overwrite = %d, want 0", code)
	}

	// The copy should now be a self-contained executable embedding the
	// data written above.
	c2, err := sandbox.Open(withTimeout(t), copyPath, []string{"--serve-stdio"})
	if err != nil {
		t.Fatalf("Open (post-overwrite): %v", err)
	}
	t.Cleanup(func() { _ = c2.Close(withTimeout(t)) })

	insp, err := c2.Inspect(ctx)
	if err != nil {
		t.Fatalf("Inspect (post-overwrite): %v", err)
	}
	if !insp.HasData {
		t.Error("HasData = false after Overwrite, want true")
	}

	q, err := c2.Query(ctx, "SELECT x FROM t")
	if err != nil {
		t.Fatalf("Query (post-overwrite): %v", err)
	}
	if len(q.Rows) != 1 || q.Rows[0][0] != int64(7) {
		t.Errorf("Rows (post-overwrite) = %#v", q.Rows)
	}
}

func copyExecutable(t *testing.T, src, dst string) {
	t.Helper()
	in, err := os.Open(src)
	if err != nil {
		t.Fatalf("opening %s: %v", src, err)
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		t.Fatalf("creating %s: %v", dst, err)
	}
	defer out.Close()
	if _, err := io.Copy(out, in); err != nil {
		t.Fatalf("copying %s to %s: %v", src, dst, err)
	}
}
