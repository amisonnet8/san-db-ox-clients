package codec

import (
	"bytes"
	"testing"
)

func decodeFields(t *testing.T, line string) Fields {
	t.Helper()
	_, fields, _, err := DecodeResponseLine([]byte(line))
	if err != nil {
		t.Fatalf("DecodeResponseLine(%s): %v", line, err)
	}
	return fields
}

func TestDecodeQueryResponse(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"columns":["b"],"rows":[[["aGk="]]]}`)
	resp, err := DecodeQueryResponse(fields)
	if err != nil {
		t.Fatalf("DecodeQueryResponse: %v", err)
	}
	if len(resp.Columns) != 1 || resp.Columns[0] != "b" {
		t.Errorf("Columns = %#v", resp.Columns)
	}
	if len(resp.Rows) != 1 || !bytes.Equal(resp.Rows[0][0].([]byte), []byte("hi")) {
		t.Errorf("Rows = %#v", resp.Rows)
	}
}

func TestDecodeExecResponse(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"rows_affected":1,"last_insert_id":42}`)
	resp, err := DecodeExecResponse(fields)
	if err != nil {
		t.Fatalf("DecodeExecResponse: %v", err)
	}
	if resp.RowsAffected != 1 || resp.LastInsertID != 42 {
		t.Errorf("resp = %#v", resp)
	}
}

func TestDecodeExecResponseLargeLastInsertID(t *testing.T) {
	// Regression guard: must decode through int64/json.Number, never
	// float64, or a large id silently corrupts.
	fields := decodeFields(t, `{"ok":true,"rows_affected":1,"last_insert_id":9223372036854775807}`)
	resp, err := DecodeExecResponse(fields)
	if err != nil {
		t.Fatalf("DecodeExecResponse: %v", err)
	}
	if resp.LastInsertID != 9223372036854775807 {
		t.Errorf("LastInsertID = %d, want 9223372036854775807", resp.LastInsertID)
	}
}

func TestDecodeSnapshotResponse(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"path":"snap1"}`)
	resp, err := DecodeSnapshotResponse(fields)
	if err != nil {
		t.Fatalf("DecodeSnapshotResponse: %v", err)
	}
	if resp.Path != "snap1" {
		t.Errorf("Path = %q", resp.Path)
	}
}

func TestDecodeInspectResponseEmpty(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"has_data":false,"read_only":false,"source":"san-db-ox","version":null,"data_length":null}`)
	resp, err := DecodeInspectResponse(fields)
	if err != nil {
		t.Fatalf("DecodeInspectResponse: %v", err)
	}
	if resp.HasData || resp.ReadOnly || resp.Version != nil || resp.DataLength != nil || resp.Source != "san-db-ox" {
		t.Errorf("resp = %#v", resp)
	}
}

func TestDecodeInspectResponseWithData(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"has_data":true,"read_only":false,"source":"snap1","version":1,"data_length":8192}`)
	resp, err := DecodeInspectResponse(fields)
	if err != nil {
		t.Fatalf("DecodeInspectResponse: %v", err)
	}
	if !resp.HasData || resp.Source != "snap1" {
		t.Errorf("resp = %#v", resp)
	}
	if resp.Version == nil || *resp.Version != 1 {
		t.Errorf("Version = %v", resp.Version)
	}
	if resp.DataLength == nil || *resp.DataLength != 8192 {
		t.Errorf("DataLength = %v", resp.DataLength)
	}
}

func TestDecodeTablesResponse(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"tables":["logs","users"]}`)
	resp, err := DecodeTablesResponse(fields)
	if err != nil {
		t.Fatalf("DecodeTablesResponse: %v", err)
	}
	if len(resp.Tables) != 2 || resp.Tables[0] != "logs" || resp.Tables[1] != "users" {
		t.Errorf("Tables = %#v", resp.Tables)
	}
}

func TestDecodeSchemaResponse(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"schema":["CREATE TABLE t(x)"]}`)
	resp, err := DecodeSchemaResponse(fields)
	if err != nil {
		t.Fatalf("DecodeSchemaResponse: %v", err)
	}
	if len(resp.Schema) != 1 || resp.Schema[0] != "CREATE TABLE t(x)" {
		t.Errorf("Schema = %#v", resp.Schema)
	}
}

func TestDecodeDumpResponse(t *testing.T) {
	fields := decodeFields(t, `{"ok":true,"sql":"CREATE TABLE t(x);"}`)
	resp, err := DecodeDumpResponse(fields)
	if err != nil {
		t.Fatalf("DecodeDumpResponse: %v", err)
	}
	if resp.SQL != "CREATE TABLE t(x);" {
		t.Errorf("SQL = %q", resp.SQL)
	}
}

func TestDecodeResponseMissingField(t *testing.T) {
	fields := decodeFields(t, `{"ok":true}`)
	if _, err := DecodeQueryResponse(fields); err == nil {
		t.Error("DecodeQueryResponse with missing fields: want error, got nil")
	}
}
