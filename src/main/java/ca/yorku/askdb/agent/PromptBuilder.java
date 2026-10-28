package ca.yorku.askdb.agent;

import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import ca.yorku.askdb.llm.ChatMessage;
import ca.yorku.askdb.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builder pattern: one build method per prompt kind. Every prompt is assembled from the same parts: a system
 * instruction, the question, schema or tool context, and the JSON shape the model must return.
 */
public class PromptBuilder {
    static final String SYSTEM_ROLE = "You are AskDB, a careful data analyst working with a read-only SQLite "
            + "database. You only reply with one JSON object and nothing else.";

    private static final Map<String, Object> PLAN_SHAPE = ordered(
            "category", "query | destructive | unanswerable",
            "approach", "one sentence on how to answer",
            "tables", List.of("table names that are needed"),
            "chart_hint", "bar | line | pie | scatter | table | none",
            "vague_words", List.of("words in the question whose measure is not stated, such as best, top, or active"),
            "ambiguous", "true if vague_words is not empty, otherwise false",
            "clarification", ordered("question", "only if ambiguous", "options", List.of("meaning 1", "meaning 2")),
            "reason", "why, if destructive or unanswerable");
    private static final Map<String, Object> ACTION_SHAPE_TOOL = ordered(
            "thought", "short reason", "action", "tool", "tool", "run_query",
            "args", ordered("sql", "SELECT ... (put every argument the tool needs here)"));
    private static final Map<String, Object> ACTION_SHAPE_FINAL = ordered(
            "thought", "short reason", "action", "final", "answer", "one sentence answer");

    private boolean shareSamples;
    private final List<String> parts = new ArrayList<>();

    public PromptBuilder(boolean shareSamples) {
        this.shareSamples = shareSamples;
    }

    public void setShareSamples(boolean shareSamples) {
        this.shareSamples = shareSamples;
    }

    private PromptBuilder reset() {
        parts.clear();
        return this;
    }

    private PromptBuilder add(String title, String body) {
        if (body != null && !body.isEmpty()) {
            parts.add("## " + title + "\n" + body);
        }
        return this;
    }

    @SafeVarargs
    private PromptBuilder shape(Map<String, Object>... shapes) {
        String text = java.util.Arrays.stream(shapes).map(Json::pretty).collect(Collectors.joining("\n"));
        return add("Reply with JSON shaped like" + (shapes.length > 1 ? " one of" : ""), text);
    }

    private List<ChatMessage> messages() {
        String user = String.join("\n\n", parts);
        parts.clear();
        return List.of(ChatMessage.system(SYSTEM_ROLE), ChatMessage.user(user));
    }

    public List<ChatMessage> buildPlanPrompt(String question, String context, List<String> hits) {
        return reset()
                .add("Task", "Plan how to answer the question with SQL over the schema below. "
                        + "Classify it: 'destructive' if it asks to change or delete data; "
                        + "'unanswerable' only if no table or column in the schema relates to it at all; "
                        + "otherwise 'query'. A vague word is never a reason for 'unanswerable': if a key "
                        + "word has two or more reasonable meanings in this schema (for example 'best' "
                        + "could mean most revenue or most orders), use category 'query', set ambiguous "
                        + "to true, and give 2 to 4 short options.")
                .add("Earlier conversation", context)
                .add("Relevant schema", String.join("\n", hits))
                .add("Question", question)
                .shape(PLAN_SHAPE)
                .messages();
    }

    public List<ChatMessage> buildStepPrompt(AgentState state, List<Map<String, Object>> specs) {
        return reset()
                .add("Task", "Answer the question by calling one tool per reply. Write SQLite SQL. "
                        + "Look at the schema first if you are unsure of names. When a run_query result "
                        + "answers the question, reply with action 'final'. Never invent numbers. "
                        + "After joining a table to its detail rows (one-to-many), do not SUM a column of the "
                        + "parent table, because it repeats once per detail row.")
                .add("Tools", Json.pretty(specs))
                .add("Schema (use these exact table and column names)", state.schemaText)
                .add("Earlier conversation", state.context)
                .add("Plan", planText(state))
                .add("Clarified meaning", String.join("; ", state.clarified))
                .add("Steps so far", observationsText(state))
                .add("Question", state.question)
                .shape(ACTION_SHAPE_TOOL, ACTION_SHAPE_FINAL)
                .messages();
    }

    public List<ChatMessage> buildRepairPrompt(AgentState state, String error, List<Map<String, Object>> specs) {
        return reset()
                .add("Task", "The last query failed (repair attempt " + state.repairCount + "). Fix it. "
                        + "Use search_schema or column_values to check real names and values, "
                        + "or call run_query with corrected SQL. Do not repeat the same SQL.")
                .add("Tools", specs == null || specs.isEmpty() ? "" : Json.pretty(specs))
                .add("Schema (use these exact table and column names)", state.schemaText)
                .add("Failed SQL", state.lastSql)
                .add("Error", error)
                .add("Plan", planText(state))
                .add("Clarified meaning", String.join("; ", state.clarified))
                .add("Steps so far", observationsText(state))
                .add("Question", state.question)
                .shape(ACTION_SHAPE_TOOL, ACTION_SHAPE_FINAL)
                .messages();
    }

    public List<ChatMessage> buildInsightPrompt(String question, QueryResult result, String feedback) {
        return reset()
                .add("Task", "Write a 1 to 3 sentence summary that answers the question from this result, "
                        + "naming the most important values (for example the highest and lowest). "
                        + "Only use numbers that appear in the result, or totals, averages, shares, "
                        + "and percentage changes computed from it. Round to at most 2 decimals.")
                .add("Correction", feedback)
                .add("Question", question)
                .add("Result", result.previewText(50))
                .shape(ordered("summary", "text"))
                .messages();
    }

    public List<ChatMessage> buildDescriptionPrompt(SchemaInfo schema, String table) {
        List<String> blocks = new ArrayList<>();
        for (TableInfo t : schema.getTables()) {
            if (table != null && !table.isEmpty() && !t.getName().equals(table)) {
                continue;
            }
            String cols = t.getColumns().stream().map(c -> c.getName() + " " + c.getType())
                    .collect(Collectors.joining(", "));
            String block = "TABLE " + t.getName() + " (" + t.getRowCount() + " rows): " + cols;
            if (shareSamples && !t.getSampleRows().isEmpty()) {
                block += "\nSample rows:\n" + t.getSampleRows().stream().limit(3)
                        .map(PromptBuilder::rowText).collect(Collectors.joining("\n"));
            }
            blocks.add(block);
        }
        return reset()
                .add("Task", "Write a short plain-English description (under 15 words) for every table "
                        + "and every column, so an analyst knows what each holds.")
                .add("Schema", String.join("\n\n", blocks))
                .shape(ordered("tables", List.of(ordered("name", "table", "description", "text",
                        "columns", ordered("column_name", "description")))))
                .messages();
    }

    private static String rowText(List<Object> row) {
        return row.stream().map(v -> v instanceof String s ? "'" + s + "'" : QueryResult.format(v))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String planText(AgentState state) {
        QueryPlan plan = state.plan;
        if (plan == null) {
            return "";
        }
        String tables = plan.tables().isEmpty() ? "unknown" : String.join(", ", plan.tables());
        return plan.approach() + " (tables: " + tables + ")";
    }

    private static String observationsText(AgentState state) {
        List<Observation> obs = state.observations;
        List<Observation> recent = obs.subList(Math.max(0, obs.size() - 6), obs.size());
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < recent.size(); i++) {
            Observation o = recent.get(i);
            String call = o.action().tool() + "(" + Json.write(o.action().args()) + ")";
            String content = o.content().length() > 1500 ? o.content().substring(0, 1500) : o.content();
            lines.add((i + 1) + ". " + call + " -> " + (o.ok() ? "ok" : "error") + "\n" + content);
        }
        return String.join("\n", lines);
    }

    static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
