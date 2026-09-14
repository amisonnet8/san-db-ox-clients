package codec

import (
	"bytes"
	"encoding/json"
	"math"
	"testing"
)

func TestEncodeValue(t *testing.T) {
	cases := []struct {
		name string
		in   any
		want string
	}{
		{"nil", nil, "null"},
		{"int", 42, "42"},
		{"int64", int64(9223372036854775807), "9223372036854775807"},
		{"negative int64", int64(-9223372036854775808), "-9223372036854775808"},
		{"whole float keeps decimal point", 88.0, "88.0"},
		{"fractional float", 1.5, "1.5"},
		{"string", "hi", `"hi"`},
		{"empty blob", []byte{}, `[""]`},
		{"blob", []byte("hi"), `["aGk="]`},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			raw, err := EncodeValue(c.in)
			if err != nil {
				t.Fatalf("EncodeValue(%#v) error: %v", c.in, err)
			}
			if string(raw) != c.want {
				t.Errorf("EncodeValue(%#v) = %s, want %s", c.in, raw, c.want)
			}
		})
	}
}

func TestEncodeValueRejectsNonFinite(t *testing.T) {
	for _, v := range []float64{math.NaN(), math.Inf(1), math.Inf(-1)} {
		if _, err := EncodeValue(v); err == nil {
			t.Errorf("EncodeValue(%v): want error, got nil (san-db-ox itself rejects these in params)", v)
		}
	}
}

func TestEncodeValueRejectsUnsupportedType(t *testing.T) {
	if _, err := EncodeValue(struct{}{}); err == nil {
		t.Error("EncodeValue(struct{}{}): want error, got nil")
	}
}

func TestDecodeValue(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want any
	}{
		{"null", "null", nil},
		{"text", `"hi"`, "hi"},
		{"integer max", "9223372036854775807", int64(9223372036854775807)},
		{"integer min", "-9223372036854775808", int64(-9223372036854775808)},
		{"real with decimal", "88.0", 88.0},
		{"real fraction", "1.5", 1.5},
		{"positive infinity sentinel", "9e999", math.Inf(1)},
		{"negative infinity sentinel", "-9e999", math.Inf(-1)},
		{"empty blob", `[""]`, []byte{}},
		{"blob", `["aGk="]`, []byte("hi")},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := DecodeValue(json.RawMessage(c.in))
			if err != nil {
				t.Fatalf("DecodeValue(%s) error: %v", c.in, err)
			}
			switch want := c.want.(type) {
			case []byte:
				gotBytes, ok := got.([]byte)
				if !ok || !bytes.Equal(gotBytes, want) {
					t.Errorf("DecodeValue(%s) = %#v, want %#v", c.in, got, want)
				}
			case float64:
				gotFloat, ok := got.(float64)
				if !ok || gotFloat != want && !(math.IsInf(gotFloat, 0) && math.IsInf(want, 0) && math.Signbit(gotFloat) == math.Signbit(want)) {
					t.Errorf("DecodeValue(%s) = %#v, want %#v", c.in, got, want)
				}
			default:
				if got != c.want {
					t.Errorf("DecodeValue(%s) = %#v, want %#v", c.in, got, c.want)
				}
			}
		})
	}
}

func TestDecodeValueRejectsWrongLengthBLOBArray(t *testing.T) {
	for _, in := range []string{`[]`, `["aGk=", "aGk="]`} {
		if _, err := DecodeValue(json.RawMessage(in)); err == nil {
			t.Errorf("DecodeValue(%s): want error (protocol.md: wrong-length BLOB array is an implementation bug), got nil", in)
		}
	}
}

func TestValueRoundTrip(t *testing.T) {
	// The values a driver must be able to read back and write out unchanged
	// (.claude/rules/protocol.md's round-trip symmetry; verified upstream
	// for BLOB and, since v0.1.1, for large integers -- see PLAN.md).
	values := []any{
		nil,
		int64(9223372036854775807),
		int64(-9223372036854775808),
		88.0,
		1.5,
		"hello",
		[]byte{},
		[]byte("hi"),
	}
	for _, v := range values {
		encoded, err := EncodeValue(v)
		if err != nil {
			t.Fatalf("EncodeValue(%#v): %v", v, err)
		}
		decoded, err := DecodeValue(encoded)
		if err != nil {
			t.Fatalf("DecodeValue(%s): %v", encoded, err)
		}
		if b, ok := v.([]byte); ok {
			db, ok := decoded.([]byte)
			if !ok || !bytes.Equal(b, db) {
				t.Errorf("round-trip %#v -> %s -> %#v", v, encoded, decoded)
			}
			continue
		}
		if decoded != v {
			t.Errorf("round-trip %#v -> %s -> %#v", v, encoded, decoded)
		}
	}
}

func TestDecodeRows(t *testing.T) {
	rows, err := DecodeRows(json.RawMessage(`[[1, "a", ["aGk="]], [null, 2.5]]`))
	if err != nil {
		t.Fatalf("DecodeRows: %v", err)
	}
	if len(rows) != 2 || len(rows[0]) != 3 || len(rows[1]) != 2 {
		t.Fatalf("DecodeRows shape = %#v", rows)
	}
	if rows[0][0] != int64(1) || rows[0][1] != "a" {
		t.Errorf("DecodeRows[0] = %#v", rows[0])
	}
	if !bytes.Equal(rows[0][2].([]byte), []byte("hi")) {
		t.Errorf("DecodeRows[0][2] = %#v", rows[0][2])
	}
	if rows[1][0] != nil || rows[1][1] != 2.5 {
		t.Errorf("DecodeRows[1] = %#v", rows[1])
	}
}
