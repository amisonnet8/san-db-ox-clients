package io.github.amisonnet8.sandbox;

import io.github.amisonnet8.sandbox.internal.codec.Json;

import java.util.ArrayList;
import java.util.List;

/** Small {@link Json} tree accessors shared by {@link MatchTest} and {@link ConformanceTest}. */
final class ConformanceSupport {

    private ConformanceSupport() {
    }

    static Json get(Json obj, String key) {
        if (!(obj instanceof Json.Obj o)) {
            return null;
        }
        for (Json.Obj.Member m : o.members()) {
            if (m.key().equals(key)) {
                return m.value();
            }
        }
        return null;
    }

    static String asString(Json v) {
        return (v instanceof Json.Str s) ? s.value() : null;
    }

    static List<String> asStringList(Json v) {
        if (!(v instanceof Json.Arr a)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Json item : a.items()) {
            String s = asString(item);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }
}
