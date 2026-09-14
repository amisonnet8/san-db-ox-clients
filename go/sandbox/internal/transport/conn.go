package transport

import (
	"io"
	"time"
)

// Conn is a byte-level connection to a SanDBox process, in whichever shape
// the transport provides one. codec talks only to this (and to plain
// io.Reader/io.Writer directly, for callers that don't need Close) --
// exactly the seam .claude/rules/architecture.md requires between the two
// layers.
//
// Conn deliberately has no exit code, no stderr, and no equivalent of
// Direct's staged shutdown: those only mean something when there is a
// child process behind the connection, which Socket does not have
// (.claude/rules/architecture.md: "直結でしか成立しないAPIは型で区別する").
type Conn interface {
	io.Reader
	io.Writer

	// Close ends the connection. timeout bounds the transport's shutdown;
	// what exactly it bounds is transport-specific -- see each
	// implementation's own Close.
	Close(timeout time.Duration) error
}
