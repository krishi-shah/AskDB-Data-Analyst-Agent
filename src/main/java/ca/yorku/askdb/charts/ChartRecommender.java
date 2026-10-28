package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;

/** Asks ChartFactory for a chart; it does not choose the class itself. */
public class ChartRecommender {
    private final ChartFactory factory;

    public ChartRecommender(ChartFactory factory) {
        this.factory = factory;
    }

    public ChartProduct recommend(QueryResult result, String hint) {
        return factory.create(result, hint);
    }
}
