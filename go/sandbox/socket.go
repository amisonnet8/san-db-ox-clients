package sandbox

import (
	"context"
	"net"

	"github.com/amisonnet8/san-db-ox-clients/go/sandbox/internal/transport"
)

// SocketClient is a connection to a SanDBox process reached over a TCP or
// UNIX domain socket exposed by socat or similar. Unlike Client, it has no
// Overwrite or ExitCode method: overwrite risks multiple processes writing
// the same executable path at once when several clients connect through
// the same listener, and there is no child process on this end to report
// an exit code for.
//
// The assumptions that come with this transport still apply: each
// connection is served by its own process with its own database (a
// consequence of how socat's fork option works, not something SanDBox
// itself arranges), and there is no authentication at this layer -- any
// exposure beyond a trusted network should combine --read-only with TLS
// client authentication and should restrict source addresses. See
// docs/usage/connecting_ja.md for worked examples.
type SocketClient struct {
	*session
}

var _ Conn = (*SocketClient)(nil)

// OpenSocket dials network/address (the same vocabulary as net.Dial, e.g.
// "tcp" or "unix"), reads its hello line, and returns a ready-to-use
// SocketClient. ctx bounds only the connection attempt; it is not retained
// for later calls -- each method takes its own ctx.
func OpenSocket(ctx context.Context, network, address string) (*SocketClient, error) {
	t, err := transport.DialSocket(ctx, network, address)
	if err != nil {
		return nil, err
	}
	s, err := newSession(ctx, t)
	if err != nil {
		return nil, err
	}
	return &SocketClient{session: s}, nil
}

// OpenSocketConn wraps an already-established net.Conn, reads its hello
// line, and returns a ready-to-use SocketClient. This is the seam for
// connections OpenSocket can't build directly -- most notably a
// TLS-wrapped dial (crypto/tls.Dial) for mutual-TLS authentication --
// without this package needing a TLS-specific constructor.
func OpenSocketConn(ctx context.Context, nc net.Conn) (*SocketClient, error) {
	s, err := newSession(ctx, transport.NewSocket(nc))
	if err != nil {
		return nil, err
	}
	return &SocketClient{session: s}, nil
}
