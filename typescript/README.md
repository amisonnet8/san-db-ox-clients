# @amisonnet8/san-db-ox-client

TypeScript/Node.js client for [SanDBox](https://github.com/amisonnet8/san-db-ox):
connect to a running `san-db-ox --serve-stdio` process, either as a child
process (direct-connect) or over a TCP/UNIX socket exposed by something like
`socat`.

```
npm install @amisonnet8/san-db-ox-client
```

(published under the `@amisonnet8` scope, so there's no name collision with
an unrelated package -- unlike the PyPI distribution, which had to be
renamed for exactly that reason)

| | |
| :--- | :--- |
| `protocol` number | `1` |

Driver version numbers are not tied to the upstream SanDBox version; the
`protocol` number above is what expresses compatibility. Runtime
dependencies: none. Requires Node.js >= 22.12 (needed for `JSON.rawJSON`
and `JSON.parse`'s reviver source-text access, which this driver uses to
represent 64-bit integers exactly). ESM only.

## Quickstart

```typescript
import { connect } from "@amisonnet8/san-db-ox-client";

await using c = await connect("san-db-ox", ["--serve-stdio"]);
await c.exec("CREATE TABLE t(x INTEGER)");
const result = await c.query("SELECT x FROM t");
```

`connect` takes the command to launch, not a fixed "local" assumption, so
the same call works over SSH, through Docker, or via `kubectl exec` just by
changing the command and args -- see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) for worked
examples of each.

**SQLite INTEGER decodes to `bigint`, REAL decodes to `number`.** This
keeps the two SQLite storage classes unambiguous in both directions, but it
means a param's *type*, not just its value, decides which one you bind:

```typescript
await c.exec("INSERT INTO t VALUES (?)", [42n]); // binds INTEGER
await c.exec("INSERT INTO t VALUES (?)", [42]);  // binds REAL (42.0)
```

Because of this, `JSON.stringify(result.rows)` throws (`TypeError: Do not
know how to serialize a BigInt`) the moment a row contains an INTEGER
column -- convert explicitly, e.g. with a replacer:
`JSON.stringify(result.rows, (_k, v) => (typeof v === "bigint" ? v.toString() : v))`.

### Connecting over a socket instead

```typescript
import { connectUnix, connectSocket } from "@amisonnet8/san-db-ox-client";
import { connect as tlsConnect } from "node:tls";

const c1 = await connectUnix("/tmp/sandbox.sock");
// or, for TLS:
const socket = tlsConnect({ host: "host", port: 5432, ca, cert, key });
const c2 = await connectSocket(socket);
```

`Client` (direct-connect) and `SocketClient` (socket) both implement the
`Connection` interface (`query`/`exec`/`snapshot`/`load`/`inspect`/`tables`/
`schema`/`dump`/`close`). `overwrite` and `exitCode` are only on `Client`
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
make fetch            # downloads the san-db-ox binary tests run against into bin/
make typescript-test  # unit tests plus an integration suite against that binary
```

Set `SAN_DB_OX_BIN` to point at a locally built `san-db-ox` binary instead
of downloading one.

## License

MIT. See [LICENSE](../LICENSE).
