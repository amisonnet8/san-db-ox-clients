package codec

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
)

// Cell values decode to one of these Go types, matching SQLite's storage
// classes (.claude/rules/protocol.md):
//
//	NULL    -> nil
//	INTEGER -> int64
//	REAL    -> float64
//	TEXT    -> string
//	BLOB    -> []byte
//
// A REAL that is NaN is indistinguishable from SQL NULL once decoded (both
// arrive as the JSON literal null) -- that is upstream's own wire
// representation (.claude/rules/protocol.md: "NaN は null"), not a gap in
// this codec.

// EncodeParams encodes a slice of Go values into the wire params
// representation.
func EncodeParams(params []any) ([]json.RawMessage, error) {
	if params == nil {
		return nil, nil
	}
	out := make([]json.RawMessage, len(params))
	for i, p := range params {
		raw, err := EncodeValue(p)
		if err != nil {
			return nil, fmt.Errorf("params[%d]: %w", i, err)
		}
		out[i] = raw
	}
	return out, nil
}

// EncodeValue encodes one Go value as a wire cell. Accepted types: nil,
// int, int64, float64, string, []byte. A NaN or infinite float64 is
// rejected -- san-db-ox v0.1.1 rejects it too (verified: params:[9e999]
// comes back bad_request, "value out of range"), and encoding/json's own
// Marshal already refuses to encode Inf/NaN, so this isn't a gap opened by
// this driver, just made explicit with a clearer message.
func EncodeValue(v any) (json.RawMessage, error) {
	switch x := v.(type) {
	case nil:
		return json.RawMessage("null"), nil
	case int:
		return json.RawMessage(strconv.FormatInt(int64(x), 10)), nil
	case int64:
		return json.RawMessage(strconv.FormatInt(x, 10)), nil
	case float64:
		if math.IsNaN(x) || math.IsInf(x, 0) {
			return nil, fmt.Errorf("REAL param must be finite (san-db-ox rejects NaN/Inf in params): %v", x)
		}
		return json.RawMessage(formatReal(x)), nil
	case string:
		b, err := json.Marshal(x)
		if err != nil {
			return nil, err
		}
		return b, nil
	case []byte:
		b, err := json.Marshal([1]string{base64.StdEncoding.EncodeToString(x)})
		if err != nil {
			return nil, err
		}
		return b, nil
	default:
		return nil, fmt.Errorf("unsupported param type %T (want nil, int, int64, float64, string, or []byte)", v)
	}
}

// formatReal renders f the way san-db-ox's own responses do: always with a
// decimal point or exponent, never a bare integer-looking token. This
// matters for round-tripping -- san-db-ox binds a params number's SQLite
// type (INTEGER vs REAL) by whether the JSON token itself has a decimal
// point (verified: params:[88, 88.0] binds typeof "integer","real"
// respectively), so a whole-number REAL like 88.0 must not be sent as a
// bare "88".
func formatReal(f float64) string {
	s := strconv.FormatFloat(f, 'g', -1, 64)
	if !strings.ContainsAny(s, ".eE") {
		s += ".0"
	}
	return s
}

// DecodeValue decodes one wire cell into a Go value.
func DecodeValue(raw json.RawMessage) (any, error) {
	s := strings.TrimSpace(string(raw))
	if s == "" {
		return nil, errors.New("decoding value: empty token")
	}
	switch s[0] {
	case 'n':
		return nil, nil
	case '"':
		var str string
		if err := json.Unmarshal(raw, &str); err != nil {
			return nil, fmt.Errorf("decoding TEXT: %w", err)
		}
		return str, nil
	case '[':
		return decodeBLOB(raw)
	default:
		return decodeNumber(s)
	}
}

// decodeBLOB decodes the one-element base64-string-array BLOB
// representation (.claude/rules/protocol.md: "BLOB は1要素配列"). An array
// of any other length is upstream sending something this driver doesn't
// understand, not something to coerce silently
// (.claude/rules/protocol.md: "要素数が1でない配列を受け取ったら実装の
// バグとして扱う").
func decodeBLOB(raw json.RawMessage) (any, error) {
	var arr []string
	if err := json.Unmarshal(raw, &arr); err != nil {
		return nil, fmt.Errorf("decoding BLOB array: %w", err)
	}
	if len(arr) != 1 {
		return nil, fmt.Errorf("protocol violation: BLOB array must have exactly 1 element, got %d", len(arr))
	}
	b, err := base64.StdEncoding.DecodeString(arr[0])
	if err != nil {
		return nil, fmt.Errorf("decoding BLOB base64: %w", err)
	}
	return b, nil
}

// decodeNumber decodes an INTEGER or REAL token. The rule (matching how
// san-db-ox itself binds params, verified in Phase 3) is: a decimal point
// or exponent means REAL, otherwise INTEGER -- decoded as int64, never
// through float64, so the full 64-bit SQLite INTEGER range round-trips
// exactly (.claude/rules/protocol.md: "64bit整数を倍精度浮動小数点へ
// デコードしない").
func decodeNumber(s string) (any, error) {
	if strings.ContainsAny(s, ".eE") {
		f, err := strconv.ParseFloat(s, 64)
		if err != nil {
			var numErr *strconv.NumError
			if errors.As(err, &numErr) && errors.Is(numErr.Err, strconv.ErrRange) && math.IsInf(f, 0) {
				// The ±9e999 sentinel literal for ±Inf
				// (.claude/rules/protocol.md) overflows float64 by
				// design; strconv still hands back the correctly
				// rounded ±Inf alongside the range error.
				return f, nil
			}
			return nil, fmt.Errorf("decoding REAL %q: %w", s, err)
		}
		return f, nil
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return nil, fmt.Errorf("decoding INTEGER %q: %w", s, err)
	}
	return n, nil
}

// DecodeRows decodes a wire `rows` array (an array of arrays of cells) into
// [][]any.
func DecodeRows(raw json.RawMessage) ([][]any, error) {
	var wireRows []json.RawMessage
	if err := json.Unmarshal(raw, &wireRows); err != nil {
		return nil, fmt.Errorf("decoding rows: %w", err)
	}
	rows := make([][]any, len(wireRows))
	for i, wireRow := range wireRows {
		var cells []json.RawMessage
		if err := json.Unmarshal(wireRow, &cells); err != nil {
			return nil, fmt.Errorf("decoding rows[%d]: %w", i, err)
		}
		row := make([]any, len(cells))
		for j, cell := range cells {
			v, err := DecodeValue(cell)
			if err != nil {
				return nil, fmt.Errorf("decoding rows[%d][%d]: %w", i, j, err)
			}
			row[j] = v
		}
		rows[i] = row
	}
	return rows, nil
}
