# Connecting (worked examples)

*[日本語](connecting_ja.md) | **English***

How to connect to SanDBox (`san-db-ox --serve-stdio`), with copy-pasteable
command examples. Two shapes: direct-connect (launching it as a child
process) and a socket exposed through something like socat. SanDBox itself
never listens on the network, so security for any external exposure has to
be built into the socat/SSH configuration -- this doc covers "how to make
it safe" alongside each pattern.

**Only the command examples immediately preceded by `<!-- doctest -->` are
actually run and checked by `make test-docs`.** The rest (SSH, socat, TLS,
Docker examples) aren't cheap to reproduce reliably in CI -- they need
keys, certificates, or a listening process set up -- so they're excluded
from automated verification, but every one of them was run by hand while
writing this document (see each section for what was checked).

## Direct-connect

### Local

<!-- doctest -->
```bash
make fetch  # fetches bin/san-db-ox (first time only)
printf '%s\n' '{"op":"query","sql":"SELECT 1"}' \
  | bin/san-db-ox --serve-stdio \
  | jq -c .
```

Output (line 1 is the hello line, line 2 is `query`'s response):

```
{"product":"SanDBox","protocol":1,"version":"v0.1.1"}
{"ok":true,"columns":["1"],"rows":[[1]]}
```

From the Go driver, this child-process launch is just delegated to
`sandbox.Open`:

```go
c, err := sandbox.Open(ctx, "bin/san-db-ox", []string{"--serve-stdio"})
```

The Python driver's `connect` does the same:

```python
c = san_db_ox.connect("bin/san-db-ox", ["--serve-stdio"])
```

The TypeScript driver's `connect` does the same:

```typescript
const c = await connect("bin/san-db-ox", ["--serve-stdio"]);
```

The Rust driver's `connect` does the same:

```rust
let mut c = san_db_ox_client::connect("bin/san-db-ox", &["--serve-stdio"])?;
```

The Java driver's `SanDbOx.connect` does the same:

```java
SanDbOxClient c = SanDbOx.connect("bin/san-db-ox", List.of("--serve-stdio"));
```

### SSH remote command

Without touching the server side at all, just launching
`san-db-ox --serve-stdio` over `ssh`:

```bash
ssh user@host san-db-ox --serve-stdio
```

No socat, no TLS certificates, no open port needed -- authentication and
encryption ride on SSH as-is. This is the most direct way to lean on
SanDBox's own design of never listening on the network, so it's the first
thing to reach for when external exposure is needed.

The Go driver reuses the same transport just by swapping the command and
its arguments (there's no SSH-specific constructor):

```go
c, err := sandbox.Open(ctx, "ssh", []string{"user@host", "san-db-ox", "--serve-stdio"})
```

Same for the Python driver -- no SSH-specific constructor there either:

```python
c = san_db_ox.connect("ssh", ["user@host", "san-db-ox", "--serve-stdio"])
```

Same for the TypeScript driver:

```typescript
const c = await connect("ssh", ["user@host", "san-db-ox", "--serve-stdio"]);
```

Same for the Rust driver:

```rust
let mut c = san_db_ox_client::connect("ssh", &["user@host", "san-db-ox", "--serve-stdio"])?;
```

Same for the Java driver:

```java
SanDbOxClient c = SanDbOx.connect("ssh", List.of("user@host", "san-db-ox", "--serve-stdio"));
```

### SSH forced command (the recommended route)

One line in `authorized_keys` gets you authentication, authorization,
source restriction, and a forced read-only mode, all at once.

```
restrict,command="/usr/local/bin/san-db-ox --read-only --serve-stdio" ssh-ed25519 AAAA... sandbox-readonly
```

- **`command=`** -- whatever the client asks to run, only this command
  ever executes when connecting with this key. Whatever the client tried
  to send just lands in the `SSH_ORIGINAL_COMMAND` environment variable,
  unexecuted -- so the client side doesn't need to specify a command at
  all.
- **`restrict`** (OpenSSH 7.2+) -- disables port forwarding, agent
  forwarding, PTY allocation, X11 forwarding, and more. This key can do
  nothing but connect to SanDBox.
- Add **`from=`** at the front to also restrict the source by CIDR or
  hostname pattern.

```bash
ssh -i sandbox-readonly-key user@host
```

**What was actually verified against a local sshd** (`openssh-server` is
installed in the devcontainer; it's brought up by hand with
`sudo /usr/sbin/sshd -p <port>` for each test rather than left running):

- Confirmed that no matter what command the client sends (e.g.
  `ssh user@host 'rm -rf /'`), only the forced command
  (`san-db-ox --serve-stdio`) ever runs.
- Confirmed that `restrict` makes the server refuse a port-forward
  request over the same key with `administratively prohibited`, visible
  in `ssh -v`'s log -- the client's own local listener always opens
  regardless, so whether forwarding actually works has to be judged by
  whether data gets through, not by whether a local listener exists.
- Set the forced command to the real `san-db-ox --read-only --serve-stdio`
  and confirmed the hello line and `query` response both come back
  correctly.
- Confirmed the Go driver's `sandbox.Open(ctx, "ssh", []string{...})`
  works over this forced-command path exactly like any other launch
  command (there's no SSH-specific code path).
- Confirmed the same for the Python driver's `san_db_ox.connect("ssh",
  [...])`.
- Confirmed the same for the TypeScript driver's `connect("ssh", [...])`,
  including that a client sending an arbitrary command
  (`ssh ... 'rm -rf /'`) still only ever gets the forced command, and that
  the forced `--read-only` is actually enforced (`snapshot()` rejects with
  the `read_only` code).
- Confirmed the same for the Rust driver's `connect("ssh", &[...])`: the
  hello line and a `query` round-trip both come back correctly, and the
  forced `--read-only` is enforced (`snapshot()` rejects with the
  `read_only` code).
- Confirmed the same for the Java driver's `SanDbOx.connect("ssh", ...)`:
  the hello line and a `query` round-trip both come back correctly, a
  client sending an arbitrary command still only ever gets the forced
  command, and the forced `--read-only` is enforced (`snapshot()` throws a
  `ResponseException` with the `read_only` code).

**Issue a separate `authorized_keys` entry (a separate key pair) for a
read-write key versus a read-only key.** Don't give one key both
privileges and leave the split up to whoever's calling it.

### Docker

```bash
docker run -i --rm <image> --serve-stdio
```

```go
c, err := sandbox.Open(ctx, "docker", []string{"run", "-i", "--rm", image, "--serve-stdio"})
```

```python
c = san_db_ox.connect("docker", ["run", "-i", "--rm", image, "--serve-stdio"])
```

```typescript
const c = await connect("docker", ["run", "-i", "--rm", image, "--serve-stdio"]);
```

```rust
let mut c = san_db_ox_client::connect("docker", &["run", "-i", "--rm", image, "--serve-stdio"])?;
```

```java
SanDbOxClient c = SanDbOx.connect("docker", List.of("run", "-i", "--rm", image, "--serve-stdio"));
```

### Kubernetes

```bash
kubectl exec -i <pod> -- san-db-ox --serve-stdio
```

```go
c, err := sandbox.Open(ctx, "kubectl", []string{"exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"})
```

```python
c = san_db_ox.connect("kubectl", ["exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"])
```

```typescript
const c = await connect("kubectl", ["exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"]);
```

```rust
let mut c = san_db_ox_client::connect("kubectl", &["exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"])?;
```

```java
SanDbOxClient c = SanDbOx.connect("kubectl", List.of("exec", "-i", pod, "--", "san-db-ox", "--serve-stdio"));
```

## Over socat (socket)

Putting socat in front exposes SanDBox as a TCP/UNIX socket, something
direct-connect can't do on its own. That comes with two assumptions
direct-connect doesn't have.

- **Each connection gets its own process and its own database.** This is
  socat's own `fork` option at work (a new child process per accepted
  connection) -- SanDBox has no mechanism for multiple clients to share
  one database. Not a fit for a "one database, many clients" use case.
- **There is no authentication at all.** Anyone who can reach it can run
  arbitrary SQL. Always combine this with TLS or source-address
  restriction, covered below.

**Don't use the `overwrite` op (which replaces the running process's own
executable) over socat.** Multiple child processes could end up writing
the same executable path at once. (In the Go driver, `*SocketClient`
simply has no `Overwrite` method -- it can't be called, by the type
system. The Rust and Java drivers' `SocketClient` types have no
`overwrite` method either, for the same reason -- calling it is a compile
error, not a runtime one.)

### Plain TCP/UNIX socket

```bash
# UNIX domain socket
socat UNIX-LISTEN:/tmp/sandbox.sock,fork EXEC:"san-db-ox --read-only --serve-stdio"

# TCP (bind=127.0.0.1 keeps it unreachable from outside; see the next section too)
socat TCP-LISTEN:5432,fork,bind=127.0.0.1 EXEC:"san-db-ox --read-only --serve-stdio"
```

```go
c, err := sandbox.OpenSocket(ctx, "unix", "/tmp/sandbox.sock")
// or
c, err := sandbox.OpenSocket(ctx, "tcp", "127.0.0.1:5432")
```

```python
c = san_db_ox.connect_unix("/tmp/sandbox.sock")
# or
c = san_db_ox.connect_tcp("127.0.0.1", 5432)
```

```typescript
const c = await connectUnix("/tmp/sandbox.sock");
// or
const c2 = await connectTcp("127.0.0.1", 5432);
```

```rust
let mut c = san_db_ox_client::connect_unix("/tmp/sandbox.sock")?;
// or
let mut c2 = san_db_ox_client::connect_tcp(("127.0.0.1", 5432))?;
```

```java
SocketClient c = SanDbOx.connectUnix(Path.of("/tmp/sandbox.sock"));
// or
SocketClient c2 = SanDbOx.connectTcp("127.0.0.1", 5432);
```

### TLS/mTLS (client-certificate authentication)

socat itself has no authentication mechanism, so exposing this externally
means leaning on TLS client-certificate verification as the de facto
authentication. Below is a self-signed CA plus server/client certificate
set generated on the spot (for trying this out -- use your organization's
own CA in production).

```bash
# CA
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout ca-key.pem -out ca.pem -subj "/CN=my-ca"

# Server certificate -- without a SAN, recent TLS clients (including Go's
# crypto/tls) reject it as "relies on legacy Common Name field", so
# subjectAltName is required.
cat > server-ext.cnf <<'EOF'
subjectAltName = DNS:sandbox.example.com,IP:127.0.0.1
EOF
openssl req -newkey rsa:2048 -nodes \
  -keyout server-key.pem -out server-req.pem -subj "/CN=sandbox.example.com"
openssl x509 -req -in server-req.pem -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem -days 365 -extfile server-ext.cnf
cat server-key.pem server-cert.pem > server.pem

# Client certificate
openssl req -newkey rsa:2048 -nodes \
  -keyout client-key.pem -out client-req.pem -subj "/CN=my-client"
openssl x509 -req -in client-req.pem -CA ca.pem -CAkey ca-key.pem \
  -CAcreateserial -out client-cert.pem -days 365
cat client-key.pem client-cert.pem > client.pem
```

```bash
socat OPENSSL-LISTEN:5432,fork,cert=server.pem,cafile=ca.pem,verify=1 \
      EXEC:"san-db-ox --read-only --serve-stdio"
```

**`verify=1` (the default) verifying the client certificate is what makes
this a de facto authentication mechanism.** Setting `verify=0` to get
encryption without verification leaves you with "eavesdropping is
prevented but anyone can connect" -- since SanDBox itself has no
authentication, that's barely different from wide open. **Don't disable
`verify`.**

**What was actually verified**: a connection with the correct
`client.pem` succeeds and the hello line can be read, while a connection
with a different self-signed certificate outside the CA chain (unrelated
to the client certificate) gets refused server-side with
`SSL_accept(): certificate verify failed`.

The Go driver has no TLS-specific constructor; instead it accepts the
result of `crypto/tls.Dial` directly (`OpenSocketConn`):

```go
cert, err := tls.LoadX509KeyPair("client-cert.pem", "client-key.pem")
pool := x509.NewCertPool()
pool.AppendCertsFromPEM(caPEM) // contents of ca.pem

nc, err := tls.Dial("tcp", "host:5432", &tls.Config{
    RootCAs:      pool,
    Certificates: []tls.Certificate{cert},
    ServerName:   "sandbox.example.com", // must match the server certificate's SAN
})
c, err := sandbox.OpenSocketConn(ctx, nc)
```

This combination (`tls.Dial` → `OpenSocketConn`) was actually run,
confirming the hello line and a `query` round-trip.

The Python driver has the same shape: no TLS-specific constructor, just
`connect_socket` wrapping whatever `ssl.SSLContext.wrap_socket` returns:

```python
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.load_verify_locations("ca.pem")
ctx.load_cert_chain("client-cert.pem", "client-key.pem")

raw = socket.create_connection(("host", 5432))
tls_sock = ctx.wrap_socket(raw, server_hostname="sandbox.example.com")
c = san_db_ox.connect_socket(tls_sock)
```

This combination was also run against the same socat listener, confirming
the hello line and a `query` round-trip, and confirming a client
certificate outside the CA chain gets the same server-side rejection
(`SSL_accept(): certificate verify failed`) regardless of which driver is
connecting -- the check happens entirely on socat's side.

The TypeScript driver has the same shape too: no TLS-specific constructor,
just `connectSocket` wrapping whatever `tls.connect` returns (typed as a
`Duplex`, so the driver itself never imports `node:tls`):

```typescript
import { connect as tlsConnect } from "node:tls";
import { readFileSync } from "node:fs";

const socket = tlsConnect({
  host: "host",
  port: 5432,
  ca: readFileSync("ca.pem"),
  cert: readFileSync("client-cert.pem"),
  key: readFileSync("client-key.pem"),
  servername: "sandbox.example.com",
});
const c = await connectSocket(socket);
```

This combination (`tls.connect` → `connectSocket`) was also run against the
same socat listener, confirming the hello line and a `query` round-trip,
and confirming a client certificate outside the CA chain gets the same
server-side rejection (`SSL_accept(): certificate verify failed`) as the
other two drivers -- the check happens entirely on socat's side, so this
is expected rather than a coincidence.

Rust's standard library has no TLS at all, so this driver can't even name
a TLS type, let alone provide a TLS-specific constructor. The seam is the
same shape as the other three drivers' -- `connect_socket` takes anything
that implements `Read + Write`, so the caller brings their own TLS crate
(`rustls` below; `native-tls` works the same way) and this driver depends
on neither:

```rust
// rustls and its cert loader are the CALLER's dependencies. This driver
// depends on no TLS crate and has no TLS-specific constructor -- it only
// needs something that implements Read + Write.
let tcp = std::net::TcpStream::connect("host:5432")?;
// Required: connect_socket cannot set this itself on an arbitrary stream,
// and a rustls/native-tls read delegates to the underlying socket, so
// this is what bounds every read the driver makes.
tcp.set_read_timeout(Some(std::time::Duration::from_secs(30)))?;

let mut roots = rustls::RootCertStore::empty();
roots.add_parsable_certificates(load_certs("ca.pem")?);
let config = rustls::ClientConfig::builder()
    .with_root_certificates(roots)
    .with_client_auth_cert(load_certs("client-cert.pem")?, load_key("client-key.pem")?)?;
let conn = rustls::ClientConnection::new(
    std::sync::Arc::new(config),
    "sandbox.example.com".try_into()?, // must match the server certificate's SAN
)?;

let mut c = san_db_ox_client::connect_socket(
    rustls::StreamOwned::new(conn, tcp),
    SocketOptions::new(),
)?;
```

This combination (`rustls::StreamOwned` → `connect_socket`) was also run
against the same socat listener, confirming the hello line and a `query`
round-trip, confirming a client certificate outside the CA chain gets the
same server-side rejection (`SSL_accept(): certificate verify failed`) as
the other three drivers, and confirming that a read timeout set on the
pre-TLS `TcpStream` actually bounds the driver's reads through the TLS
wrapper -- the one claim above that's specific to Rust, so it was measured
(a 200ms `TcpStream` timeout against a deliberately slow query returned
`Error::Timeout` in ~255ms) rather than assumed.

Java's `javax.net.ssl.SSLSocket` is a `java.net.Socket` subclass, so
`connectSocket` takes it directly -- no TLS crate/library dependency here
either. Unlike the other four drivers, Java can't load a PEM certificate
directly, so the client certificate and CA need converting to PKCS#12
keystores first:

```bash
openssl pkcs12 -export -in client-cert.pem -inkey client-key.pem \
  -certfile ca.pem -out client.p12 -passout pass:changeit
keytool -importcert -noprompt -alias ca -file ca.pem \
  -keystore truststore.p12 -storetype PKCS12 -storepass changeit
```

```java
KeyStore keyStore = KeyStore.getInstance("PKCS12");
try (var in = Files.newInputStream(Path.of("client.p12"))) {
    keyStore.load(in, "changeit".toCharArray());
}
KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(keyStore, "changeit".toCharArray());

KeyStore trustStore = KeyStore.getInstance("PKCS12");
try (var in = Files.newInputStream(Path.of("truststore.p12"))) {
    trustStore.load(in, "changeit".toCharArray());
}
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustStore);

SSLContext ctx = SSLContext.getInstance("TLS");
ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket("host", 5432);
socket.startHandshake();

SocketClient c = SanDbOx.connectSocket(socket, new SocketOptions());
```

This combination (`SSLSocket` → `connectSocket`) was also run against the
same socat listener, confirming the hello line and a `query` round-trip,
and confirming a driver-level timeout set via `SocketOptions`/`setTimeout`
actually bounds a read through the TLS wrapper (a 200ms timeout against
the same deliberately slow query returned `ReadTimeoutException` in
~202ms). Unlike Rust, where the caller has to set the read timeout on the
pre-TLS stream itself because the driver cannot reach it, Java's
`connectSocket` calls `Socket.setSoTimeout` on the socket directly as
calls are made (overwriting anything set beforehand) -- `SSLSocket
extends Socket` closes the one gap Rust's own doc note above calls out,
so this driver has no equivalent caveat.

A client certificate outside the CA chain was also confirmed rejected,
with one TLS 1.3-specific wrinkle worth knowing: `SSLSocket.startHandshake()`
completing without an exception is **not** by itself proof the server
accepted the certificate. TLS 1.3 lets a client consider its own handshake
finished before it has processed the server's asynchronous rejection
alert, so `startHandshake()` can return normally even when socat is about
to close the connection. The rejection reliably surfaces one step later,
at the first actual read -- which is exactly what `connectSocket`'s own
hello-line read (the first thing it does) provides: it throws a
`SanDbOxException` wrapping the TLS alert (`Received fatal alert:
unknown_ca`), confirmed consistently across repeated runs.

### Restricting source IP addresses

```bash
# Loopback only (the safest option; pairs with an SSH port forward)
socat TCP-LISTEN:5432,fork,bind=127.0.0.1 EXEC:"san-db-ox --read-only --serve-stdio"

# Restrict allowed sources by CIDR
socat TCP-LISTEN:5432,fork,range=10.0.0.0/8 EXEC:"san-db-ox --read-only --serve-stdio"
```

**What was actually verified**: with `bind=127.0.0.1`, confirmed via
`ss -tln` that it listens only on `127.0.0.1`, not `0.0.0.0`. With
`range=`, confirmed that a connection from an out-of-range source
(`127.0.0.1` against `range=10.0.0.0/8`) is refused immediately with
`refusing connection from ... due to range option`, while an in-range
source (`range=127.0.0.0/8`) connects normally.

There's also a `tcpwrap[=<name>]` option (integrating with source
restriction via `/etc/hosts.allow`/`/etc/hosts.deny`), but it requires
editing system-wide configuration files, so it wasn't exercised here.

**Don't rely on socat's options alone -- use the OS firewall
(`iptables`/`nftables`/a cloud security group, etc.) as a last line of
defense.** A single mistyped socat startup command can widen exposure by
itself; that's exactly the kind of mistake a one-line command invites, so
keep another layer of restriction on the outside.

### Pairing with `--read-only`

Every example in this document includes `--read-only`. Treat that as the
baseline for any SanDBox exposed externally. If a read-write external
exposure is genuinely needed, combine it with TLS client authentication
above and restrict the source as tightly as possible.

## Other uses of SSH (supplementary)

- **Port forwarding** -- run socat bound to loopback only
  (`bind=127.0.0.1`), then tunnel from the client side with
  `ssh -L 15432:127.0.0.1:5432 user@host`. SSH handles encryption and
  authentication, and socat itself stays loopback-only so it's never
  directly reachable from outside.
- **SSH's own failure can be indistinguishable from a SanDBox-side
  error** -- SSH's own failures (unreachable host, host key mismatch,
  authentication failure, etc.) return exit code `255`, which is
  indistinguishable from the remote command (`san-db-ox`) itself exiting
  with `255`. Don't conclude "it's a SanDBox-side error" from the exit
  code alone -- check it alongside stderr's contents.
