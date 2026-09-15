package io.github.amisonnet8.sandbox.internal.codec;

import io.github.amisonnet8.sandbox.DumpResult;
import io.github.amisonnet8.sandbox.ExecResult;
import io.github.amisonnet8.sandbox.Hello;
import io.github.amisonnet8.sandbox.InspectResult;
import io.github.amisonnet8.sandbox.QueryResult;
import io.github.amisonnet8.sandbox.SchemaResult;
import io.github.amisonnet8.sandbox.SnapshotResult;
import io.github.amisonnet8.sandbox.TablesResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decoding: turning wire JSON (parsed into a {@link Json} tree by {@link
 * JsonParser}) into this library's public result records, defined one
 * package up ({@code io.github.amisonnet8.sandbox}) since they are part of
 * the supported API and this package is not.
 */
public final class Decode {

    private Decode() {
    }

    /** Decodes a hello line. Does not validate the {@code protocol} number: that is the caller's job. */
    public static Hello hello(byte[] line) {
        Json v = parseLine(line, "hello");
        requireObject(v, "hello line");
        return new Hello((int) requiredInt(v, "protocol"), requiredStr(v, "version"), requiredStr(v, "product"));
    }

    /** Decodes one response line into (ok, the full field set). */
    public static DecodedResponse responseLine(byte[] line) {
        Json v = parseLine(line, "response");
        requireObject(v, "response line");
        Boolean ok = asBool(get(v, "ok"));
        return new DecodedResponse(ok != null && ok, v);
    }

    /** Extracts {@code {"error":{"code":...,"message":...}}} from a response, or {@code null} if absent/malformed. */
    public static ResponseError error(DecodedResponse r) {
        Json err = get(r.fields, "error");
        if (err == null) {
            return null;
        }
        String code = asStr(get(err, "code"));
        String message = asStr(get(err, "message"));
        if (code == null || message == null) {
            return null;
        }
        return new ResponseError(code, message);
    }

    public static QueryResult query(DecodedResponse r) {
        return new QueryResult(requiredStrList(r.fields, "columns"), decodeRows(required(r.fields, "rows")));
    }

    public static ExecResult exec(DecodedResponse r) {
        return new ExecResult(requiredInt(r.fields, "rows_affected"), requiredInt(r.fields, "last_insert_id"));
    }

    public static SnapshotResult snapshot(DecodedResponse r) {
        return new SnapshotResult(requiredStr(r.fields, "path"));
    }

    public static InspectResult inspect(DecodedResponse r) {
        return new InspectResult(
                requiredBool(r.fields, "has_data"),
                requiredOptionalLong(r.fields, "version"),
                requiredOptionalLong(r.fields, "data_length"),
                requiredStr(r.fields, "source"),
                requiredBool(r.fields, "read_only"));
    }

    public static TablesResult tables(DecodedResponse r) {
        return new TablesResult(requiredStrList(r.fields, "tables"));
    }

    public static SchemaResult schema(DecodedResponse r) {
        return new SchemaResult(requiredStrList(r.fields, "schema"));
    }

    public static DumpResult dump(DecodedResponse r) {
        return new DumpResult(requiredStr(r.fields, "sql"));
    }

    // --- value/rows decoding ---

    static Object decodeValue(Json raw) {
        if (raw instanceof Json.Null) {
            return null;
        }
        if (raw instanceof Json.Bool b) {
            throw new CodecException("unexpected boolean value in response: " + b.value());
        }
        if (raw instanceof Json.Num n) {
            NumberToken token = n.token();
            if (token.isReal()) {
                return token.asDouble();
            }
            return token.asLong();
        }
        if (raw instanceof Json.Str s) {
            return s.value();
        }
        if (raw instanceof Json.Arr a) {
            List<Json> items = a.items();
            if (items.size() != 1) {
                throw new CodecException(
                        "protocol violation: BLOB array must have exactly 1 element, got " + items.size());
            }
            String s = asStr(items.get(0));
            if (s == null) {
                throw new CodecException("protocol violation: BLOB array element is not a string");
            }
            return B64.decode(s);
        }
        throw new CodecException("unexpected object value in response");
    }

    static List<List<Object>> decodeRows(Json raw) {
        if (!(raw instanceof Json.Arr arr)) {
            throw new CodecException("rows field is not an array");
        }
        List<List<Object>> rows = new ArrayList<>(arr.items().size());
        int i = 0;
        for (Json row : arr.items()) {
            if (!(row instanceof Json.Arr rowArr)) {
                throw new CodecException("rows[" + i + "] is not an array");
            }
            List<Object> decoded = new ArrayList<>(rowArr.items().size());
            for (Json cell : rowArr.items()) {
                try {
                    decoded.add(decodeValue(cell));
                } catch (CodecException e) {
                    throw new CodecException("rows[" + i + "]: " + e.getMessage(), e);
                }
            }
            rows.add(decoded);
            i++;
        }
        return rows;
    }

    // --- Json tree accessors ---

    private static Json parseLine(byte[] line, String what) {
        try {
            return JsonParser.parse(new String(line, StandardCharsets.UTF_8));
        } catch (CodecException e) {
            throw new CodecException("invalid " + what + " line: " + e.getMessage(), e);
        }
    }

    private static void requireObject(Json v, String what) {
        if (!(v instanceof Json.Obj)) {
            throw new CodecException(what + " is not a JSON object");
        }
    }

    static Json get(Json v, String key) {
        if (!(v instanceof Json.Obj o)) {
            return null;
        }
        for (Json.Obj.Member m : o.members()) {
            if (m.key().equals(key)) {
                return m.value();
            }
        }
        return null;
    }

    private static Boolean asBool(Json v) {
        return (v instanceof Json.Bool b) ? b.value() : null;
    }

    private static String asStr(Json v) {
        return (v instanceof Json.Str s) ? s.value() : null;
    }

    private static Json required(Json v, String key) {
        Json field = get(v, key);
        if (field == null) {
            throw new CodecException("response missing field \"" + key + "\"");
        }
        return field;
    }

    private static String requiredStr(Json v, String key) {
        String s = asStr(required(v, key));
        if (s == null) {
            throw new CodecException("response field \"" + key + "\" is not a string");
        }
        return s;
    }

    private static boolean requiredBool(Json v, String key) {
        Boolean b = asBool(required(v, key));
        if (b == null) {
            throw new CodecException("response field \"" + key + "\" is not a bool");
        }
        return b;
    }

    private static long requiredInt(Json v, String key) {
        Json field = required(v, key);
        if (!(field instanceof Json.Num n)) {
            throw new CodecException("response field \"" + key + "\" is not an integer");
        }
        return n.token().asLong();
    }

    private static Long requiredOptionalLong(Json v, String key) {
        Json field = required(v, key);
        if (field instanceof Json.Null) {
            return null;
        }
        if (!(field instanceof Json.Num n)) {
            throw new CodecException("response field \"" + key + "\" is not an integer or null");
        }
        return n.token().asLong();
    }

    private static List<String> requiredStrList(Json v, String key) {
        Json field = required(v, key);
        if (!(field instanceof Json.Arr arr)) {
            throw new CodecException("response field \"" + key + "\" is not an array");
        }
        List<String> out = new ArrayList<>(arr.items().size());
        for (Json item : arr.items()) {
            String s = asStr(item);
            if (s == null) {
                throw new CodecException("response field \"" + key + "\" is not a string array");
            }
            out.add(s);
        }
        return out;
    }
}
