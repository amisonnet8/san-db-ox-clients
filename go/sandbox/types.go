package sandbox

// Row is one query result row. Each element is nil, int64, float64,
// string, or []byte, matching SQLite's NULL/INTEGER/REAL/TEXT/BLOB storage
// classes. A REAL that is NaN is indistinguishable from SQL NULL once
// decoded -- both arrive over the wire as the JSON literal null (that is
// upstream's own representation, not a limitation added here).
type Row []any

// QueryResult is query's result.
type QueryResult struct {
	Columns []string
	Rows    []Row
}

// ExecResult is exec's result.
type ExecResult struct {
	RowsAffected int64
	LastInsertID int64
}

// SnapshotResult is snapshot's result.
type SnapshotResult struct {
	Path string
}

// InspectResult is inspect's result. It describes the running process's
// own embedded data, not the live SQL state -- CREATE TABLE/INSERT/load
// against a running process do not change what a later Inspect reports.
// Version and DataLength are nil when the process has no embedded
// snapshot data.
type InspectResult struct {
	HasData    bool
	Version    *int64
	DataLength *int64
	Source     string
	ReadOnly   bool
}

// TablesResult is tables's result.
type TablesResult struct {
	Tables []string
}

// SchemaResult is schema's result.
type SchemaResult struct {
	Schema []string
}

// DumpResult is dump's result.
type DumpResult struct {
	SQL string
}

// Hello is the greeting a SanDBox process sends before any request.
type Hello struct {
	Protocol int
	Version  string
	Product  string
}
