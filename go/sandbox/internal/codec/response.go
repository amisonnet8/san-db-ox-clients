package codec

import (
	"encoding/json"
	"fmt"
	"strconv"
)

// Fields is the decoded set of top-level fields from one response line
// (see DecodeResponseLine). The op-specific decoders below each pull the
// fields relevant to one op out of it.
type Fields = map[string]json.RawMessage

func rawField(fields Fields, name string) (json.RawMessage, bool) {
	raw, ok := fields[name]
	return raw, ok
}

func decodeString(fields Fields, name string) (string, error) {
	raw, ok := rawField(fields, name)
	if !ok {
		return "", fmt.Errorf("response missing field %q", name)
	}
	var s string
	if err := json.Unmarshal(raw, &s); err != nil {
		return "", fmt.Errorf("decoding field %q: %w", name, err)
	}
	return s, nil
}

func decodeStringSlice(fields Fields, name string) ([]string, error) {
	raw, ok := rawField(fields, name)
	if !ok {
		return nil, fmt.Errorf("response missing field %q", name)
	}
	var s []string
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, fmt.Errorf("decoding field %q: %w", name, err)
	}
	return s, nil
}

func decodeBool(fields Fields, name string) (bool, error) {
	raw, ok := rawField(fields, name)
	if !ok {
		return false, fmt.Errorf("response missing field %q", name)
	}
	var b bool
	if err := json.Unmarshal(raw, &b); err != nil {
		return false, fmt.Errorf("decoding field %q: %w", name, err)
	}
	return b, nil
}

// decodeInt64 decodes an INTEGER-typed response field (never through
// float64, same discipline as cell values).
func decodeInt64(fields Fields, name string) (int64, error) {
	raw, ok := rawField(fields, name)
	if !ok {
		return 0, fmt.Errorf("response missing field %q", name)
	}
	var num json.Number
	if err := json.Unmarshal(raw, &num); err != nil {
		return 0, fmt.Errorf("decoding field %q: %w", name, err)
	}
	n, err := strconv.ParseInt(num.String(), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("decoding field %q as int64: %w", name, err)
	}
	return n, nil
}

// decodeNullableInt64 decodes an INTEGER-typed response field that may be
// JSON null (inspect's version/data_length).
func decodeNullableInt64(fields Fields, name string) (*int64, error) {
	raw, ok := rawField(fields, name)
	if !ok {
		return nil, fmt.Errorf("response missing field %q", name)
	}
	if string(raw) == "null" {
		return nil, nil
	}
	var num json.Number
	if err := json.Unmarshal(raw, &num); err != nil {
		return nil, fmt.Errorf("decoding field %q: %w", name, err)
	}
	n, err := strconv.ParseInt(num.String(), 10, 64)
	if err != nil {
		return nil, fmt.Errorf("decoding field %q as int64: %w", name, err)
	}
	return &n, nil
}

// QueryResponse is query's decoded result fields.
type QueryResponse struct {
	Columns []string
	Rows    [][]any
}

func DecodeQueryResponse(fields Fields) (*QueryResponse, error) {
	cols, err := decodeStringSlice(fields, "columns")
	if err != nil {
		return nil, err
	}
	rawRows, ok := rawField(fields, "rows")
	if !ok {
		return nil, fmt.Errorf("response missing field %q", "rows")
	}
	rows, err := DecodeRows(rawRows)
	if err != nil {
		return nil, err
	}
	return &QueryResponse{Columns: cols, Rows: rows}, nil
}

// ExecResponse is exec's decoded result fields.
type ExecResponse struct {
	RowsAffected int64
	LastInsertID int64
}

func DecodeExecResponse(fields Fields) (*ExecResponse, error) {
	rowsAffected, err := decodeInt64(fields, "rows_affected")
	if err != nil {
		return nil, err
	}
	lastInsertID, err := decodeInt64(fields, "last_insert_id")
	if err != nil {
		return nil, err
	}
	return &ExecResponse{RowsAffected: rowsAffected, LastInsertID: lastInsertID}, nil
}

// SnapshotResponse is snapshot's decoded result fields.
type SnapshotResponse struct {
	Path string
}

func DecodeSnapshotResponse(fields Fields) (*SnapshotResponse, error) {
	path, err := decodeString(fields, "path")
	if err != nil {
		return nil, err
	}
	return &SnapshotResponse{Path: path}, nil
}

// InspectResponse is inspect's decoded result fields. Version and
// DataLength are nil when the running process has no embedded snapshot
// data (verified in Phase 2: inspect reflects the running binary's own
// embedded data, not live state mutated via exec/load).
type InspectResponse struct {
	HasData    bool
	Version    *int64
	DataLength *int64
	Source     string
	ReadOnly   bool
}

func DecodeInspectResponse(fields Fields) (*InspectResponse, error) {
	hasData, err := decodeBool(fields, "has_data")
	if err != nil {
		return nil, err
	}
	version, err := decodeNullableInt64(fields, "version")
	if err != nil {
		return nil, err
	}
	dataLength, err := decodeNullableInt64(fields, "data_length")
	if err != nil {
		return nil, err
	}
	source, err := decodeString(fields, "source")
	if err != nil {
		return nil, err
	}
	readOnly, err := decodeBool(fields, "read_only")
	if err != nil {
		return nil, err
	}
	return &InspectResponse{
		HasData:    hasData,
		Version:    version,
		DataLength: dataLength,
		Source:     source,
		ReadOnly:   readOnly,
	}, nil
}

// TablesResponse is tables's decoded result fields.
type TablesResponse struct {
	Tables []string
}

func DecodeTablesResponse(fields Fields) (*TablesResponse, error) {
	tables, err := decodeStringSlice(fields, "tables")
	if err != nil {
		return nil, err
	}
	return &TablesResponse{Tables: tables}, nil
}

// SchemaResponse is schema's decoded result fields.
type SchemaResponse struct {
	Schema []string
}

func DecodeSchemaResponse(fields Fields) (*SchemaResponse, error) {
	schema, err := decodeStringSlice(fields, "schema")
	if err != nil {
		return nil, err
	}
	return &SchemaResponse{Schema: schema}, nil
}

// DumpResponse is dump's decoded result fields.
type DumpResponse struct {
	SQL string
}

func DecodeDumpResponse(fields Fields) (*DumpResponse, error) {
	sqlText, err := decodeString(fields, "sql")
	if err != nil {
		return nil, err
	}
	return &DumpResponse{SQL: sqlText}, nil
}
