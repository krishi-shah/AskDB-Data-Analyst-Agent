package ca.yorku.askdb.agent;

import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.llm.LLMException;
import ca.yorku.askdb.llm.ModelRouter;

/** Grounded insight summary (F09): the model writes the summary, InsightVerifier checks every number. */
public class InsightGenerator {
    public static final String EMPTY_MESSAGE = "The query returned no rows, so there is nothing to summarize.";

    private final ModelRouter router;
    private final PromptBuilder builder;
    private final ResponseParser parser;

    public InsightGenerator(ModelRouter router, PromptBuilder builder, ResponseParser parser) {
        this.router = router;
        this.builder = builder;
        this.parser = parser;
    }

    public Insight generate(String question, QueryResult result, String feedback) {
        if (result.isEmpty()) {
            return new Insight(EMPTY_MESSAGE);
        }
        try {
            String text = router.complete(builder.buildInsightPrompt(question, result, feedback), "insight", 1, 300)
                    .text();
            return parser.parseInsight(text);
        } catch (LLMException | ParseException e) {
            return new Insight("No summary could be written (" + e.getMessage() + "). The result table is shown below.");
        }
    }
}
