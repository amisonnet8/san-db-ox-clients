# san-db-ox-clients

Client libraries for [SanDBox](https://github.com/amisonnet8/san-db-ox)
(a portable, zero-config, single-binary RDBMS that keeps both the engine
and the data in one executable) — thin, per-language wrappers that
launch it as a child process and talk to it over its stdio protocol.

**Drivers are a convenience, not a prerequisite.** The stdio protocol is
newline-delimited JSON (JSON Lines), so any runtime that can spawn a
subprocess and speak to its pipes can connect directly, without a
dedicated library. Drivers sit on top of that as a thin layer providing
a typed API, value-representation conversion, and a choice of
transport.

## Protocol compatibility

| | |
| :--- | :--- |
| Upstream repository | [amisonnet8/san-db-ox](https://github.com/amisonnet8/san-db-ox) |
| `protocol` number | `1` |

Driver version numbers are not tied to the upstream version; the
`protocol` number above is what expresses compatibility. See
[`.claude/rules/protocol.md`](.claude/rules/protocol.md) for the tracked
upstream tag and details.

## Language status

| Language | Status |
| :--- | :--- |
| Go | available — see [`go/`](go/) |
| Python | planned |
| TypeScript | planned |

## Connecting

Two transports are supported: direct-connect (spawning a child process
— the same transport works locally, over SSH, via Docker, or via
Kubernetes just by swapping the launch command) and a socket transport
for connecting to an endpoint exposed via socat or similar. For
security configuration when exposing an unauthenticated endpoint
externally (SSH forced commands, TLS client auth, etc.), see
[`.claude/rules/connectivity.md`](.claude/rules/connectivity.md).
Copy-pasteable command examples for every pattern above are in
[`docs/usage/connecting_ja.md`](docs/usage/connecting_ja.md) (Japanese
for now; an English version follows once more languages land, per this
repo's documentation policy).

## License

MIT. See [LICENSE](LICENSE).
