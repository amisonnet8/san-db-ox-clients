package io.github.amisonnet8.sandbox;

/** The greeting a SanDBox process sends before any request. */
public record Hello(int protocol, String version, String product) {
}
