package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;
import java.util.List;
import java.util.Locale;

/** Factory: returns the hinted chart if it fits the result, otherwise the first chart that fits. */
public class ChartFactory {
    public static final List<String> CHART_TYPES = List.of("line", "bar", "scatter", "pie", "table");

    private final List<ChartProduct> order = List.of(
            new LineChart(), new BarChart(), new ScatterChart(), new PieChart(), new TableChart());

    public ChartProduct create(QueryResult result, String hint) {
        String wanted = hint == null ? "" : hint.toLowerCase(Locale.ROOT);
        for (ChartProduct chart : order) {
            if (chart.name().equals(wanted) && chart.suits(result)) {
                return chart;
            }
        }
        return order.stream().filter(c -> c.suits(result)).findFirst().orElseThrow();
    }

    public List<String> fits(QueryResult result) {
        return order.stream().filter(c -> c.suits(result)).map(ChartProduct::name).toList();
    }
}
