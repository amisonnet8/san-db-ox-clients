package codec

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func TestWriteRequestFlushesOneLine(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteRequest(&buf, &Request{Op: "query", SQL: "SELECT 1"}); err != nil {
		t.Fatalf("WriteRequest: %v", err)
	}
	s := buf.String()
	if !strings.HasSuffix(s, "\n") {
		t.Errorf("WriteRequest output does not end in a newline: %q", s)
	}
	if strings.Count(s, "\n") != 1 {
		t.Errorf("WriteRequest wrote %d lines, want 1: %q", strings.Count(s, "\n"), s)
	}
	var decoded map[string]any
	if err := json.Unmarshal([]byte(strings.TrimSuffix(s, "\n")), &decoded); err != nil {
		t.Fatalf("output is not valid JSON: %v", err)
	}
	if decoded["op"] != "query" || decoded["sql"] != "SELECT 1" {
		t.Errorf("decoded request = %#v", decoded)
	}
}

func TestWriteRequestOmitsUnsetFields(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteRequest(&buf, &Request{Op: "close"}); err != nil {
		t.Fatalf("WriteRequest: %v", err)
	}
	got := strings.TrimSuffix(buf.String(), "\n")
	want := `{"op":"close"}`
	if got != want {
		t.Errorf("WriteRequest(close) = %s, want %s", got, want)
	}
}

func TestDecodeHello(t *testing.T) {
	h, err := DecodeHello([]byte(`{"protocol":1,"version":"v0.1.1","product":"SanDBox"}`))
	if err != nil {
		t.Fatalf("DecodeHello: %v", err)
	}
	if h.Protocol != 1 || h.Version != "v0.1.1" || h.Product != "SanDBox" {
		t.Errorf("DecodeHello = %#v", h)
	}
}

func TestDecodeResponseLineSuccess(t *testing.T) {
	ok, fields, errResp, err := DecodeResponseLine([]byte(`{"ok":true,"columns":["1"],"rows":[[1]]}`))
	if err != nil {
		t.Fatalf("DecodeResponseLine: %v", err)
	}
	if !ok {
		t.Fatal("ok = false, want true")
	}
	if errResp != nil {
		t.Errorf("errResp = %#v, want nil", errResp)
	}
	if _, present := fields["columns"]; !present {
		t.Errorf("fields missing columns: %#v", fields)
	}
}

func TestDecodeResponseLineError(t *testing.T) {
	ok, _, errResp, err := DecodeResponseLine([]byte(`{"ok":false,"error":{"code":"sqlite_error","message":"no such table"}}`))
	if err != nil {
		t.Fatalf("DecodeResponseLine: %v", err)
	}
	if ok {
		t.Fatal("ok = true, want false")
	}
	if errResp == nil || errResp.Code != CodeSQLite {
		t.Errorf("errResp = %#v", errResp)
	}
	if !strings.Contains(errResp.Error(), CodeSQLite) {
		t.Errorf("Error() = %q, want it to mention the code", errResp.Error())
	}
}

func TestDecodeResponseLineMalformedJSON(t *testing.T) {
	if _, _, _, err := DecodeResponseLine([]byte(`{not json`)); err == nil {
		t.Error("DecodeResponseLine(malformed): want error, got nil")
	}
}
