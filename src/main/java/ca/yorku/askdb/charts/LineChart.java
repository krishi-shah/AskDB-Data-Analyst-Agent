package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LineChart extends ChartProduct {
    @Override
    public String name() {
        return "line";
    }

    static Integer timeAxis(QueryResult result) {
        List<Integer> labels = labelColumns(result);
        if (!labels.isEmpty() && isTimeColumn(result, labels.get(0))) {
            return labels.get(0);
        }
        if (labels.isEmpty() && !result.getColumns().isEmpty() && isTimeColumn(result, 0)) {
            return 0;
        }
        return null;
    }

    @Override
    public boolean suits(QueryResult result) {
        Integer x = timeAxis(result);
        return result.rowCount() >= 2 && x != null && result.numericColumns().stream().anyMatch(i -> !i.equals(x));
    }

    @Override
    public ChartSpec render(QueryResult result) {
        int x = timeAxis(result);
        List<List<Object>> rows = new ArrayList<>(result.getRows());
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get(x))));
        Map<String, List<Object>> series = new LinkedHashMap<>();
        for (int i : result.numericColumns()) {
            if (i != x) {
                series.put(result.getColumns().get(i), column(rows, i));
            }
        }
        List<String> yNames = new ArrayList<>(series.keySet());
        String xName = result.getColumns().get(x);
        List<Object> labels = new ArrayList<>(column(rows, x).stream().map(ChartProduct::label).toList());
        return new ChartSpec("line", title(yNames, xName), xName, yNames.size() == 1 ? yNames.get(0) : "value",
                labels, series, result.getColumns(), result.getRows());
    }
}
