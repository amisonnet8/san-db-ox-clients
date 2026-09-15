package io.github.amisonnet8.sandbox.internal.codec;

/**
 * The {@code {"code":...,"message":...}} carried by an {@code "ok":false}
 * response. Plain data, not an exception: this package throws no checked
 * exceptions, so translating this into the public {@code
 * ResponseException} is left to {@code Session}.
 */
public record ResponseError(String code, String message) {
}
