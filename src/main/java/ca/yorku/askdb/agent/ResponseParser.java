package ca.yorku.askdb.agent;

import ca.yorku.askdb.util.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Turns model text into typed objects. Bad output raises ParseException. */
public class ResponseParser {
    public static final Set<String> CHART_HINTS = Set.of("bar", "line", "pie", "scatter", "table");

    /** One table description returned by the model. */
    public record TableDescription(String name, String description, Map<String, String> columns) {
    }

    /** Reads the first JSON object in text, tolerating code fences and leading prose. */
    public static JsonNode extractJson(String text) {
        String cleaned = text == null ? "" : text.strip().replaceAll("(?m)^```(?:json)?|```$", "").strip();
        int start = cleaned.indexOf('{');
        if (start == -1) {
            throw new ParseException("The model reply contained no JSON object.");
        }
        JsonNode node;
        try (JsonParser parser = Json.MAPPER.getFactory().createParser(cleaned.substring(start))) {
            node = Json.MAPPER.readTree(parser);
        } catch (JsonProcessingException e) {
            throw new ParseException("The model reply was not valid JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new ParseException("The model reply was not valid JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new ParseException("The model reply was not a JSON object.");
        }
        return node;
    }

    public QueryPlan parsePlan(String text) {
        JsonNode data = extractJson(text);
        String category = Json.text(data, "category", "query").strip().toLowerCase(Locale.ROOT);
        if (!List.of("query", "destructive", "unanswerable").contains(category)) {
            category = "query";
        }
        String hint = Json.text(data, "chart_hint", "").strip().toLowerCase(Locale.ROOT);
        ClarificationRequest clar = null;
        JsonNode raw = data.get("clarification");
        if (raw != null && raw.isObject()) {
            List<String> options = new ArrayList<>();
            raw.path("options").forEach(o -> {
                String s = o.asText().strip();
                if (!s.isEmpty()) {
                    options.add(s);
                }
            });
            String question = Json.text(raw, "question", "").strip();
            if (!question.isEmpty() && options.size() >= 2) {
                clar = new ClarificationRequest(question, options.subList(0, Math.min(4, options.size())));
            }
        }
        List<String> tables = new ArrayList<>();
        JsonNode t = data.get("tables");
        if (t != null && t.isArray()) {
            t.forEach(x -> tables.add(x.asText()));
        }
        boolean ambiguous = Json.text(data, "ambiguous", "false").strip().equalsIgnoreCase("true") && clar != null;
        return new QueryPlan(category, Json.text(data, "approach", ""), tables,
                CHART_HINTS.contains(hint) ? hint : "", ambiguous, clar, Json.text(data, "reason", ""));
    }

    public AgentAction parseAction(String text) {
        JsonNode data = extractJson(text);
        String kind = Json.text(data, "action", "").strip().toLowerCase(Locale.ROOT);
        String thought = Json.text(data, "thought", "");
        if (kind.equals("final")) {
            return AgentAction.fin(thought, Json.text(data, "answer", ""));
        }
        String tool = Json.text(data, "tool", "").strip();
        JsonNode args = data.get("args");
        if ((kind.equals("tool") || kind.isEmpty()) && !tool.isEmpty()) {
            if (args != null && !args.isNull() && !args.isObject()) {
                throw new ParseException("Tool args must be a JSON object.");
            }
            return AgentAction.tool(tool, toMap(args), thought);
        }
        if (!kind.isEmpty() && !kind.equals("tool")) {
            return AgentAction.tool(kind, args != null && args.isObject() ? toMap(args) : Map.of(), thought);
        }
        throw new ParseException("The reply must have action 'tool' with a tool name, or action 'final'.");
    }

    public Insight parseInsight(String text) {
        String summary;
        try {
            JsonNode data = extractJson(text);
            summary = Json.text(data, "summary", "");
            if (summary.isEmpty()) {
                summary = Json.text(data, "text", "");
            }
        } catch (ParseException e) {
            summary = text == null ? "" : text;
        }
        summary = summary.strip();
        if (summary.isEmpty()) {
            throw new ParseException("The summary was empty.");
        }
        return new Insight(summary);
    }

    public List<TableDescription> parseDescriptions(String text) {
        JsonNode data = extractJson(text);
        JsonNode tables = data.path("tables");
        if (!tables.isArray()) {
            throw new ParseException("Expected a list of tables.");
        }
        List<TableDescription> parsed = new ArrayList<>();
        for (JsonNode item : tables) {
            if (!item.isObject() || !item.has("name")) {
                continue;
            }
            Map<String, String> cols = new LinkedHashMap<>();
            JsonNode c = item.path("columns");
            if (c.isObject()) {
                c.fields().forEachRemaining(e -> cols.put(e.getKey(), e.getValue().asText()));
            } else if (c.isArray()) {
                c.forEach(x -> {
                    if (x.isObject()) {
                        cols.put(Json.text(x, "name", ""), Json.text(x, "description", ""));
                    }
                });
            }
            parsed.add(new TableDescription(item.get("name").asText(), Json.text(item, "description", ""), cols));
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return Json.MAPPER.convertValue(node, LinkedHashMap.class);
    }
}
