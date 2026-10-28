package ca.yorku.askdb.app;

import ca.yorku.askdb.agent.AgentAnswer;
import ca.yorku.askdb.agent.AgentEventListener;
import ca.yorku.askdb.agent.AgentOrchestrator;
import ca.yorku.askdb.agent.AnswerStatus;
import ca.yorku.askdb.agent.ConversationMemory;
import ca.yorku.askdb.agent.InsightGenerator;
import ca.yorku.askdb.agent.InsightVerifier;
import ca.yorku.askdb.agent.Planner;
import ca.yorku.askdb.agent.PromptBuilder;
import ca.yorku.askdb.agent.ResponseParser;
import ca.yorku.askdb.agent.tools.AskUserTool;
import ca.yorku.askdb.agent.tools.ColumnValuesTool;
import ca.yorku.askdb.agent.tools.MakeChartTool;
import ca.yorku.askdb.agent.tools.RunQueryTool;
import ca.yorku.askdb.agent.tools.SampleRowsTool;
import ca.yorku.askdb.agent.tools.SearchSchemaTool;
import ca.yorku.askdb.agent.tools.ToolRegistry;
import ca.yorku.askdb.charts.ChartFactory;
import ca.yorku.askdb.charts.ChartProduct;
import ca.yorku.askdb.charts.ChartRecommender;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.config.Settings;
import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.DataSource;
import ca.yorku.askdb.data.DataSourceFactory;
import ca.yorku.askdb.data.DatasetException;
import ca.yorku.askdb.data.QueryExecutor;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.SchemaReader;
import ca.yorku.askdb.data.TableInfo;
import ca.yorku.askdb.llm.LLMException;
import ca.yorku.askdb.llm.LLMProvider;
import ca.yorku.askdb.llm.LLMProviderFactory;
import ca.yorku.askdb.llm.ModelRouter;
import ca.yorku.askdb.llm.RoutingPolicy;
import ca.yorku.askdb.retrieval.EmbeddingProvider;
import ca.yorku.askdb.retrieval.OllamaEmbeddingProvider;
import ca.yorku.askdb.retrieval.SchemaDescriber;
import ca.yorku.askdb.retrieval.SchemaIndex;
import ca.yorku.askdb.safety.SqlValidator;
import ca.yorku.askdb.safety.ValidationResult;
import ca.yorku.askdb.storage.AppDatabase;
import ca.yorku.askdb.storage.DescriptionStore;
import ca.yorku.askdb.storage.HistoryEntry;
import ca.yorku.askdb.storage.HistoryStore;
import ca.yorku.askdb.storage.SavedQuestion;
import ca.yorku.askdb.storage.SavedQuestionStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The single entry point used by the GUI and the CLI (Facade pattern). */
public class AskDBFacade implements AutoCloseable {
    private static final Map<String, String> TIER_PROVIDERS = Map.of("fast", "gemini", "strong", "groq");

    /** Thrown when an action needs a dataset and none is loaded. */
    public static class NoDatasetException extends DatasetException {
        public NoDatasetException() {
            super("No dataset is loaded. Import a SQLite or CSV file first.");
        }
    }

    private final Settings settings;
    private final Connection appConn;
    private final DataSourceFactory sources;
    private final SchemaReader reader = new SchemaReader();
    private final SqlValidator validator;
    private final QueryExecutor executor;
    private final ChartFactory charts = new ChartFactory();
    private final ChartRecommender recommender = new ChartRecommender(charts);
    private final HistoryStore history;
    private final SavedQuestionStore saved;
    private final DescriptionStore descriptions;
    private final UsageTracker usage;
    private final LLMProviderFactory providers;
    private final ModelRouter router;
    private final PromptBuilder builder;
    private final ResponseParser parser = new ResponseParser();
    private final SchemaDescriber describer;
    private final AgentOrchestrator orchestrator;
    private EmbeddingProvider embedder;
    private Session session;
    private String datasetKey = "";

    public AskDBFacade(Settings settings) {
        this(settings, null, null);
    }

    /** localProvider and embedder may be null; tests pass a mock provider here. */
    public AskDBFacade(Settings settings, LLMProvider localProvider, EmbeddingProvider embedder) {
        this.settings = settings == null ? Settings.load() : settings;
        Settings s = this.settings;
        s.ensureHome();
        sources = new DataSourceFactory(s.maxFileMb);
        validator = new SqlValidator(s.rowLimit);
        executor = new QueryExecutor(s.queryTimeoutS, s.rowLimit);
        appConn = AppDatabase.connect(s.appDb());
        history = new HistoryStore(appConn);
        saved = new SavedQuestionStore(appConn);
        descriptions = new DescriptionStore(appConn);
        usage = new UsageTracker(appConn);
        providers = new LLMProviderFactory(s);
        Map<String, LLMProvider> tiers = new LinkedHashMap<>();
        tiers.put("local", localProvider != null ? localProvider : providers.create("ollama"));
        router = new ModelRouter(tiers);
        this.embedder = embedder;
        builder = new PromptBuilder(s.shareSamples);
        describer = new SchemaDescriber(router, builder, parser);
        orchestrator = new AgentOrchestrator(router, new Planner(router, builder, parser, 8), buildTools(), builder,
                parser, new InsightGenerator(router, builder, parser), new InsightVerifier(),
                s.maxSteps, s.maxRepairs, s.maxClarifications);
        orchestrator.subscribe(usage);
        setRoutingPolicy(s.policy);
    }

    private ToolRegistry buildTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new SearchSchemaTool());
        registry.register(new SampleRowsTool(executor, () -> settings.shareSamples));
        registry.register(new ColumnValuesTool(executor, () -> settings.shareSamples));
        registry.register(new RunQueryTool(validator, executor));
        registry.register(new MakeChartTool(recommender));
        registry.register(new AskUserTool());
        return registry;
    }

    public Settings getSettings() {
        return settings;
    }

    public Session getSession() {
        return session;
    }

    public String getDatasetKey() {
        return datasetKey;
    }

    // Observers

    public void subscribe(AgentEventListener listener) {
        orchestrator.subscribe(listener);
    }

    public void unsubscribe(AgentEventListener listener) {
        orchestrator.unsubscribe(listener);
    }

    // F01, F02

    public SchemaInfo importDataset(List<Path> paths, boolean describe) {
        DataSource source = sources.create(paths);
        source.connect();
        SchemaInfo schema = reader.read(source);
        String key = paths.stream().map(p -> p.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining("|"));
        descriptions.apply(key, schema);
        List<String> missing = schema.getTables().stream().filter(t -> t.getDescription().isEmpty())
                .map(TableInfo::getName).toList();
        if (describe && !missing.isEmpty()) {
            describer.describe(schema, missing);
            descriptions.saveAll(key, schema);
        }
        if (embedder == null) {
            embedder = OllamaEmbeddingProvider.defaultFor(settings.ollamaUrl);
        }
        SchemaIndex index = new SchemaIndex(embedder);
        index.build(schema);
        if (session != null) {
            session.close();
        }
        datasetKey = key;
        session = new Session(source, schema, List.of(key.split("\\|")), index,
                new ConversationMemory(settings.memoryTurns));
        return schema;
    }

    public List<String> describeErrors() {
        return describer.getErrors();
    }

    public String indexWarning() {
        return session == null || session.getIndex() == null ? "" : session.getIndex().getWarning();
    }

    public Session requireSession() {
        if (session == null) {
            throw new NoDatasetException();
        }
        return session;
    }

    /** entryId is "table" or "table.column". */
    public void updateDescription(String entryId, String text) {
        Session s = requireSession();
        int dot = entryId.indexOf('.');
        String tableName = dot < 0 ? entryId : entryId.substring(0, dot);
        String columnName = dot < 0 ? "" : entryId.substring(dot + 1);
        TableInfo table = s.getSchema().table(tableName);
        if (table == null) {
            throw new DatasetException("No table named '" + tableName + "'.");
        }
        if (!columnName.isEmpty()) {
            ColumnInfo column = table.column(columnName);
            if (column == null) {
                throw new DatasetException("Table " + table.getName() + " has no column '" + columnName + "'.");
            }
            column.setDescription(text);
            String id = table.getName() + "." + column.getName();
            descriptions.saveEdit(datasetKey, id, text);
            if (s.getIndex() != null) {
                s.getIndex().updateEntry(id, SchemaIndex.columnText(table, column));
            }
        } else {
            table.setDescription(text);
            descriptions.saveEdit(datasetKey, table.getName(), text);
            if (s.getIndex() != null) {
                s.getIndex().build(s.getSchema());
            }
        }
    }

    // F03, F04, F06, F09, F10

    public AgentAnswer ask(String question, boolean interactive) {
        return record(orchestrator.run(question, requireSession(), interactive));
    }

    public AgentAnswer answerClarification(String choice) {
        return record(orchestrator.resume(choice, requireSession()));
    }

    public boolean hasPendingClarification() {
        return session != null && session.hasPendingClarification();
    }

    public void resetConversation() {
        Session s = requireSession();
        s.getMemory().clear();
        s.setPendingState(null);
    }

    // F05, F07, F08

    public AgentAnswer runManualSql(String sql, String question) {
        Session s = requireSession();
        String label = question == null || question.isEmpty() ? sql : question;
        ValidationResult check = validator.validate(sql);
        if (!check.ok()) {
            return record(new AgentAnswer(AnswerStatus.REFUSED).question(label).sql(sql).manual(true)
                    .message("Query blocked by " + check.rule() + ": " + check.reason()));
        }
        QueryResult raw;
        try {
            raw = executor.run(check.sql(), s.getDataSource());
        } catch (DatasetException.QueryFailed e) {
            return record(new AgentAnswer(AnswerStatus.FAILED).question(label).sql(sql).manual(true)
                    .message(e.getMessage()));
        }
        String clean = sql.strip().replaceAll(";+$", "");
        QueryResult result = new QueryResult(raw.getColumns(), raw.getRows(), raw.isTruncated(), raw.getElapsedMs(),
                clean);
        s.setLastResult(result);
        if (result.isEmpty()) {
            return record(new AgentAnswer(AnswerStatus.NO_DATA).question(label).sql(clean).result(result)
                    .manual(true).message("The query returned no rows."));
        }
        ChartSpec chart = recommender.recommend(result, "").render(result);
        return record(new AgentAnswer(AnswerStatus.ANSWERED).question(label).sql(clean).result(result)
                .chart(chart).manual(true));
    }

    public ChartProduct buildChart(QueryResult result, String chartType) {
        return charts.create(result, chartType);
    }

    public List<String> chartTypesFor(QueryResult result) {
        return charts.fits(result);
    }

    // F11

    public List<HistoryEntry> getHistory(String search) {
        return search == null || search.isEmpty() ? history.recent(50) : history.search(search, 50);
    }

    public SavedQuestion saveQuestion(long entryId, String name) {
        HistoryEntry entry = history.get(entryId);
        if (entry == null) {
            throw new DatasetException("No history entry #" + entryId + ".");
        }
        if (entry.sql().isEmpty()) {
            throw new DatasetException("History entry #" + entryId + " has no SQL to save.");
        }
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new DatasetException("Give the saved question a name.");
        }
        SavedQuestion item = new SavedQuestion(clean, entry.question(), entry.sql(), entry.dataset(), "");
        saved.save(item);
        return item;
    }

    public List<SavedQuestion> getSavedQuestions() {
        return saved.list();
    }

    public AgentAnswer rerunHistory(long entryId) {
        HistoryEntry entry = history.get(entryId);
        if (entry == null) {
            throw new DatasetException("No history entry #" + entryId + ".");
        }
        if (entry.sql().isEmpty()) {
            throw new DatasetException("History entry #" + entryId
                    + " has no SQL to rerun. Ask the question again instead.");
        }
        return runManualSql(entry.sql(), entry.question());
    }

    public AgentAnswer rerunSaved(String name) {
        SavedQuestion item = saved.get(name);
        if (item == null) {
            throw new DatasetException("No saved question named '" + name + "'.");
        }
        return runManualSql(item.sql(), item.question());
    }

    private AgentAnswer record(AgentAnswer answer) {
        if (answer.getStatus() == AnswerStatus.NEEDS_CLARIFICATION) {
            return answer;
        }
        String summary = answer.getInsight() != null ? answer.getInsight().text() : answer.getMessage();
        int rows = answer.getResult() == null ? 0 : answer.getResult().rowCount();
        long id = history.add(new HistoryEntry(null, "", datasetKey, answer.getQuestion(), answer.getSql(),
                answer.getStatus().name(), rows, summary, answer.isManual()));
        return answer.historyId(id);
    }

    // F12

    /** Installs the policy. Returns warnings for cloud tiers that have no key. */
    public List<String> setRoutingPolicy(String name) {
        RoutingPolicy policy = RoutingPolicy.byName(name);
        List<String> warnings = new ArrayList<>();
        if (policy.allowsCloud()) {
            for (String tier : List.of("fast", "strong")) {
                if (router.getProviders().containsKey(tier)) {
                    continue;
                }
                String providerName = TIER_PROVIDERS.get(tier);
                try {
                    router.addProvider(tier, providers.create(providerName));
                } catch (LLMException.Unavailable e) {
                    warnings.add(tier + " tier (" + providerName + ") unavailable: " + e.getMessage()
                            + " Using the local model.");
                }
            }
        }
        router.setPolicy(policy);
        settings.policy = policy.name();
        return warnings;
    }

    public String getRoutingPolicy() {
        return router.getPolicy().name();
    }

    public void setShareSamples(boolean share) {
        settings.shareSamples = share;
        builder.setShareSamples(share);
    }

    public Map<String, String> providerStatus() {
        Map<String, String> out = new LinkedHashMap<>();
        router.getProviders().forEach((tier, p) ->
                out.put(tier, p.modelName() + " (" + (p.isCloud() ? "cloud" : "local") + ")"));
        return out;
    }

    public UsageTracker.UsageSummary getUsageSummary() {
        return usage.summary(router.getPolicy().name());
    }

    public void resetUsage() {
        usage.reset();
    }

    @Override
    public void close() {
        if (session != null) {
            session.close();
            session = null;
        }
        try {
            appConn.close();
        } catch (java.sql.SQLException ignored) {
            // nothing useful to do while shutting down
        }
    }
}
