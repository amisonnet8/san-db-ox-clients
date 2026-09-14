package sandbox

import "github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/codec"

// Error is a stdio protocol error response. Compare Code against the
// constants below; Message is upstream's own text and can change wording
// between releases without notice, so never branch on it.
type Error = codec.Error

// The five stable error codes the protocol defines.
const (
	CodeSQLite        = codec.CodeSQLite
	CodeBadRequest    = codec.CodeBadRequest
	CodeIOError       = codec.CodeIOError
	CodeUnsupportedOp = codec.CodeUnsupportedOp
	CodeReadOnly      = codec.CodeReadOnly
)
