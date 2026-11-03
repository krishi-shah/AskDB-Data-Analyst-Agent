package ca.yorku.askdb.agent;

import java.util.List;

/** category is "query", "destructive", or "unanswerable". sql is the planner's first query, or empty. */
public record QueryPlan(String category, String approach, List<String> tables, String chartHint,
                        boolean ambiguous, ClarificationRequest clarification, String reason, String sql) {

    public static QueryPlan fallback() {
        return new QueryPlan("query", "Explore the schema and write a query.", List.of(), "", false, null, "", "");
    }

    public boolean needsClarification() {
        return ambiguous && clarification != null;
    }
}
