package io.github.amisonnet8.sandbox;

import java.util.List;

/**
 * {@code query}'s result. Each row is a {@code List<Object>} whose elements
 * are {@code null}, {@link Long}, {@link Double}, {@link String}, or
 * {@code byte[]}, matching SQLite's NULL/INTEGER/REAL/TEXT/BLOB storage
 * classes. A REAL that is NaN is indistinguishable from SQL NULL once
 * decoded (both arrive over the wire as the JSON literal {@code null});
 * that is upstream's own representation, not a limitation added here.
 */
public record QueryResult(List<String> columns, List<List<Object>> rows) {
}
