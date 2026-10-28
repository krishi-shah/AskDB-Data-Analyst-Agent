package ca.yorku.askdb.presentation.cli;

import ca.yorku.askdb.agent.AgentAnswer;
import ca.yorku.askdb.agent.AnswerStatus;
import ca.yorku.askdb.agent.ClarificationRequest;
import ca.yorku.askdb.agent.ConversationMemory;
import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.app.SavedState;
import ca.yorku.askdb.app.Session;
import ca.yorku.askdb.app.UsageTracker;
import ca.yorku.askdb.charts.ChartProduct;
import ca.yorku.askdb.charts.ChartRenderer;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.config.Settings;
import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.DatasetException;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import ca.yorku.askdb.llm.LLMException;
import ca.yorku.askdb.presentation.gui.MainWindow;
import ca.yorku.askdb.storage.HistoryEntry;
import ca.yorku.askdb.storage.SavedQuestion;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** The askdb command line. Every command goes through AskDBFacade. */
@Command(name = "askdb", mixinStandardHelpOptions = true, version = "AskDB 2.0 (Stage 2)",
        description = "AskDB: ask questions about a SQLite or CSV dataset in plain English.")
public class CliApp implements Runnable {
    private final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private AskDBFacade facade;

    @Spec
    CommandSpec spec;

    public CliApp() {
    }

    /** Used by tests to drive the CLI with a facade that has a mock model. */
    public CliApp(AskDBFacade facade) {
        this.facade = facade;
    }

    @Override
    public void run() {
        spec.commandLine().usage(out);
    }

    private AskDBFacade facade(boolean trace) {
        if (facade == null) {
            facade = new AskDBFacade(Settings.load());
            SavedState.load(facade.getSettings()).applySettings(facade);
        }
        facade.subscribe(new ConsoleProgressPrinter(out, trace));
        return facade;
    }

    private AskDBFacade facade() {
        return facade(false);
    }

    private SavedState state() {
        return SavedState.load(facade.getSettings());
    }

    private void restore(boolean keepMemory) {
        SavedState state = state();
        if (state.dataset.isEmpty()) {
            throw new DatasetException("No dataset is loaded. Run: askdb import <file>");
        }
        if (facade.getSession() == null) {
            facade.importDataset(state.dataset.stream().map(Path::of).toList(), false);
        }
        if (keepMemory) {
            ConversationMemory memory = facade.requireSession().getMemory();
            memory.clear();
            state.memory.forEach(memory::addTurn);
        }
    }

    private void saveMemory() {
        Session session = facade.getSession();
        if (session != null) {
            SavedState state = state();
            state.memory = new ArrayList<>(session.getMemory().getTurns());
            state.save(facade.getSettings());
        }
    }

    // Commands

    @Command(name = "import", description = "Load a dataset read-only, describe it, and show its schema.")
    void importCmd(@Parameters(arity = "1..*", paramLabel = "FILE",
                           description = "A .db/.sqlite file or one or more .csv files") List<Path> paths,
                   @Option(names = "--describe", negatable = true, defaultValue = "true", fallbackValue = "true",
                           description = "Ask the model for column descriptions (default: on)") boolean describe) {
        facade();
        if (describe) {
            out.println(dim("Reading the schema and asking the model for descriptions..."));
        }
        SchemaInfo schema = facade.importDataset(paths, describe);
        SavedState state = state();
        state.dataset = paths.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList();
        state.memory = new ArrayList<>();
        state.save(facade.getSettings());
        out.println(colour("green", "Loaded") + " " + schema.getSourceLabel() + ": " + schema.summary());
        for (String problem : facade.describeErrors()) {
            out.println(colour("yellow", "Description skipped for " + problem));
        }
        if (!facade.indexWarning().isEmpty()) {
            out.println(colour("yellow", facade.indexWarning()));
        }
        printSchema(schema, null);
    }

    @Command(name = "schema", description = "Show tables, columns, keys and descriptions.")
    void schemaCmd(@Parameters(arity = "0..1", paramLabel = "TABLE", description = "Show one table only")
                   String table) {
        facade();
        restore(false);
        SchemaInfo schema = facade.requireSession().getSchema();
        if (table != null && schema.table(table) == null) {
            throw new DatasetException("No table named '" + table + "'. Tables: "
                    + schema.getTables().stream().map(TableInfo::getName).collect(Collectors.joining(", ")));
        }
        printSchema(schema, table);
    }

    @Command(name = "describe", description = "Edit the description of a table or column.")
    void describeCmd(@Parameters(index = "0", paramLabel = "ENTRY", description = "table or table.column")
                     String entry,
                     @Parameters(index = "1", paramLabel = "TEXT", description = "new description") String text) {
        facade();
        restore(false);
        facade.updateDescription(entry, text);
        out.println(colour("green", "Saved description for " + entry + "."));
    }

    @Command(name = "ask", description = "Ask a question in plain English.")
    void askCmd(@Parameters(paramLabel = "QUESTION") String question,
                @Option(names = {"--continue", "-c"}, description = "Follow up on the previous question")
                boolean followUp,
                @Option(names = "--trace", description = "Show every model call and tool result") boolean trace,
                @Option(names = "--chart", arity = "0..1", fallbackValue = "auto", paramLabel = "TYPE",
                        description = "Save the chart as PNG in out/ (auto, bar, line, pie, scatter, table)")
                String chart,
                @Option(names = "--interactive", negatable = true, defaultValue = "true", fallbackValue = "true",
                        description = "Ask me when the question is ambiguous (default: on)") boolean interactive) {
        facade(trace);
        restore(followUp);
        boolean canAsk = interactive && System.console() != null;
        AgentAnswer answer = clarifyLoop(facade.ask(question, canAsk));
        printAnswer(answer, chart);
        saveMemory();
    }

    private AgentAnswer clarifyLoop(AgentAnswer answer) {
        while (answer.getStatus() == AnswerStatus.NEEDS_CLARIFICATION) {
            ClarificationRequest request = answer.getClarification();
            out.println(colour("magenta", request.question()));
            for (int i = 0; i < request.options().size(); i++) {
                out.println("  " + (i + 1) + ". " + request.options().get(i));
            }
            String raw = prompt("Choose a number or type your meaning");
            if (raw == null) {
                raw = "";
            }
            String choice = raw;
            if (raw.matches("\\d+")) {
                int n = Integer.parseInt(raw);
                if (n >= 1 && n <= request.options().size()) {
                    choice = request.options().get(n - 1);
                }
            }
            answer = facade.answerClarification(choice);
        }
        return answer;
    }

    @Command(name = "shell", description = "Interactive session with follow-up questions.")
    void shellCmd(@Option(names = "--trace", description = "Show every model call and tool result") boolean trace) {
        facade(trace);
        restore(false);
        out.println(Ansi.AUTO.string("@|bold AskDB shell|@") + ". Type a question, ':sql <query>', ':new' for a new "
                + "conversation, ':chart <type>', or ':quit'.");
        AgentAnswer last = null;
        while (true) {
            String line = prompt(colour("cyan", "askdb"));
            if (line == null) {
                break;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (List.of(":quit", ":q", "exit").contains(line)) {
                break;
            }
            try {
                if (line.equals(":new")) {
                    facade.resetConversation();
                    out.println(colour("green", "Started a new conversation."));
                } else if (line.startsWith(":sql ")) {
                    last = facade.runManualSql(line.substring(5), null);
                    printAnswer(last, null);
                } else if (line.startsWith(":chart")) {
                    rechart(last, line.substring(6).strip());
                } else {
                    last = clarifyLoop(facade.ask(line, true));
                    printAnswer(last, null);
                }
            } catch (DatasetException | LLMException | IllegalArgumentException e) {
                out.println(colour("red", "Error:") + " " + e.getMessage());
            }
        }
    }

    private void rechart(AgentAnswer answer, String chartType) {
        if (answer == null || answer.getResult() == null || answer.getResult().isEmpty()) {
            out.println(colour("yellow", "Nothing to chart yet."));
            return;
        }
        ChartProduct chart = facade.buildChart(answer.getResult(), chartType);
        if (!chart.name().equals(chartType)) {
            out.println(colour("yellow", "A " + chartType + " chart does not fit this result; using "
                    + chart.name() + "."));
        }
        saveChart(chart.render(answer.getResult()), answer.getHistoryId());
    }

    @Command(name = "sql", description = "Validate and run your own SQL without calling a model.")
    void sqlCmd(@Parameters(paramLabel = "SQL", description = "A single SELECT statement") String sql,
                @Option(names = "--chart", arity = "0..1", fallbackValue = "auto", paramLabel = "TYPE",
                        description = "Save the chart as PNG in out/") String chart) {
        facade();
        restore(false);
        printAnswer(facade.runManualSql(sql, null), chart);
    }

    @Command(name = "history", description = "List or search past questions.")
    void historyCmd(@Parameters(arity = "0..1", paramLabel = "TEXT", description = "Text to search for")
                    String search) {
        facade();
        List<HistoryEntry> entries = facade.getHistory(search == null ? "" : search);
        ConsoleTable grid = new ConsoleTable("History" + (search != null ? " matching '" + search + "'" : ""),
                List.of("#", "when", "status", "question", "rows")).maxWidth(70);
        for (HistoryEntry e : entries) {
            grid.addRow(List.of(String.valueOf(e.id()), e.createdAt(), e.status(),
                    (e.manual() ? "[manual] " : "") + e.question(), String.valueOf(e.rowCount())));
        }
        grid.print(out);
        List<SavedQuestion> saved = facade.getSavedQuestions();
        if (!saved.isEmpty() && search == null) {
            out.println(Ansi.AUTO.string("@|bold Saved questions:|@ ")
                    + saved.stream().map(SavedQuestion::name).collect(Collectors.joining(", ")));
        }
    }

    @Command(name = "save", description = "Save a history entry under a name.")
    void saveCmd(@Parameters(index = "0", paramLabel = "ID", description = "History number") long entryId,
                 @Parameters(index = "1", paramLabel = "NAME", description = "Name for the saved question")
                 String name) {
        facade();
        SavedQuestion item = facade.saveQuestion(entryId, name);
        out.println(colour("green", "Saved") + " '" + item.name() + "': " + item.question());
    }

    @Command(name = "rerun", description = "Run the stored SQL again without calling a model.")
    void rerunCmd(@Parameters(paramLabel = "TARGET", description = "History number or saved question name")
                  String target,
                  @Option(names = "--chart", arity = "0..1", fallbackValue = "auto", paramLabel = "TYPE",
                          description = "Save the chart as PNG in out/") String chart) {
        facade();
        restore(false);
        AgentAnswer answer = target.matches("\\d+") ? facade.rerunHistory(Long.parseLong(target))
                : facade.rerunSaved(target);
        printAnswer(answer, chart);
    }

    @Command(name = "config", description = "Show or change the routing policy and sample sharing.")
    void configCmd(@Option(names = "--policy", paramLabel = "NAME",
                           description = "local_only, cheap_first or strong_only") String policy,
                   @Option(names = "--share-samples", negatable = true,
                           description = "Send sample rows to the model") Boolean shareSamples) {
        facade();
        SavedState state = state();
        if (policy != null) {
            for (String warning : facade.setRoutingPolicy(policy)) {
                out.println(colour("yellow", warning));
            }
            state.policy = facade.getRoutingPolicy();
        }
        if (shareSamples != null) {
            facade.setShareSamples(shareSamples);
            state.shareSamples = shareSamples;
        }
        if (policy != null || shareSamples != null) {
            state.save(facade.getSettings());
        }
        out.println("Routing policy: " + Ansi.AUTO.string("@|bold " + facade.getRoutingPolicy() + "|@"));
        out.println("Share sample rows with the model: " + facade.getSettings().shareSamples);
        facade.providerStatus().forEach((tier, name) -> out.println("  " + tier + ": " + name));
    }

    @Command(name = "usage", description = "Show model calls, tokens, latency and cost.")
    void usageCmd(@Option(names = "--reset", description = "Clear recorded usage") boolean reset) {
        facade();
        if (reset) {
            facade.resetUsage();
        }
        UsageTracker.UsageSummary summary = facade.getUsageSummary();
        ConsoleTable grid = new ConsoleTable("Model usage (policy " + summary.policy() + ")",
                List.of("model", "calls", "failed", "fallbacks", "tokens in", "tokens out", "avg ms", "cost $"));
        for (UsageTracker.ModelUsage m : summary.models()) {
            grid.addRow(List.of(m.provider().isEmpty() ? m.model() : m.provider() + ":" + m.model(),
                    String.valueOf(m.calls()), String.valueOf(m.failures()), String.valueOf(m.fallbacks()),
                    String.valueOf(m.inputTokens()), String.valueOf(m.outputTokens()),
                    String.format(Locale.ROOT, "%.0f", m.avgLatencyMs()),
                    String.format(Locale.ROOT, "%.4f", m.costUsd())));
        }
        grid.print(out);
        out.println(String.format(Locale.ROOT, "Total: %d calls, %d tokens, $%.4f", summary.totalCalls(),
                summary.totalTokens(), summary.totalCost()));
    }

    @Command(name = "gui", description = "Open the desktop window.")
    void guiCmd() {
        MainWindow.main(new String[0]);
    }

    // Output

    void printSchema(SchemaInfo schema, String only) {
        for (TableInfo t : schema.getTables()) {
            if (only != null && !t.getName().equalsIgnoreCase(only)) {
                continue;
            }
            String title = t.getName() + " (" + t.getRowCount() + " rows)"
                    + (t.getDescription().isEmpty() ? "" : " - " + t.getDescription());
            ConsoleTable grid = new ConsoleTable(title, List.of("column", "type", "key", "description"));
            for (ColumnInfo c : t.getColumns()) {
                String key = c.isPrimaryKey() ? "PK" : (c.getReferences() == null ? "" : "FK " + c.getReferences());
                grid.addRow(List.of(c.getName(), c.getType(), key, c.getDescription()));
            }
            grid.print(out);
        }
    }

    void printResult(QueryResult result, int maxRows) {
        ConsoleTable grid = new ConsoleTable(null, result.getColumns());
        for (List<Object> row : result.getRows().subList(0, Math.min(maxRows, result.rowCount()))) {
            grid.addRow(row.stream().map(QueryResult::format).toList());
        }
        grid.print(out);
        String more = result.rowCount() > maxRows ? ", showing " + maxRows : "";
        String limit = result.isTruncated() ? " (row limit reached)" : "";
        out.println(dim(result.rowCount() + " rows" + more + limit + ", "
                + Math.round(result.getElapsedMs()) + " ms"));
    }

    void printAnswer(AgentAnswer answer, String chart) {
        String col = switch (answer.getStatus()) {
            case ANSWERED -> "green";
            case NO_DATA -> "yellow";
            default -> "red";
        };
        boolean showMessage = !answer.getMessage().isEmpty() && answer.getInsight() == null;
        out.println(colour(col, answer.getStatus().name()) + (showMessage ? " " + answer.getMessage() : ""));
        if (answer.getInsight() != null) {
            String badge = "";
            if (answer.getVerification() != null) {
                badge = answer.getVerification().verified() ? colour("green", "verified")
                        : colour("yellow", "unverified: " + String.join(", ", answer.getVerification().unverified()));
            }
            out.println(Ansi.AUTO.string("@|bold " + escape(answer.getInsight().text()) + "|@") + " " + badge);
        }
        if (!answer.getSql().isEmpty()) {
            out.println(colour("cyan", "SQL:") + " " + answer.getSql());
        }
        if (answer.getResult() != null && !answer.getResult().isEmpty()) {
            printResult(answer.getResult(), 25);
        }
        if (answer.getChart() != null) {
            out.println(dim("Chart: " + answer.getChart().chartType() + " - " + answer.getChart().title()));
            if (chart != null) {
                ChartSpec spec = answer.getChart();
                if (!chart.isEmpty() && !chart.equals("auto") && answer.getResult() != null) {
                    spec = facade.buildChart(answer.getResult(), chart).render(answer.getResult());
                }
                saveChart(spec, answer.getHistoryId());
            }
        }
        if (answer.getHistoryId() != null) {
            out.println(dim("History #" + answer.getHistoryId()));
        }
    }

    private void saveChart(ChartSpec spec, Long historyId) {
        Path path = Path.of("out", "chart_" + (historyId == null ? "latest" : historyId) + "_" + spec.chartType()
                + ".png");
        try {
            out.println(colour("green", "Chart saved:") + " " + ChartRenderer.savePng(spec, path));
        } catch (IOException e) {
            out.println(colour("red", "Could not save the chart: " + e.getMessage()));
        }
    }

    private String prompt(String label) {
        out.print(label + ": ");
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String colour(String style, String text) {
        return Ansi.AUTO.string("@|" + style + " " + escape(text) + "|@");
    }

    private static String dim(String text) {
        return colour("faint", text);
    }

    /** picocli markup uses @| and |@; keep user text from breaking it. */
    private static String escape(String text) {
        return text.replace("|@", "| @").replace("@|", "@ |");
    }

    public static int execute(CliApp app, String... args) {
        CommandLine cmd = new CommandLine(app);
        cmd.setExecutionExceptionHandler((ex, line, parsed) -> {
            if (ex instanceof DatasetException || ex instanceof LLMException || ex instanceof IllegalArgumentException
                    || ex instanceof IllegalStateException) {
                line.getErr().println(Ansi.AUTO.string("@|red Error:|@ ") + ex.getMessage());
                return 1;
            }
            throw ex;
        });
        return cmd.execute(args);
    }

    public static void main(String[] args) {
        int code = execute(new CliApp(), args);
        if (args.length == 0 || !args[0].equals("gui")) {
            System.exit(code);
        }
    }
}
