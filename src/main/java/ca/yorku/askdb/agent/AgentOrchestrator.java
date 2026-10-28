package ca.yorku.askdb.agent;

import ca.yorku.askdb.agent.tools.ToolRegistry;
import ca.yorku.askdb.agent.tools.ToolResult;
import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import ca.yorku.askdb.llm.ChatMessage;
import ca.yorku.askdb.llm.LLMException;
import ca.yorku.askdb.llm.LLMResponse;
import ca.yorku.askdb.llm.ModelRouter;
import ca.yorku.askdb.retrieval.SchemaEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** The bounded plan, act, observe loop. Subject of the Observer pattern. */
public class AgentOrchestrator {
    private final ModelRouter router;
    private final Planner planner;
    private final ToolRegistry tools;
    private final PromptBuilder builder;
    private final ResponseParser parser;
    private final InsightGenerator insights;
    private final InsightVerifier verifier;
    private final int maxSteps;
    private final int maxRepairs;
    private final int maxClarifications;
    private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private int step;

    /** Either the next action or an answer that ends the run; both null means "try another step". */
    private record Next(AgentAction action, AgentAnswer answer) {
    }

    public AgentOrchestrator(ModelRouter router, Planner planner, ToolRegistry tools, PromptBuilder builder,
                             ResponseParser parser, InsightGenerator insights, InsightVerifier verifier,
                             int maxSteps, int maxRepairs, int maxClarifications) {
        this.router = router;
        this.planner = planner;
        this.tools = tools;
        this.builder = builder;
        this.parser = parser;
        this.insights = insights;
        this.verifier = verifier;
        this.maxSteps = maxSteps;
        this.maxRepairs = maxRepairs;
        this.maxClarifications = maxClarifications;
        router.setListener(this::onModelCall);
    }

    // Observer subject

    public void subscribe(AgentEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(AgentEventListener listener) {
        listeners.remove(listener);
    }

    private void publish(String kind, String message, Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            data.put((String) keyValues[i], keyValues[i + 1]);
        }
        AgentEvent event = new AgentEvent(kind, message, step, data, System.currentTimeMillis());
        for (AgentEventListener l : listeners) {
            l.onEvent(event);
        }
    }

    private void onModelCall(String task, LLMResponse response, String note) {
        if (response == null) {
            publish("llm_error", note, "task", task);
            return;
        }
        publish("llm_call", task + " via " + response.provider() + ":" + response.model(), "task", task,
                "provider", response.provider(), "model", response.model(),
                "input_tokens", response.inputTokens(), "output_tokens", response.outputTokens(),
                "latency_ms", response.latencyMs(), "cost_usd", response.costUsd(),
                "fallback", !note.isEmpty(), "note", note);
    }

    // Entry points

    public AgentAnswer run(String question, Session session, boolean interactive) {
        step = 0;
        String context = session.getMemory() == null ? "" : session.getMemory().contextText();
        AgentState state = new AgentState(question, context, interactive);
        session.setPendingState(null);
        publish("start", "Question: " + question);
        try {
            state.plan = planner.createPlan(question, state.context, session.getIndex(), session.getSchema());
        } catch (LLMException e) {
            return failed(state, "No model could plan the question: " + e.getMessage());
        }
        QueryPlan plan = state.plan;
        state.schemaText = schemaContext(state, session);
        publish("plan", "Plan (" + plan.category() + "): " + plan.approach(), "category", plan.category(),
                "tables", plan.tables(), "chart_hint", plan.chartHint(), "ambiguous", plan.ambiguous());
        if (plan.category().equals("destructive")) {
            return refuse(plan.reason().isEmpty() ? "AskDB only reads data; it cannot change or delete it."
                    : plan.reason(), state);
        }
        if (plan.category().equals("unanswerable")) {
            String message = plan.reason().isEmpty()
                    ? "The loaded dataset does not contain data that answers this question." : plan.reason();
            publish("final", message, "status", AnswerStatus.NO_DATA.name());
            return new AgentAnswer(AnswerStatus.NO_DATA).question(question).message(message);
        }
        if (plan.needsClarification()) {
            if (interactive && state.clarificationRounds < maxClarifications) {
                return ask(state, session, plan.clarification());
            }
            state.clarified.add("The question is ambiguous; choose the most common meaning and state it.");
        }
        return loop(state, session);
    }

    /** Small schemas are sent whole; large ones only the planned and retrieved tables. */
    private static String schemaContext(AgentState state, Session session) {
        SchemaInfo schema = session.getSchema();
        if (schema.getTables().size() <= 8) {
            return schema.toText();
        }
        Set<String> planned = state.plan.tables().stream().map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        Set<String> names = new TreeSet<>();
        for (TableInfo t : schema.getTables()) {
            if (planned.contains(t.getName().toLowerCase(Locale.ROOT))) {
                names.add(t.getName());
            }
        }
        if (session.getIndex() != null) {
            session.getIndex().search(state.question, 10).stream().map(SchemaEntry::getTable).forEach(names::add);
        }
        return names.isEmpty() ? schema.toText() : schema.toText(names);
    }

    public AgentAnswer resume(String choice, Session session) {
        AgentState state = session.getPendingState();
        if (state == null || state.pendingClarification == null) {
            return new AgentAnswer(AnswerStatus.FAILED).message("There is no question waiting for clarification.");
        }
        ClarificationRequest request = state.pendingClarification;
        state.clarified.add(request.question() + " -> " + choice);
        state.pendingClarification = null;
        session.setPendingState(null);
        step = state.stepCount;
        publish("clarified", "Analyst chose: " + choice, "choice", choice);
        return loop(state, session);
    }

    public AgentAnswer refuse(String reason, AgentState state) {
        publish("refused", reason, "status", AnswerStatus.REFUSED.name());
        return new AgentAnswer(AnswerStatus.REFUSED).question(state == null ? "" : state.question).message(reason);
    }

    // Loop

    private AgentAnswer loop(AgentState state, Session session) {
        while (state.stepCount < maxSteps) {
            state.stepCount++;
            step = state.stepCount;
            Next next = nextAction(state);
            if (next.answer() != null) {
                return next.answer();
            }
            AgentAction action = next.action();
            if (action == null) {
                continue;
            }
            if (action.isFinal()) {
                acceptEmptyResult(state);
                if (state.successfulQuery == null) {
                    observe(state, action, false, "You must run a successful run_query before answering.");
                    continue;
                }
                return finalize(session, state, action);
            }
            AgentAnswer answer = act(state, session, action);
            if (answer != null) {
                return answer;
            }
        }
        acceptEmptyResult(state);
        if (state.successfulQuery != null) {
            return finalize(session, state, AgentAction.fin("", ""));
        }
        return failed(state, "The agent stopped after " + maxSteps + " steps without a working query.");
    }

    private Next nextAction(AgentState state) {
        boolean repairing = !state.lastError.isEmpty();
        List<Map<String, Object>> specs = tools.specs(excludedTools(state));
        List<ChatMessage> messages = repairing
                ? builder.buildRepairPrompt(state, state.lastError, specs)
                : builder.buildStepPrompt(state, specs);
        LLMResponse response;
        try {
            response = router.complete(messages, repairing ? "repair" : "step", Math.max(state.repairCount, 1), 1024);
        } catch (LLMException e) {
            return new Next(null, failed(state, "No model could answer: " + e.getMessage()));
        }
        AgentAction action;
        try {
            action = parser.parseAction(response.text());
        } catch (ParseException e) {
            publish("parse_error", e.getMessage());
            observe(state, AgentAction.invalid(), false, "Your reply was not usable: " + e.getMessage());
            return new Next(null, null);
        }
        publish("action", action.thought().isEmpty() ? "Action: " + action.kind() + " " + action.tool()
                : action.thought(), "tool", action.tool(), "args", action.args(), "final", action.isFinal());
        return new Next(action, null);
    }

    private List<String> excludedTools(AgentState state) {
        if (!state.interactive || state.clarificationRounds >= maxClarifications) {
            return List.of("ask_user", "make_chart");
        }
        return List.of("make_chart");
    }

    private AgentAnswer act(AgentState state, Session session, AgentAction action) {
        if (action.tool().equals("ask_user") && excludedTools(state).contains("ask_user")) {
            observe(state, action, false, "Do not ask again. Choose the most common meaning and state it.");
            return null;
        }
        if (action.tool().equals("run_query")) {
            String sql = normalizeSql(String.valueOf(action.args().getOrDefault("sql", "")));
            if (state.seenSql.contains(sql)) {
                return failed(state, "The agent repeated SQL that had already failed, so it stopped. "
                        + "Last error: " + state.lastError);
            }
            if (state.successfulQuery != null && sql.equals(normalizeSql(state.successfulSql))) {
                return finalize(session, state, AgentAction.fin("", ""));
            }
        }
        if (!state.observations.isEmpty()) {
            AgentAction previous = state.observations.get(state.observations.size() - 1).action();
            if (previous.tool().equals(action.tool()) && previous.args().equals(action.args())) {
                observe(state, action, false, "You already called " + action.tool()
                        + " with these arguments; its result is above. Take a different step.");
                return null;
            }
        }
        publish("tool_call", action.tool() + "(" + action.args() + ")", "tool", action.tool(), "args", action.args());
        ToolResult result = tools.execute(action.tool(), action.args(), session);
        String preview = result.content().length() > 500 ? result.content().substring(0, 500) : result.content();
        publish("tool_result", preview, "tool", action.tool(), "ok", result.ok());
        observe(state, action, result.ok(), result.content());
        if (action.tool().equals("ask_user") && result.ok()) {
            return ask(state, session, (ClarificationRequest) result.data());
        }
        if (action.tool().equals("run_query")) {
            return afterQuery(state, action, result);
        }
        return null;
    }

    private AgentAnswer afterQuery(AgentState state, AgentAction action, ToolResult result) {
        state.lastSql = String.valueOf(action.args().getOrDefault("sql", ""));
        if (result.ok()) {
            QueryResult data = (QueryResult) result.data();
            if (!data.isEmpty() || state.emptyResults >= 1) {
                state.successfulQuery = data;
                state.successfulSql = data.getSql();
                state.lastError = "";
                return null;
            }
            state.emptyResults++;
            state.emptyQuery = data;
            return handleFailure(state, "The query returned 0 rows. If the question expects rows, check the filter "
                    + "values with column_values and fix the query; otherwise answer that there are none.", false);
        }
        return handleFailure(state, result.error(), true);
    }

    /** A query that ran but found no rows is a valid answer once the model concludes there are none. */
    private static void acceptEmptyResult(AgentState state) {
        if (state.successfulQuery == null && state.emptyQuery != null) {
            state.successfulQuery = state.emptyQuery;
            state.successfulSql = state.emptyQuery.getSql();
            state.lastError = "";
        }
    }

    public AgentAnswer handleFailure(AgentState state, String error, boolean remember) {
        if (remember) {
            state.seenSql.add(normalizeSql(state.lastSql));
        }
        state.repairCount++;
        state.lastError = error;
        if (state.repairCount > maxRepairs) {
            return failed(state, "The query still failed after " + maxRepairs + " repair attempts. Last error: " + error);
        }
        publish("repair", "Repair attempt " + state.repairCount + ": " + error, "attempt", state.repairCount);
        return null;
    }

    private AgentAnswer ask(AgentState state, Session session, ClarificationRequest request) {
        state.clarificationRounds++;
        state.pendingClarification = request;
        session.setPendingState(state);
        publish("clarification", request.question(), "options", request.options());
        return new AgentAnswer(AnswerStatus.NEEDS_CLARIFICATION).question(state.question)
                .message(request.question()).clarification(request).steps(state.observations);
    }

    private static void observe(AgentState state, AgentAction action, boolean ok, String content) {
        state.observations.add(new Observation(action, ok, content));
    }

    private AgentAnswer failed(AgentState state, String message) {
        publish("final", message, "status", AnswerStatus.FAILED.name());
        return new AgentAnswer(AnswerStatus.FAILED).question(state.question).message(message)
                .sql(state.lastSql).steps(state.observations);
    }

    // Finish

    public AgentAnswer finalize(Session session, AgentState state, AgentAction action) {
        QueryResult result = state.successfulQuery;
        session.setLastResult(result);
        if (result.isEmpty()) {
            publish("final", "The query returned no rows.", "status", AnswerStatus.NO_DATA.name());
            AgentAnswer answer = new AgentAnswer(AnswerStatus.NO_DATA).question(state.question)
                    .sql(state.successfulSql).result(result).message("No rows match this question.")
                    .steps(state.observations);
            remember(session, state, answer);
            return answer;
        }
        String hint = state.plan == null ? "" : state.plan.chartHint();
        ToolResult chart = tools.execute("make_chart", new LinkedHashMap<>(Map.of("chart_type", hint)), session);
        publish("chart", chart.content());
        Insight insight = insights.generate(state.question, result, "");
        VerificationReport report = verifier.verify(insight, result, state.question);
        if (!report.verified()) {
            publish("verify", "Unverified numbers " + report.unverified() + "; regenerating once.");
            insight = insights.generate(state.question, result, report.feedback());
            report = verifier.verify(insight, result, state.question);
        }
        publish("verify", report.verified() ? "All numbers verified."
                : "Still unverified: " + String.join(", ", report.unverified()), "verified", report.verified());
        AgentAnswer answer = new AgentAnswer(AnswerStatus.ANSWERED).question(state.question)
                .sql(state.successfulSql).result(result).chart(chart.ok() ? (ChartSpec) chart.data() : null)
                .insight(insight).verification(report).message(action.answer()).steps(state.observations);
        remember(session, state, answer);
        publish("final", insight.text(), "status", AnswerStatus.ANSWERED.name());
        return answer;
    }

    private static void remember(Session session, AgentState state, AgentAnswer answer) {
        if (session.getMemory() == null) {
            return;
        }
        String summary = answer.getResult() == null ? "" : answer.getResult().previewText(5);
        String text = answer.getInsight() != null ? answer.getInsight().text() : answer.getMessage();
        session.getMemory().addTurn(new ConversationMemory.Turn(state.question, answer.getSql(), summary, text));
    }

    static String normalizeSql(String sql) {
        return String.join(" ", sql.strip().replaceAll(";+$", "").strip().split("\\s+")).toLowerCase(Locale.ROOT);
    }
}
