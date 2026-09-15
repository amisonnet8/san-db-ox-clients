# san_db_ox

Python client for [SanDBox](https://github.com/amisonnet8/san-db-ox): connect
to a running `san-db-ox --serve-stdio` process, either as a child process
(direct-connect) or over a TCP/UNIX socket exposed by something like
`socat`.

```
pip install san-db-ox-client
```

(the distribution name is `san-db-ox-client`; the importable module is
`san_db_ox`, since Python identifiers can't contain hyphens)

| | |
| :--- | :--- |
| `protocol` number | `1` |

Driver version numbers are not tied to the upstream SanDBox version; the
`protocol` number above is what expresses compatibility. Runtime
dependencies: none (standard library only).

## Quickstart

```python
import san_db_ox

with san_db_ox.connect("san-db-ox", ["--serve-stdio"]) as c:
    c.exec("CREATE TABLE t(x INTEGER)")
    result = c.query("SELECT x FROM t")
```

`connect` takes the command to launch, not a fixed "local" assumption, so
the same call works over SSH, through Docker, or via `kubectl exec` just by
changing the command and args -- see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) for worked
examples of each.

### Connecting over a socket instead

```python
c = san_db_ox.connect_unix("/tmp/sandbox.sock")
# or, for TLS:
sock = ssl_context.wrap_socket(socket.create_connection(("host", 5432)))
c = san_db_ox.connect_socket(sock)
```

`Client` (direct-connect) and `SocketClient` (socket) both implement the
`Connection` protocol (`query`/`exec`/`snapshot`/`load`/`inspect`/`tables`/
`schema`/`dump`/`close`). `overwrite` and `exit_code` are only on `Client`
-- they don't mean anything over a socket connection, so they're simply not
there to call.

## Connecting anywhere

For direct-connect over SSH/Docker/Kubernetes, socat with TLS client
authentication, and source-IP restriction, see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) (a Japanese
version is also available at
[`docs/usage/connecting_ja.md`](../docs/usage/connecting_ja.md)).

## Testing

```
make fetch        # downloads the san-db-ox binary tests run against into bin/
make python-test   # unit tests plus an integration suite against that binary
```

Set `SAN_DB_OX_BIN` to point at a locally built `san-db-ox` binary instead
of downloading one.

## License

MIT. See [LICENSE](../LICENSE).
