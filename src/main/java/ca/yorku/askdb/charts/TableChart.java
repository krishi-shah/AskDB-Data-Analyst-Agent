package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;

public class TableChart extends ChartProduct {
    @Override
    public String name() {
        return "table";
    }

    @Override
    public boolean suits(QueryResult result) {
        return true;
    }

    @Override
    public ChartSpec render(QueryResult result) {
        return ChartSpec.table(result.rowCount() + " rows", result.getColumns(), result.getRows());
    }
}
