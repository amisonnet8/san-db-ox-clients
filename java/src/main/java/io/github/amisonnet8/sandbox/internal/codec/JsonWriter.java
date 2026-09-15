package io.github.amisonnet8.sandbox.internal.codec;

/**
 * Serializes a {@link Json} tree back to text. {@link Json.Num} tokens are
 * written verbatim ({@link NumberToken#raw()}): whatever produced the
 * value (typically {@code Encode}) already computed the correct wire
 * text, so this class does not re-derive number formatting.
 *
 * <p>Branches on the sealed {@link Json} hierarchy with {@code instanceof}
 * pattern matching rather than a pattern-matching {@code switch}: the
 * latter was only finalized in Java 21 (JEP 441), and this driver's
 * minimum is Java 17.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(Json value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Json value, StringBuilder sb) {
        if (value instanceof Json.Null) {
            sb.append("null");
        } else if (value instanceof Json.Bool b) {
            sb.append(b.value() ? "true" : "false");
        } else if (value instanceof Json.Num n) {
            sb.append(n.token().raw());
        } else if (value instanceof Json.Str s) {
            writeString(s.value(), sb);
        } else if (value instanceof Json.Arr a) {
            sb.append('[');
            boolean first = true;
            for (Json item : a.items()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else if (value instanceof Json.Obj o) {
            sb.append('{');
            boolean first = true;
            for (Json.Obj.Member m : o.members()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(m.key(), sb);
                sb.append(':');
                writeValue(m.value(), sb);
            }
            sb.append('}');
        } else {
            throw new AssertionError("unreachable Json subtype: " + value.getClass());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
