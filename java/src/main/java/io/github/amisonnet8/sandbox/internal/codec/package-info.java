/**
 * The pure layer: JSON Lines encoding/decoding and value representation
 * (BLOB, REAL, 64-bit integers), with no knowledge of I/O or process
 * management (this repository's driver architecture: codec and transport
 * layers never know each other's types).
 *
 * <p><b>Internal.</b> This package (and {@code internal.transport}) is
 * public only because Java's package-private access is scoped to a single
 * exact package name, with nothing between that and fully public -- unlike
 * Rust's {@code pub(crate)}, which the driver this design is ported from
 * relies on to let its test suite reach across the codec/transport split
 * without exposing either to library consumers. Application code should
 * use {@link io.github.amisonnet8.sandbox.Connection} instead; nothing
 * here is part of this library's supported API and it may change or be
 * removed without notice.
 */
package io.github.amisonnet8.sandbox.internal.codec;
