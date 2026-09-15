# san-db-ox-client

Java client for [SanDBox](https://github.com/amisonnet8/san-db-ox): connect
to a running `san-db-ox --serve-stdio` process, either as a child process
(direct-connect) or over a TCP/UNIX socket exposed by something like
`socat`.

```xml
<dependency>
  <groupId>io.github.amisonnet8</groupId>
  <artifactId>san-db-ox-client</artifactId>
  <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.github.amisonnet8:san-db-ox-client:0.1.0")
```

| | |
| :--- | :--- |
| `SanDbOx.PROTOCOL` | `1` |

Driver version numbers are not tied to the upstream SanDBox version; the
`PROTOCOL` constant above is what expresses compatibility. Runtime
dependencies: none -- the JSON codec is hand-written. JUnit 5 is used for
tests only. Requires Java 17.

## Quickstart

```java
import io.github.amisonnet8.sandbox.*;
import java.util.List;

try (SanDbOxClient c = SanDbOx.connect("san-db-ox", List.of("--serve-stdio"))) {
    c.exec("CREATE TABLE t(x INTEGER)", List.of());
    QueryResult result = c.query("SELECT x FROM t", List.of());
}
```

`SanDbOx.connect` takes the command to launch, not a fixed "local"
assumption, so the same call works over SSH, through Docker, or via
`kubectl exec` just by changing the command and args -- see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) for worked
examples of each.

Cell and param values are plain `Object`: `null`/`Long`/`Double`/`String`/
`byte[]`, matching SQLite's NULL/INTEGER/REAL/TEXT/BLOB storage classes, so
`42L` binds INTEGER and `42.0d` binds REAL. `Boolean` is not a supported
param type -- SQLite (and san-db-ox) has no boolean type, so passing one is
a runtime error, not a compile-time one (unlike `Value::from(true)` in this
project's Rust driver, a plain `Object` parameter can't be checked at
compile time). `Connection` extends `AutoCloseable`, so try-with-resources
closes the connection automatically; there is nothing like a `Cleaner` or
`finalize` backing it up, so an unclosed connection that also never falls
out of scope will leak its child process.

### Connecting over a socket instead

```java
import java.nio.file.Path;

try (SocketClient c = SanDbOx.connectUnix(Path.of("/tmp/sandbox.sock"))) {
    // ...
}

// or, for TLS: bring your own SSLSocket -- this crate depends on no TLS
// library and has no TLS-specific constructor.
try (SocketClient c = SanDbOx.connectSocket(tlsSocket, new SocketOptions())) {
    // ...
}
```

`SanDbOxClient` (direct-connect) and `SocketClient` (socket) both implement
the `Connection` interface (`query`/`exec`/`snapshot`/`load`/`inspect`/
`tables`/`schema`/`dump`/`close`). `overwrite()` and `exitCode()` are only
on `SanDbOxClient` -- they don't mean anything over a socket connection, so
they're simply not there to call, and calling them on a `SocketClient` is a
compile error.

`exitCode()` reports a signal death as `128 + signum` (SIGTERM as `143`,
SIGKILL as `137`), the JVM's own convention for `Process.exitValue()` on
POSIX, kept as-is rather than translated to the `-signum` convention this
project's other drivers use -- so it never disagrees with a `Process` the
caller might also be observing directly.

## Connecting anywhere

For direct-connect over SSH/Docker/Kubernetes, socat with TLS client
authentication, and source-IP restriction, see
[`docs/usage/connecting.md`](../docs/usage/connecting.md) (a Japanese
version is also available at
[`docs/usage/connecting_ja.md`](../docs/usage/connecting_ja.md)).

## Testing

```
make fetch      # downloads the san-db-ox binary tests run against into bin/
make java-test  # unit tests plus an integration suite against that binary
```

Set `SAN_DB_OX_BIN` to point at a locally built `san-db-ox` binary instead
of downloading one.

## License

MIT. See [LICENSE](../LICENSE).
