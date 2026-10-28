package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Registers the tools, describes them to the model, and checks arguments before running one. */
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public List<String> names() {
        return new ArrayList<>(tools.keySet());
    }

    public List<Map<String, Object>> specs(Collection<String> exclude) {
        List<Map<String, Object>> out = new ArrayList<>();
        tools.forEach((name, tool) -> {
            if (!exclude.contains(name)) {
                out.add(tool.spec());
            }
        });
        return out;
    }

    public ToolResult execute(String name, Map<String, Object> args, Session session) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.failure("Unknown tool '" + name + "'. Available tools: "
                    + String.join(", ", tools.keySet()) + ".");
        }
        Map<String, Object> given = args == null ? Map.of() : args;
        String problem = checkArgs(tool, given);
        if (!problem.isEmpty()) {
            return ToolResult.failure(problem);
        }
        try {
            return tool.execute(coerce(tool, given), session);
        } catch (RuntimeException e) {
            return ToolResult.failure(name + " failed: " + e.getMessage());
        }
    }

    private static String checkArgs(Tool tool, Map<String, Object> args) {
        TreeSet<String> unknown = new TreeSet<>(args.keySet());
        unknown.removeAll(tool.parameters().keySet());
        if (!unknown.isEmpty()) {
            return "Unknown argument(s) for " + tool.name() + ": " + String.join(", ", unknown) + ".";
        }
        for (Map.Entry<String, Tool.Param> e : tool.parameters().entrySet()) {
            String arg = e.getKey();
            Tool.Param meta = e.getValue();
            Object value = args.get(arg);
            if (meta.required() && (value == null || "".equals(value))) {
                return tool.name() + " needs the argument '" + arg + "'.";
            }
            if (value == null) {
                continue;
            }
            boolean fits = switch (meta.type()) {
                case "string" -> value instanceof String;
                case "integer" -> value instanceof Integer || value instanceof Long
                        || value instanceof String s && s.strip().matches("\\d+");
                case "list" -> value instanceof List;
                default -> false;
            };
            if (!fits) {
                return "Argument '" + arg + "' of " + tool.name() + " must be a " + meta.type() + ".";
            }
        }
        return "";
    }

    private static Map<String, Object> coerce(Tool tool, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>(args);
        tool.parameters().forEach((arg, meta) -> {
            if (meta.type().equals("integer") && out.get(arg) instanceof String s) {
                out.put(arg, Integer.parseInt(s.strip()));
            }
        });
        return out;
    }
}
