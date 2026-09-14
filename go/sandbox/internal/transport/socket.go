package transport

import (
	"context"
	"fmt"
	"net"
	"time"
)

// Socket is a connection to a SanDBox process exposed over a TCP or UNIX
// domain socket, typically by socat or similar
// (.claude/rules/architecture.md, .claude/rules/connectivity.md). Unlike
// Direct, there is no child process on this end of the connection: no exit
// code to reap, no stderr to drain, and no staged SIGTERM/SIGKILL
// escalation on Close -- those all belong to whatever process is listening
// on the other end, which this package never sees.
type Socket struct {
	nc net.Conn
}

// DialSocket dials network/address (the same vocabulary as net.Dial, e.g.
// "tcp" or "unix") and wraps the result. ctx bounds only the dial itself.
func DialSocket(ctx context.Context, network, address string) (*Socket, error) {
	var d net.Dialer
	nc, err := d.DialContext(ctx, network, address)
	if err != nil {
		return nil, fmt.Errorf("sandbox/transport: dialing %s %s: %w", network, address, err)
	}
	return NewSocket(nc), nil
}

// NewSocket wraps an already-established net.Conn. This is the seam for
// connections DialSocket can't build directly -- a TLS-wrapped dial
// (crypto/tls.Dial, for the mutual-TLS setup .claude/rules/connectivity.md
// describes) or any other custom net.Conn -- without this package needing
// a TLS-specific constructor (.claude/rules/architecture.md: "SSH 専用・
// Docker 専用の API やコンストラクタは作らない" applies here just as much).
func NewSocket(nc net.Conn) *Socket {
	return &Socket{nc: nc}
}

func (s *Socket) Read(p []byte) (int, error)  { return s.nc.Read(p) }
func (s *Socket) Write(p []byte) (int, error) { return s.nc.Write(p) }

var _ Conn = (*Socket)(nil)

// Close ends the connection. timeout bounds how long a pending read/write
// is given to unblock before the connection is torn down outright -- there
// is deliberately no escalation sequence here (contrast Direct.Close):
// with no child process on this end, there is nothing to signal or reap,
// only the socket itself to close.
func (s *Socket) Close(timeout time.Duration) error {
	_ = s.nc.SetDeadline(time.Now().Add(timeout))
	return s.nc.Close()
}
