package ca.yorku.askdb.charts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything needed to draw one chart; produced by a ChartProduct and drawn by ChartRenderer. */
public record ChartSpec(String chartType, String title, String xLabel, String yLabel, List<Object> labels,
                        Map<String, List<Object>> series, List<String> columns, List<List<Object>> rows) {

    public ChartSpec {
        series = new LinkedHashMap<>(series);
    }

    public static ChartSpec table(String title, List<String> columns, List<List<Object>> rows) {
        return new ChartSpec("table", title, "", "", List.of(), Map.of(), columns, rows);
    }
}
