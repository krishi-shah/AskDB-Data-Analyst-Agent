package ca.yorku.askdb.agent;

import java.util.List;

/** category is "query", "destructive", or "unanswerable". */
public record QueryPlan(String category, String approach, List<String> tables, String chartHint,
                        boolean ambiguous, ClarificationRequest clarification, String reason) {

    public static QueryPlan fallback() {
        return new QueryPlan("query", "Explore the schema and write a query.", List.of(), "", false, null, "");
    }

    public boolean needsClarification() {
        return ambiguous && clarification != null;
    }
}
