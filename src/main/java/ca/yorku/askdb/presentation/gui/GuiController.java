package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.agent.AgentAnswer;
import ca.yorku.askdb.agent.AnswerStatus;
import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.app.SavedState;
import ca.yorku.askdb.charts.ChartProduct;
import ca.yorku.askdb.data.DatasetException;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.llm.LLMException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.SwingWorker;

/** Turns window actions into facade calls, running slow ones on a SwingWorker thread. */
public class GuiController {
    private final AskDBFacade facade;
    private final MainWindow window;
    private boolean busy;
    private AgentAnswer lastAnswer;

    public GuiController(AskDBFacade facade, MainWindow window) {
        this.facade = facade;
        this.window = window;
    }

    public AskDBFacade getFacade() {
        return facade;
    }

    public boolean isBusy() {
        return busy;
    }

    private <T> void start(Callable<T> job, Consumer<T> onDone, String busyText) {
        if (busy) {
            window.showError("AskDB is still working on the previous request.");
            return;
        }
        busy = true;
        window.setBusy(true, busyText);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return job.call();
            }

            @Override
            protected void done() {
                busy = false;
                window.setBusy(false, "");
                try {
                    onDone.accept(get());
                } catch (ExecutionException e) {
                    window.showError(message(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private static String message(Throwable error) {
        if (error instanceof DatasetException || error instanceof LLMException
                || error instanceof IllegalArgumentException) {
            return error.getMessage();
        }
        error.printStackTrace();
        return "Unexpected error: " + error;
    }

    // F01, F02

    public void onImportClicked(List<Path> paths, boolean describe) {
        start(() -> facade.importDataset(paths, describe), schema -> {
            rememberDataset();
            window.showSchema(schema);
            List<String> problems = new ArrayList<>(facade.describeErrors());
            if (!facade.indexWarning().isEmpty()) {
                problems.add(facade.indexWarning());
            }
            if (!problems.isEmpty()) {
                window.showError("Imported, with warnings:\n" + String.join("\n", problems));
            }
        }, "Importing and describing the dataset...");
    }

    public void onDescriptionEdited(String entryId, String text) {
        try {
            facade.updateDescription(entryId, text);
            window.showStatus("Description saved for " + entryId);
        } catch (DatasetException e) {
            window.showError(e.getMessage());
        }
    }

    // F03, F04, F10

    public void onAskClicked(String question) {
        String q = question.strip();
        if (q.isEmpty()) {
            return;
        }
        window.clearSteps();
        start(() -> facade.ask(q, true), this::showAnswer, "Thinking...");
    }

    public void onClarificationChosen(String choice) {
        String c = choice.strip();
        if (c.isEmpty()) {
            return;
        }
        start(() -> facade.answerClarification(c), this::showAnswer, "Continuing with your choice...");
    }

    public void onNewConversationClicked() {
        try {
            facade.resetConversation();
        } catch (DatasetException e) {
            window.showError(e.getMessage());
            return;
        }
        lastAnswer = null;
        window.clearAnswer();
        window.clearSteps();
    }

    private void showAnswer(AgentAnswer answer) {
        lastAnswer = answer;
        if (answer.getStatus() == AnswerStatus.NEEDS_CLARIFICATION && answer.getClarification() != null) {
            window.showClarification(answer.getClarification());
        } else {
            window.displayAnswer(answer);
        }
        onHistoryClicked("");
    }

    // F07

    public void onRunSqlClicked(String sql) {
        if (sql.isBlank()) {
            return;
        }
        start(() -> facade.runManualSql(sql, null), this::showAnswer, "Running SQL...");
    }

    // F08

    public void onChartTypeChanged(String chartType) {
        AgentAnswer answer = lastAnswer;
        if (answer == null || answer.getResult() == null || answer.getResult().isEmpty()) {
            return;
        }
        ChartProduct chart = facade.buildChart(answer.getResult(), chartType);
        if (!chart.name().equals(chartType)) {
            window.showStatus("A " + chartType + " chart does not fit this result; showing " + chart.name() + ".");
        }
        window.showChart(chart.render(answer.getResult()));
    }

    // F11

    public void onHistoryClicked(String search) {
        window.showHistory(facade.getHistory(search), facade.getSavedQuestions());
    }

    public void onSaveClicked(long entryId, String name) {
        try {
            facade.saveQuestion(entryId, name);
        } catch (DatasetException e) {
            window.showError(e.getMessage());
            return;
        }
        onHistoryClicked("");
    }

    public void onRerunClicked(long entryId) {
        start(() -> facade.rerunHistory(entryId), this::afterRerun, "Rerunning stored SQL...");
    }

    public void onSavedRunClicked(String name) {
        start(() -> facade.rerunSaved(name), this::afterRerun, "Running saved question...");
    }

    public void onAskAgainClicked(String question) {
        window.focusAsk(question);
        onAskClicked(question);
    }

    private void afterRerun(AgentAnswer answer) {
        window.focusAsk(answer.getQuestion());
        showAnswer(answer);
    }

    // F12

    public List<String> onPolicySelected(String name) {
        List<String> warnings = facade.setRoutingPolicy(name);
        SavedState state = SavedState.load(facade.getSettings());
        state.policy = facade.getRoutingPolicy();
        state.save(facade.getSettings());
        return warnings;
    }

    public void onShareSamplesChanged(boolean share) {
        facade.setShareSamples(share);
        SavedState state = SavedState.load(facade.getSettings());
        state.shareSamples = share;
        state.save(facade.getSettings());
    }

    public void onUsageClicked() {
        window.showUsage(facade.getUsageSummary());
    }

    // State shared with the CLI

    private void rememberDataset() {
        SavedState state = SavedState.load(facade.getSettings());
        state.dataset = new ArrayList<>(facade.requireSession().getPaths());
        state.memory = new ArrayList<>();
        state.save(facade.getSettings());
    }

    public void restoreLastSession() {
        SavedState state = SavedState.load(facade.getSettings());
        state.applySettings(facade);
        if (!state.dataset.isEmpty()) {
            List<Path> paths = state.dataset.stream().map(Path::of).toList();
            start(() -> facade.importDataset(paths, false), (SchemaInfo schema) -> window.showSchema(schema),
                    "Reopening the last dataset...");
        }
    }
}
