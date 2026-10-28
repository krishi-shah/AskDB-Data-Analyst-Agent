package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.charts.ChartRecommender;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.data.QueryResult;
import java.util.Map;

public class MakeChartTool implements Tool {
    private final ChartRecommender recommender;

    public MakeChartTool(ChartRecommender recommender) {
        this.recommender = recommender;
    }

    @Override
    public String name() {
        return "make_chart";
    }

    @Override
    public String description() {
        return "Build a chart from the latest successful query result.";
    }

    @Override
    public Map<String, Param> parameters() {
        return Map.of("chart_type", new Param("string", false,
                "bar, line, pie, scatter or table; chosen automatically if omitted"));
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Session session) {
        QueryResult result = session.getLastResult();
        if (result == null || result.isEmpty()) {
            return ToolResult.failure("There is no query result to chart yet.");
        }
        Object hint = args.get("chart_type");
        ChartSpec spec = recommender.recommend(result, hint == null ? "" : hint.toString()).render(result);
        return ToolResult.success("Built a " + spec.chartType() + " chart: " + spec.title(), spec);
    }
}
