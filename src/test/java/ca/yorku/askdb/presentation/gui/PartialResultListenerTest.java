package ca.yorku.askdb.presentation.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.data.QueryResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class PartialResultListenerTest {
    @Test
    void resultEventShowsTheTableBeforeTheSummary() throws Exception {
        QueryResult data = new QueryResult(List.of("region"), List.of(List.of("North")), false, 1, "SELECT 1");
        ChartSpec chart = ChartSpec.table("t", data.getColumns(), data.getRows());
        AtomicReference<QueryResult> shown = new AtomicReference<>();
        AtomicReference<String> sql = new AtomicReference<>();
        AtomicReference<ChartSpec> shownChart = new AtomicReference<>();
        PartialResultListener listener = new PartialResultListener((result, query, spec) -> {
            shown.set(result);
            sql.set(query);
            shownChart.set(spec);
        });
        listener.onEvent(new AgentEvent("result", "Query returned 1 rows", 1,
                Map.of("result", data, "sql", "SELECT 1", "chart", chart), 0));
        SwingUtilities.invokeAndWait(() -> { });
        assertSame(data, shown.get());
        assertEquals("SELECT 1", sql.get());
        assertSame(chart, shownChart.get());
    }

    @Test
    void otherEventsAreIgnored() throws Exception {
        AtomicReference<QueryResult> shown = new AtomicReference<>();
        new PartialResultListener((result, sql, chart) -> shown.set(result))
                .onEvent(new AgentEvent("plan", "planning", 0, Map.of(), 0));
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(null, shown.get());
    }
}
