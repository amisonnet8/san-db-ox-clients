package sandbox

import "github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/codec"

// Error is a stdio protocol error response. Compare Code against the
// constants below; Message is upstream's own text and can change wording
// between releases without notice (.claude/rules/protocol.md: "message の
// 文言に依存した分岐を書かない").
type Error = codec.Error

// The five stable error codes (.claude/rules/protocol.md).
const (
	CodeSQLite        = codec.CodeSQLite
	CodeBadRequest    = codec.CodeBadRequest
	CodeIOError       = codec.CodeIOError
	CodeUnsupportedOp = codec.CodeUnsupportedOp
	CodeReadOnly      = codec.CodeReadOnly
)
