/**
 * The transport layer: reading/writing bytes and managing a connection's
 * lifetime. Knows nothing about JSON Lines framing or the op vocabulary --
 * that is {@code internal.codec}'s job (this repository's architecture:
 * codec and transport layers never know each other's types). Exactly two
 * transports: {@code DirectTransport} (a child process, the default) and
 * {@code SocketTransport} (a TCP/UNIX socket, typically fronted by
 * something like socat).
 *
 * <p><b>Internal.</b> Public for the same reason as {@code
 * internal.codec}: this repository's conformance test suite drives {@code
 * DirectTransport} directly (some conformance cases send malformed
 * requests the typed API cannot construct), and Java has nothing between
 * package-private and public to grant that access without exposing it
 * everywhere. Application code should use {@link
 * io.github.amisonnet8.sandbox.Connection} instead.
 */
package io.github.amisonnet8.sandbox.internal.transport;
