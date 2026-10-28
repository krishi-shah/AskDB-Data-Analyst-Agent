package ca.yorku.askdb.charts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.yorku.askdb.data.QueryResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartFactoryTest {
    private final ChartFactory factory = new ChartFactory();

    private static QueryResult result(List<String> columns, List<List<Object>> rows) {
        return new QueryResult(columns, rows, false, 0, "");
    }

    @Test
    void timeSeriesGivesLineChart() {
        QueryResult r = result(List.of("month", "revenue"),
                List.of(List.of("2025-01", 10.0), List.of("2025-02", 12.0), List.of("2025-03", 9.0)));
        assertEquals("line", factory.create(r, "").name());
    }

    @Test
    void categoriesGiveBarChart() {
        QueryResult r = result(List.of("region", "revenue"), List.of(List.of("North", 5.0), List.of("South", 7.0)));
        assertEquals("bar", factory.create(r, "").name());
    }

    @Test
    void hintIsUsedWhenItFits() {
        QueryResult r = result(List.of("region", "revenue"), List.of(List.of("North", 5.0), List.of("South", 7.0)));
        assertEquals("pie", factory.create(r, "pie").name());
    }

    @Test
    void hintThatDoesNotFitFallsBack() {
        QueryResult r = result(List.of("region", "revenue"), List.of(List.of("North", -5.0), List.of("South", 7.0)));
        assertEquals("bar", factory.create(r, "pie").name());
    }

    @Test
    void twoNumericColumnsGiveScatter() {
        QueryResult r = result(List.of("price", "quantity"),
                List.of(List.of(1.0, 3L), List.of(2.0, 5L), List.of(3.0, 4L)));
        assertEquals("scatter", factory.create(r, "").name());
    }

    @Test
    void textOnlyResultGivesTable() {
        QueryResult r = result(List.of("name", "email"), List.of(List.of("Ava", "a@example.com")));
        assertEquals("table", factory.create(r, "").name());
    }
}
