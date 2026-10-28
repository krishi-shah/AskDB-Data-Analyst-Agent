package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PieChart extends ChartProduct {
    @Override
    public String name() {
        return "pie";
    }

    @Override
    public boolean suits(QueryResult result) {
        List<Integer> numeric = result.numericColumns();
        if (result.rowCount() < 2 || result.rowCount() > 8 || labelColumns(result).isEmpty() || numeric.isEmpty()) {
            return false;
        }
        return result.columnValues(numeric.get(0)).stream()
                .allMatch(v -> v instanceof Number n && n.doubleValue() >= 0);
    }

    @Override
    public ChartSpec render(QueryResult result) {
        int x = labelColumns(result).get(0);
        int y = result.numericColumns().get(0);
        String xName = result.getColumns().get(x);
        String yName = result.getColumns().get(y);
        List<Object> labels = new ArrayList<>(column(result.getRows(), x).stream().map(ChartProduct::label).toList());
        return new ChartSpec("pie", "Share of " + yName + " by " + xName, xName, yName, labels,
                Map.of(yName, column(result.getRows(), y)), result.getColumns(), result.getRows());
    }
}
