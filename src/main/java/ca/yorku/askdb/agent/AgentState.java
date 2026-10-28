package ca.yorku.askdb.agent;

import ca.yorku.askdb.data.QueryResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Working state of one question while the agent loop runs; kept in Session while a clarification is pending. */
public class AgentState {
    public final String question;
    public final String context;
    public final boolean interactive;
    public QueryPlan plan;
    public String schemaText = "";
    public final List<Observation> observations = new ArrayList<>();
    public int stepCount;
    public int repairCount;
    public final Set<String> seenSql = new HashSet<>();
    public String lastSql = "";
    public String lastError = "";
    public QueryResult successfulQuery;
    public String successfulSql = "";
    public int clarificationRounds;
    public final List<String> clarified = new ArrayList<>();
    public ClarificationRequest pendingClarification;
    public int emptyResults;
    /** Last query that ran without error but returned no rows; lets "there are none" end as NO_DATA. */
    public QueryResult emptyQuery;

    public AgentState(String question, String context, boolean interactive) {
        this.question = question;
        this.context = context == null ? "" : context;
        this.interactive = interactive;
    }
}
