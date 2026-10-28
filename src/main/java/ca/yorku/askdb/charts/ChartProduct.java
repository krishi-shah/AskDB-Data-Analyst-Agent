package ca.yorku.askdb.charts;

import ca.yorku.askdb.data.QueryResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Product interface of the chart Factory. */
public abstract class ChartProduct {
    private static final Pattern DATE = Pattern.compile(
            "^\\d{4}(-\\d{2}){0,2}([ T].*)?$|^\\d{4}-?Q[1-4]$|^\\d{4}-W\\d{2}$");
    private static final List<String> TIME_WORDS = List.of(
            "date", "month", "year", "week", "day", "quarter", "period", "time");

    public abstract String name();

    public abstract boolean suits(QueryResult result);

    public abstract ChartSpec render(QueryResult result);

    protected static String title(List<String> y, String x) {
        return x.isEmpty() ? String.join(", ", y) : String.join(", ", y) + " by " + x;
    }

    protected static List<Integer> labelColumns(QueryResult result) {
        Set<Integer> numeric = new HashSet<>(result.numericColumns());
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < result.getColumns().size(); i++) {
            if (!numeric.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }

    protected static boolean isTimeColumn(QueryResult result, int index) {
        List<Object> values = result.columnValues(index).stream().filter(v -> v != null).toList();
        if (values.isEmpty()) {
            return false;
        }
        if (values.stream().allMatch(v -> v instanceof String s && DATE.matcher(s).matches())) {
            return true;
        }
        String name = result.getColumns().get(index).toLowerCase(Locale.ROOT);
        return TIME_WORDS.stream().anyMatch(name::contains) && new HashSet<>(values).size() == values.size();
    }

    protected static List<Object> column(List<List<Object>> rows, int index) {
        List<Object> out = new ArrayList<>(rows.size());
        for (List<Object> r : rows) {
            out.add(r.get(index));
        }
        return out;
    }

    protected static String label(Object v) {
        return v == null ? "NULL" : String.valueOf(v);
    }
}
