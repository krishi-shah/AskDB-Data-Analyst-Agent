package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import java.util.LinkedHashMap;
import java.util.Map;

/** One action the agent may take. */
public interface Tool {
    /** One argument: type is "string", "integer", or "list". */
    record Param(String type, boolean required, String description) {
    }

    String name();

    String description();

    /** Argument name to its type, requirement, and meaning, in display order. */
    Map<String, Param> parameters();

    ToolResult execute(Map<String, Object> args, Session session);

    default Map<String, Object> spec() {
        Map<String, Object> args = new LinkedHashMap<>();
        parameters().forEach((k, p) ->
                args.put(k, p.type() + (p.required() ? "" : ", optional") + ": " + p.description()));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name());
        spec.put("description", description());
        spec.put("args", args);
        return spec;
    }

    static String quoteIdent(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    static int intArg(Map<String, Object> args, String key, int fallback, int min, int max) {
        Object v = args.get(key);
        int n = v instanceof Number num ? num.intValue() : fallback;
        if (n == 0) {
            n = fallback;
        }
        return Math.max(min, Math.min(n, max));
    }
}
