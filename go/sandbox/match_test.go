package sandbox_test

import (
	"encoding/json"
	"testing"
)

// These pin down conformance/README_ja.md's partial-match rule
// independently of any real process: an object matches partially (only
// keys mentioned in expect are checked, recursively); anything else
// (array, string, number, bool, null) must match exactly.
func TestMatchJSONSemantics(t *testing.T) {
	cases := []struct {
		name   string
		expect string
		actual string
		wantOK bool
	}{
		{
			name:   "extra unmentioned top-level key is ignored",
			expect: `{"ok":true}`,
			actual: `{"ok":true,"columns":["1"],"rows":[[1]]}`,
			wantOK: true,
		},
		{
			name:   "mentioned key must match",
			expect: `{"ok":false}`,
			actual: `{"ok":true}`,
			wantOK: false,
		},
		{
			name:   "nested object partial match ignores unmentioned nested keys",
			expect: `{"ok":false,"error":{"code":"sqlite_error"}}`,
			actual: `{"ok":false,"error":{"code":"sqlite_error","message":"anything, changes freely"}}`,
			wantOK: true,
		},
		{
			name:   "nested object partial match still checks mentioned keys",
			expect: `{"error":{"code":"sqlite_error"}}`,
			actual: `{"error":{"code":"io_error","message":"..."}}`,
			wantOK: false,
		},
		{
			name:   "array must match exactly, not partially",
			expect: `{"rows":[[1]]}`,
			actual: `{"rows":[[1],[2]]}`,
			wantOK: false,
		},
		{
			name:   "array element order and content must match exactly",
			expect: `{"tables":["logs","users"]}`,
			actual: `{"tables":["users","logs"]}`,
			wantOK: false,
		},
		{
			name:   "BLOB one-element array matches exactly",
			expect: `{"rows":[[["aGk="]]]}`,
			actual: `{"rows":[[["aGk="]]]}`,
			wantOK: true,
		},
		{
			name:   "large integer compared by exact literal text, not float64",
			expect: `{"rows":[[9223372036854775807]]}`,
			actual: `{"rows":[[9223372036854775807]]}`,
			wantOK: true,
		},
		{
			name:   "REAL decimal-point formatting is part of the exact match",
			expect: `{"rows":[[88.0]]}`,
			actual: `{"rows":[[88]]}`,
			wantOK: false,
		},
		{
			name:   "missing key fails",
			expect: `{"path":"snap1"}`,
			actual: `{"ok":true}`,
			wantOK: false,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ok, msg := matchJSON(json.RawMessage(c.expect), json.RawMessage(c.actual))
			if ok != c.wantOK {
				t.Errorf("matchJSON(%s, %s) = (%v, %q), want ok=%v", c.expect, c.actual, ok, msg, c.wantOK)
			}
		})
	}
}
