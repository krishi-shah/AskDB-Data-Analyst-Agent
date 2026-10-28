package ca.yorku.askdb.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.askdb.MockLLMProvider;
import ca.yorku.askdb.NoEmbeddings;
import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.config.Settings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentOrchestratorTest {
    private static final String GOOD_SQL = "SELECT c.region, ROUND(SUM(o.total), 2) AS revenue FROM orders o "
            + "JOIN customers c USING (customer_id) GROUP BY c.region ORDER BY revenue DESC";

    @TempDir
    Path home;

    private final MockLLMProvider mock = new MockLLMProvider();
    private final List<String> kinds = new ArrayList<>();
    private AskDBFacade facade;

    @BeforeEach
    void setUp() {
        Settings settings = new Settings();
        settings.home = home;
        facade = new AskDBFacade(settings, mock, new NoEmbeddings());
        facade.importDataset(List.of(Path.of("data", "sample_store.db")), false);
        facade.subscribe(event -> kinds.add(event.kind()));
    }

    @AfterEach
    void tearDown() {
        facade.close();
    }

    private static Map<String, Object> runQuery(String sql) {
        return Map.of("action", "tool", "tool", "run_query", "args", Map.of("sql", sql));
    }

    @Test
    void failedQueryIsRepairedAndAnswerVerified() {
        mock.reply(Map.of("category", "query", "approach", "revenue by region", "chart_hint", "bar"),
                runQuery("SELECT region, SUM(total) FROM orders"),
                runQuery(GOOD_SQL),
                Map.of("action", "final", "answer", "done"),
                Map.of("summary", "North has the highest revenue at 248,307.93."));
        AgentAnswer answer = facade.ask("Revenue by region?", true);
        assertEquals(AnswerStatus.ANSWERED, answer.getStatus());
        assertEquals(4, answer.getResult().rowCount());
        assertEquals("bar", answer.getChart().chartType());
        assertTrue(answer.getVerification().verified());
        assertTrue(kinds.contains("repair"));
        assertEquals(1, facade.getSession().getMemory().getTurns().size());
    }

    @Test
    void destructiveRequestIsRefusedWithoutTools() {
        mock.reply(Map.of("category", "destructive", "reason", "AskDB only reads data."));
        AgentAnswer answer = facade.ask("Delete all cancelled orders", true);
        assertEquals(AnswerStatus.REFUSED, answer.getStatus());
        assertFalse(kinds.contains("tool_call"));
    }

    @Test
    void clarificationPausesAndResumes() {
        mock.reply(Map.of("category", "query", "ambiguous", true,
                "clarification", Map.of("question", "Best by what?", "options", List.of("revenue", "units"))));
        AgentAnswer answer = facade.ask("best region", true);
        assertEquals(AnswerStatus.NEEDS_CLARIFICATION, answer.getStatus());
        assertTrue(facade.hasPendingClarification());
        mock.reply(runQuery(GOOD_SQL), Map.of("action", "final", "answer", "ok"), Map.of("summary", "North leads."));
        answer = facade.answerClarification("revenue");
        assertEquals(AnswerStatus.ANSWERED, answer.getStatus());
        assertFalse(facade.hasPendingClarification());
    }

    @Test
    void emptyResultThenFinalAnswerGivesNoData() {
        mock.reply(Map.of("category", "query"),
                runQuery("SELECT order_id FROM orders WHERE order_date LIKE '2019%'"),
                Map.of("action", "final", "answer", "There were no orders in 2019."));
        AgentAnswer answer = facade.ask("List the orders placed in 2019", true);
        assertEquals(AnswerStatus.NO_DATA, answer.getStatus());
        assertEquals(0, answer.getResult().rowCount());
    }

    @Test
    void repeatedFailingSqlStopsTheLoop() {
        mock.reply(Map.of("category", "query"),
                runQuery("SELECT x FROM nowhere"),
                runQuery("select x from nowhere;"));
        AgentAnswer answer = facade.ask("anything", true);
        assertEquals(AnswerStatus.FAILED, answer.getStatus());
    }
}
