package ca.yorku.askdb.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResponseParserTest {
    private final ResponseParser parser = new ResponseParser();

    @Test
    void fencedJsonIsRead() {
        String text = """
                Here is the plan:
                ```json
                {"category": "query", "approach": "revenue by region", "chart_hint": "bar",
                 "sql": " SELECT region FROM orders "}
                ```
                """;
        QueryPlan plan = parser.parsePlan(text);
        assertEquals("query", plan.category());
        assertEquals("revenue by region", plan.approach());
        assertEquals("bar", plan.chartHint());
        assertEquals("SELECT region FROM orders", plan.sql());
    }

    @Test
    void unknownCategoryBecomesAQuery() {
        QueryPlan plan = parser.parsePlan("{\"category\": \"essay\", \"sql\": \"SELECT 1\"}");
        assertEquals("query", plan.category());
        assertEquals("SELECT 1", plan.sql());
    }

    @Test
    void ambiguousStringNeedsARealClarification() {
        String withOptions = """
                {"category": "query", "ambiguous": "true", "vague_words": ["best"],
                 "clarification": {"question": "Best by what?", "options": ["revenue", "orders"]},
                 "sql": "SELECT 1"}
                """;
        QueryPlan plan = parser.parsePlan(withOptions);
        assertTrue(plan.ambiguous());
        assertEquals("Best by what?", plan.clarification().question());
        assertEquals(2, plan.clarification().options().size());

        QueryPlan bare = parser.parsePlan("{\"category\": \"query\", \"ambiguous\": \"true\", \"vague_words\": [\"best\"]}");
        assertFalse(bare.ambiguous());
    }

    @Test
    void missingKeysUseDefaultsAndDropSqlOutsideAQuery() {
        QueryPlan empty = parser.parsePlan("{}");
        assertEquals("query", empty.category());
        assertEquals("", empty.approach());
        assertEquals("", empty.sql());
        assertFalse(empty.ambiguous());

        QueryPlan destructive = parser.parsePlan(
                "{\"category\": \"destructive\", \"sql\": \"DELETE FROM orders\", \"reason\": \"writes\"}");
        assertEquals("destructive", destructive.category());
        assertEquals("", destructive.sql());
        assertEquals("writes", destructive.reason());
    }

    @Test
    void replyWithNoObjectIsRejected() {
        assertThrows(ParseException.class, () -> parser.parsePlan("not json"));
    }
}
