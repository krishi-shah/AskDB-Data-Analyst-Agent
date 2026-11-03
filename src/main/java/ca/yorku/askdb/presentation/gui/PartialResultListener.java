package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.agent.AgentEventListener;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.data.QueryResult;
import javax.swing.SwingUtilities;

/** Observer that shows the table and chart as soon as the query finishes, before the summary. */
public class PartialResultListener implements AgentEventListener {
    @FunctionalInterface
    public interface Sink {
        void show(QueryResult result, String sql, ChartSpec chart);
    }

    private final Sink sink;

    public PartialResultListener(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (!event.kind().equals("result") || !(event.get("result") instanceof QueryResult result)) {
            return;
        }
        String sql = event.get("sql") == null ? "" : String.valueOf(event.get("sql"));
        ChartSpec chart = event.get("chart") instanceof ChartSpec spec ? spec : null;
        SwingUtilities.invokeLater(() -> sink.show(result, sql, chart));
    }
}
