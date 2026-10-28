package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;
import java.util.List;
import java.util.Map;

public class ScatterChart extends ChartProduct {
    @Override
    public String name() {
        return "scatter";
    }

    @Override
    public boolean suits(QueryResult result) {
        return result.rowCount() >= 3 && result.numericColumns().size() >= 2;
    }

    @Override
    public ChartSpec render(QueryResult result) {
        List<Integer> numeric = result.numericColumns();
        int xi = numeric.get(0);
        int yi = numeric.get(1);
        String xName = result.getColumns().get(xi);
        String yName = result.getColumns().get(yi);
        return new ChartSpec("scatter", yName + " vs " + xName, xName, yName, column(result.getRows(), xi),
                Map.of(yName, column(result.getRows(), yi)), result.getColumns(), result.getRows());
    }
}
