# sandbox

Go client for [SanDBox](https://github.com/amisonnet8/san-db-ox): connect
to a running `san-db-ox --serve-stdio` process, either as a child process
(direct-connect) or over a TCP/UNIX socket exposed by something like
`socat`.

```
go get github.com/amisonnet8/san-db-ox-clients/go@latest
```

| | |
| :--- | :--- |
| `protocol` number | `1` |

Driver version numbers are not tied to the upstream SanDBox version; the
`protocol` number above is what expresses compatibility. External
dependencies: none (standard library only).

## Quickstart

```go
import "github.com/amisonnet8/san-db-ox-clients/go/sandbox"

c, err := sandbox.Open(ctx, "san-db-ox", []string{"--serve-stdio"})
if err != nil {
    log.Fatal(err)
}
defer c.Close(ctx)

if _, err := c.Exec(ctx, "CREATE TABLE t(x INTEGER)"); err != nil {
    log.Fatal(err)
}
res, err := c.Query(ctx, "SELECT x FROM t")
```

`Open` takes the command to launch, not a fixed "local" assumption, so the
same call works over SSH, through Docker, or via `kubectl exec` just by
changing the command and args -- see
[`docs/usage/connecting_ja.md`](../docs/usage/connecting_ja.md) for worked
examples of each.

### Connecting over a socket instead

```go
c, err := sandbox.OpenSocket(ctx, "unix", "/tmp/sandbox.sock")
// or, for TLS:
nc, err := tls.Dial("tcp", "host:5432", tlsConfig)
c, err := sandbox.OpenSocketConn(ctx, nc)
```

`*Client` (direct-connect) and `*SocketClient` (socket) both implement
`sandbox.Conn` (`Query`/`Exec`/`Snapshot`/`Load`/`Inspect`/`Tables`/
`Schema`/`Dump`/`Close`). `Overwrite` and `ExitCode` are only on `*Client`
-- they don't mean anything over a socket connection, so they're simply
not there to call.

## Connecting anywhere

For direct-connect over SSH/Docker/Kubernetes, socat with TLS client
authentication, and source-IP restriction, see
[`docs/usage/connecting_ja.md`](../docs/usage/connecting_ja.md) (Japanese;
an English version will follow once more languages land).

## Testing

```
make fetch    # downloads the san-db-ox binary tests run against into bin/
make go-test  # unit tests plus an integration suite against that binary
```

Set `SAN_DB_OX_BIN` to point at a locally built `san-db-ox` binary instead
of downloading one.

## License

MIT. See [LICENSE](../LICENSE).
