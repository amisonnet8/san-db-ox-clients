# san-db-ox-client

Rust client for [SanDBox](https://github.com/amisonnet8/san-db-ox): connect
to a running `san-db-ox --serve-stdio` process, either as a child process
(direct-connect) or over a TCP/UNIX socket exposed by something like
`socat`.

```
cargo add san-db-ox-client
```

| | |
| :--- | :--- |
| `PROTOCOL` | `1` |

Driver version numbers are not tied to the upstream SanDBox version; the
`PROTOCOL` constant above is what expresses compatibility. Runtime
dependencies: none, and no dev-dependencies either -- the JSON codec and
Base64 encoding are hand-written. Requires Rust 1.85 (edition 2024),
synchronous only (no async runtime).

## Quickstart

```rust
use san_db_ox_client::{connect, Connection};

let mut c = connect("san-db-ox", &["--serve-stdio"])?;
c.exec("CREATE TABLE t(x INTEGER)", &[])?;
let result = c.query("SELECT x FROM t", &[])?;
```

`connect` takes the command to launch, not a fixed "local" assumption, so
the same call works over SSH, through Docker, or via `kubectl exec` just by
changing the command and args -- see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) for worked
examples of each.

`Value` maps INTEGER to `i64`, REAL to `f64`, TEXT to `String`, and BLOB to
`Vec<u8>`, so `Value::Integer(42)` binds INTEGER and `Value::Real(42.0)`
binds REAL. `bool` is not a supported param type -- SQLite (and san-db-ox)
has no boolean type, so `Value::from(true)` is a compile error, not a
runtime one. Every connection closes automatically on drop, so there is
nothing like a `with` block or `defer` to remember.

### Connecting over a socket instead

```rust
let mut c = san_db_ox_client::connect_unix("/tmp/sandbox.sock")?;
// or, for TLS: bring your own rustls/native-tls -- this crate depends on
// neither and has no TLS-specific constructor.
let mut c = san_db_ox_client::connect_socket(tls_stream, SocketOptions::new())?;
```

`Client` (direct-connect) and `SocketClient` (socket) both implement the
`Connection` trait (`query`/`exec`/`snapshot`/`load`/`inspect`/`tables`/
`schema`/`dump`/`close`). `overwrite` and `exit_code` are only on `Client`
-- they don't mean anything over a socket connection, so they're simply
not there to call (and calling them on a `SocketClient` is a compile
error).

## Connecting anywhere

For direct-connect over SSH/Docker/Kubernetes, socat with TLS client
authentication, and source-IP restriction, see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) (a Japanese
version is also available at
[`docs/usage/connecting_ja.md`](../docs/usage/connecting_ja.md)).

## Testing

```
make fetch      # downloads the san-db-ox binary tests run against into bin/
make rust-test  # unit tests plus an integration suite against that binary
```

Set `SAN_DB_OX_BIN` to point at a locally built `san-db-ox` binary instead
of downloading one.

## License

MIT. See [LICENSE](../LICENSE).
