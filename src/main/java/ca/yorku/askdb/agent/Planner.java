package ca.yorku.askdb.agent;

import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.llm.ChatMessage;
import ca.yorku.askdb.llm.ModelRouter;
import ca.yorku.askdb.retrieval.SchemaEntry;
import ca.yorku.askdb.retrieval.SchemaIndex;
import java.util.List;

/** Retrieves the relevant schema and asks the model for a QueryPlan. */
public class Planner {
    private final ModelRouter router;
    private final PromptBuilder builder;
    private final ResponseParser parser;
    private final int k;

    public Planner(ModelRouter router, PromptBuilder builder, ResponseParser parser, int k) {
        this.router = router;
        this.builder = builder;
        this.parser = parser;
        this.k = k;
    }

    public QueryPlan createPlan(String question, String context, SchemaIndex index, SchemaInfo schema) {
        List<String> hits;
        if (schema != null && (index == null || schema.getTables().size() <= 8)) {
            hits = List.of(schema.toText());
        } else if (index != null) {
            String tail = context.length() > 300 ? context.substring(context.length() - 300) : context;
            hits = index.search(question + " " + tail, k).stream().map(SchemaEntry::getText).toList();
        } else {
            hits = List.of();
        }
        List<ChatMessage> messages = builder.buildPlanPrompt(question, context, hits);
        for (int i = 0; i < 2; i++) {
            String text = router.complete(messages, "plan").text();
            try {
                return parser.parsePlan(text);
            } catch (ParseException e) {
                // ask once more; a second bad reply falls back to a plain query plan
            }
        }
        return QueryPlan.fallback();
    }
}
