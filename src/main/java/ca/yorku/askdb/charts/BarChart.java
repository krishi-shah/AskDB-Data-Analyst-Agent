package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BarChart extends ChartProduct {
    @Override
    public String name() {
        return "bar";
    }

    @Override
    public boolean suits(QueryResult result) {
        return result.rowCount() >= 1 && result.rowCount() <= 30
                && !labelColumns(result).isEmpty() && !result.numericColumns().isEmpty();
    }

    @Override
    public ChartSpec render(QueryResult result) {
        int x = labelColumns(result).get(0);
        List<Integer> yCols = result.numericColumns();
        Map<String, List<Object>> series = new LinkedHashMap<>();
        for (int i : yCols.subList(0, Math.min(3, yCols.size()))) {
            series.put(result.getColumns().get(i), column(result.getRows(), i));
        }
        List<String> yNames = new ArrayList<>(series.keySet());
        String xName = result.getColumns().get(x);
        List<Object> labels = new ArrayList<>(column(result.getRows(), x).stream().map(ChartProduct::label).toList());
        return new ChartSpec("bar", title(yNames, xName), xName, yNames.size() == 1 ? yNames.get(0) : "value",
                labels, series, result.getColumns(), result.getRows());
    }
}
