package ca.yorku.askdb.agent;

import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.data.QueryResult;
import java.util.List;

/** What the agent returns to the facade and, through it, to the GUI or CLI. */
public class AgentAnswer {
    private final AnswerStatus status;
    private String question = "";
    private String message = "";
    private String sql = "";
    private QueryResult result;
    private ChartSpec chart;
    private Insight insight;
    private VerificationReport verification;
    private ClarificationRequest clarification;
    private List<Observation> steps = List.of();
    private Long historyId;
    private boolean manual;

    public AgentAnswer(AnswerStatus status) {
        this.status = status;
    }

    public AnswerStatus getStatus() {
        return status;
    }

    public String getQuestion() {
        return question;
    }

    public AgentAnswer question(String value) {
        question = value == null ? "" : value;
        return this;
    }

    public String getMessage() {
        return message;
    }

    public AgentAnswer message(String value) {
        message = value == null ? "" : value;
        return this;
    }

    public String getSql() {
        return sql;
    }

    public AgentAnswer sql(String value) {
        sql = value == null ? "" : value;
        return this;
    }

    public QueryResult getResult() {
        return result;
    }

    public AgentAnswer result(QueryResult value) {
        result = value;
        return this;
    }

    public ChartSpec getChart() {
        return chart;
    }

    public AgentAnswer chart(ChartSpec value) {
        chart = value;
        return this;
    }

    public Insight getInsight() {
        return insight;
    }

    public AgentAnswer insight(Insight value) {
        insight = value;
        return this;
    }

    public VerificationReport getVerification() {
        return verification;
    }

    public AgentAnswer verification(VerificationReport value) {
        verification = value;
        return this;
    }

    public ClarificationRequest getClarification() {
        return clarification;
    }

    public AgentAnswer clarification(ClarificationRequest value) {
        clarification = value;
        return this;
    }

    public List<Observation> getSteps() {
        return steps;
    }

    public AgentAnswer steps(List<Observation> value) {
        steps = List.copyOf(value);
        return this;
    }

    public Long getHistoryId() {
        return historyId;
    }

    public AgentAnswer historyId(Long value) {
        historyId = value;
        return this;
    }

    public boolean isManual() {
        return manual;
    }

    public AgentAnswer manual(boolean value) {
        manual = value;
        return this;
    }
}
