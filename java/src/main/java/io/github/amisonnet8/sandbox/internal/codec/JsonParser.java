package io.github.amisonnet8.sandbox.internal.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written, byte-position-based recursive-descent JSON parser (RFC
 * 8259). Written from scratch because {@code java.util.json}/{@code
 * javax.json} do not exist in the JDK standard library the way this
 * repository's other drivers had one available, and because a stock
 * parser would eagerly convert numbers to {@code double}, losing 64-bit
 * integer precision ({@link NumberToken} is what avoids that).
 *
 * <p>Deliberately rejected, matching every other driver in this
 * repository: trailing commas, comments, a leading {@code +}, a leading
 * zero followed by another digit, bareword {@code NaN}/{@code Infinity}/
 * {@code -Infinity}, and unescaped control characters in strings. None of
 * these need special-case code: the grammar below simply does not have a
 * production for them, so they fall through to "unexpected character."
 *
 * <p>Surrogate pairs need no special handling here, unlike in a language
 * whose strings are UTF-32 codepoints: Java {@code String}/{@code char}
 * are UTF-16, so two {@code \\uXXXX \\uYYYY} escapes for a surrogate pair
 * simply append two {@code char}s that already form a valid surrogate
 * pair in the resulting {@code String}.
 */
public final class JsonParser {

    private final String s;
    private final int len;
    private int i;

    private JsonParser(String s) {
        this.s = s;
        this.len = s.length();
    }

    public static Json parse(String s) {
        JsonParser p = new JsonParser(s);
        p.skipWhitespace();
        Json v = p.parseValue();
        p.skipWhitespace();
        if (p.i != p.len) {
            throw p.err("unexpected trailing data after JSON value");
        }
        return v;
    }

    private CodecException err(String message) {
        return new CodecException(message + " at position " + i);
    }

    private void skipWhitespace() {
        while (i < len) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private Json parseValue() {
        if (i >= len) {
            throw err("unexpected end of input");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return parseObject();
            case '[':
                return parseArray();
            case '"':
                return new Json.Str(parseString());
            case 't':
                expectLiteral("true");
                return new Json.Bool(true);
            case 'f':
                expectLiteral("false");
                return new Json.Bool(false);
            case 'n':
                expectLiteral("null");
                return new Json.Null();
            default:
                if (c == '-' || isDigit(c)) {
                    return parseNumber();
                }
                throw err("unexpected character '" + c + "'");
        }
    }

    private void expectLiteral(String literal) {
        if (i + literal.length() > len || !s.regionMatches(i, literal, 0, literal.length())) {
            throw err("invalid literal, expected \"" + literal + "\"");
        }
        i += literal.length();
    }

    private Json.Obj parseObject() {
        i++; // consume '{'
        List<Json.Obj.Member> members = new ArrayList<>();
        skipWhitespace();
        if (i < len && s.charAt(i) == '}') {
            i++;
            return new Json.Obj(members);
        }
        while (true) {
            skipWhitespace();
            if (i >= len || s.charAt(i) != '"') {
                throw err("expected string key");
            }
            String key = parseString();
            skipWhitespace();
            if (i >= len || s.charAt(i) != ':') {
                throw err("expected ':'");
            }
            i++;
            skipWhitespace();
            Json value = parseValue();
            members.add(new Json.Obj.Member(key, value));
            skipWhitespace();
            if (i >= len) {
                throw err("unterminated object");
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
            } else if (c == '}') {
                i++;
                break;
            } else {
                throw err("expected ',' or '}'");
            }
        }
        return new Json.Obj(members);
    }

    private Json.Arr parseArray() {
        i++; // consume '['
        List<Json> items = new ArrayList<>();
        skipWhitespace();
        if (i < len && s.charAt(i) == ']') {
            i++;
            return new Json.Arr(items);
        }
        while (true) {
            skipWhitespace();
            items.add(parseValue());
            skipWhitespace();
            if (i >= len) {
                throw err("unterminated array");
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
            } else if (c == ']') {
                i++;
                break;
            } else {
                throw err("expected ',' or ']'");
            }
        }
        return new Json.Arr(items);
    }

    private String parseString() {
        i++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= len) {
                throw err("unterminated string");
            }
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                return sb.toString();
            }
            if (c == '\\') {
                i++;
                if (i >= len) {
                    throw err("unterminated escape sequence");
                }
                char e = s.charAt(i);
                switch (e) {
                    case '"' -> {
                        sb.append('"');
                        i++;
                    }
                    case '\\' -> {
                        sb.append('\\');
                        i++;
                    }
                    case '/' -> {
                        sb.append('/');
                        i++;
                    }
                    case 'b' -> {
                        sb.append('\b');
                        i++;
                    }
                    case 'f' -> {
                        sb.append('\f');
                        i++;
                    }
                    case 'n' -> {
                        sb.append('\n');
                        i++;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i++;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i++;
                    }
                    case 'u' -> {
                        i++;
                        sb.append((char) parseHex4());
                    }
                    default -> throw err("invalid escape character '" + e + "'");
                }
                continue;
            }
            if (c < 0x20) {
                throw err("unescaped control character in string");
            }
            sb.append(c);
            i++;
        }
    }

    private int parseHex4() {
        if (i + 4 > len) {
            throw err("invalid \\u escape: not enough hex digits");
        }
        int value = 0;
        for (int k = 0; k < 4; k++) {
            char c = s.charAt(i + k);
            int d;
            if (c >= '0' && c <= '9') {
                d = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                d = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                d = c - 'A' + 10;
            } else {
                throw err("invalid hex digit '" + c + "' in \\u escape");
            }
            value = (value << 4) | d;
        }
        i += 4;
        return value;
    }

    private Json.Num parseNumber() {
        int start = i;
        if (i < len && s.charAt(i) == '-') {
            i++;
        }
        if (i >= len || !isDigit(s.charAt(i))) {
            throw err("invalid number");
        }
        if (s.charAt(i) == '0') {
            // A leading zero must stand alone: "01" is rejected because
            // parsing stops after this single '0', leaving "1" as
            // unconsumed trailing data that the caller's comma/bracket
            // check (or the top-level trailing-data check) rejects.
            i++;
        } else {
            while (i < len && isDigit(s.charAt(i))) {
                i++;
            }
        }
        if (i < len && s.charAt(i) == '.') {
            i++;
            if (i >= len || !isDigit(s.charAt(i))) {
                throw err("invalid number: expected digit after '.'");
            }
            while (i < len && isDigit(s.charAt(i))) {
                i++;
            }
        }
        if (i < len && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < len && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            if (i >= len || !isDigit(s.charAt(i))) {
                throw err("invalid number: expected digit in exponent");
            }
            while (i < len && isDigit(s.charAt(i))) {
                i++;
            }
        }
        return new Json.Num(new NumberToken(s.substring(start, i)));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
