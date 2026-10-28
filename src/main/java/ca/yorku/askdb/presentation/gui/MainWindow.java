package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.agent.AgentAnswer;
import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.agent.AnswerStatus;
import ca.yorku.askdb.agent.ClarificationRequest;
import ca.yorku.askdb.app.AskDBFacade;
import ca.yorku.askdb.app.UsageTracker;
import ca.yorku.askdb.charts.ChartFactory;
import ca.yorku.askdb.charts.ChartRenderer;
import ca.yorku.askdb.charts.ChartSpec;
import ca.yorku.askdb.config.Settings;
import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.QueryResult;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import ca.yorku.askdb.storage.HistoryEntry;
import ca.yorku.askdb.storage.SavedQuestion;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;

/** The AskDB desktop window. It only displays data and forwards actions to GuiController. */
public class MainWindow extends JFrame {
    private static final Map<AnswerStatus, Color> STATUS_COLOURS = Map.of(
            AnswerStatus.ANSWERED, new Color(0x1b7f3b),
            AnswerStatus.NO_DATA, new Color(0x9a6700),
            AnswerStatus.NEEDS_CLARIFICATION, new Color(0x6f42c1),
            AnswerStatus.REFUSED, new Color(0xb42318),
            AnswerStatus.FAILED, new Color(0xb42318));
    private static final int TABLE_LIMIT = 1000;

    private final GuiController controller;
    private final TraceView trace = new TraceView();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel statusBar = new JLabel(" ");
    private final JLabel datasetLabel = new JLabel("No dataset loaded");
    private final JButton importButton = new JButton("Import data...");

    // Ask tab
    private final JTextField question = new JTextField();
    private final JButton askButton = new JButton("Ask");
    private final JPanel clarifyBox = new JPanel();
    private final JLabel clarifyLabel = new JLabel();
    private final JPanel clarifyButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JTextField clarifyOther = new JTextField();
    private final DefaultListModel<String> steps = new DefaultListModel<>();
    private final JList<String> stepList = new JList<>(steps);
    private final JTextArea sqlEditor = new JTextArea(6, 30);
    private final JButton runSqlButton = new JButton("Run SQL");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel badge = new JLabel(" ");
    private final JTextArea answerText = new JTextArea(3, 40);
    private final JComboBox<String> chartType = new JComboBox<>(ChartFactory.CHART_TYPES.toArray(new String[0]));
    private final JPanel chartHolder = new JPanel(new BorderLayout());
    private final JTable resultTable = readOnlyTable();
    private ChartSpec currentSpec;
    private boolean fillingChartType;

    // Schema tab
    private final List<String> schemaIds = new ArrayList<>();
    private boolean fillingSchema;
    private final DefaultTableModel schemaModel = new DefaultTableModel(
            new Object[] {"Table / column", "Type", "Key", "Description"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 3;
        }
    };
    private final JTable schemaTable = new JTable(schemaModel);

    // History tab
    private final JTextField historySearch = new JTextField();
    private final JTable historyTable = readOnlyTable();
    private final DefaultListModel<SavedQuestion> saved = new DefaultListModel<>();
    private final JList<SavedQuestion> savedList = new JList<>(saved);
    private List<HistoryEntry> historyEntries = List.of();

    // Usage and Trace tab
    private final JLabel usageLabel = new JLabel(" ");
    private final JTable usageTable = readOnlyTable();

    public MainWindow(AskDBFacade facade) {
        super("AskDB - Data Analyst Agent");
        controller = new GuiController(facade, this);
        facade.subscribe(trace);
        facade.subscribe(new StepListListener(this::addStep));

        tabs.addTab("Ask", buildAskTab());
        tabs.addTab("Schema", buildSchemaTab());
        tabs.addTab("History", buildHistoryTab());
        tabs.addTab("Usage and Trace", buildUsageTab());
        tabs.addChangeListener(e -> tabChanged(tabs.getSelectedIndex()));

        importButton.addActionListener(e -> chooseFiles());
        JButton settingsButton = new JButton("Settings");
        settingsButton.addActionListener(e -> new SettingsDialog(controller, this).setVisible(true));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(importButton, BorderLayout.WEST);
        top.add(datasetLabel, BorderLayout.CENTER);
        top.add(settingsButton, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        root.add(top, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        root.add(statusBar, BorderLayout.SOUTH);
        setContentPane(root);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 860);
        setLocationRelativeTo(null);
        showStatus("Import a SQLite database or CSV files to begin.");
    }

    public GuiController getController() {
        return controller;
    }

    public JTabbedPane getTabs() {
        return tabs;
    }

    // Ask tab

    private JComponent buildAskTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 6));
        tab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        question.setToolTipText("Ask a question, e.g. Which product category had the highest revenue in 2025?");
        question.addActionListener(e -> controller.onAskClicked(question.getText()));
        askButton.addActionListener(e -> controller.onAskClicked(question.getText()));
        JButton newButton = new JButton("New Conversation");
        newButton.addActionListener(e -> controller.onNewConversationClicked());
        JPanel askButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        askButtons.add(askButton);
        askButtons.add(newButton);
        JPanel askRow = new JPanel(new BorderLayout(6, 0));
        askRow.add(question, BorderLayout.CENTER);
        askRow.add(askButtons, BorderLayout.EAST);

        clarifyBox.setLayout(new BoxLayout(clarifyBox, BoxLayout.Y_AXIS));
        clarifyBox.setBorder(BorderFactory.createTitledBorder("The question has more than one meaning"));
        JButton otherButton = new JButton("Use this meaning");
        otherButton.addActionListener(e -> choose(clarifyOther.getText()));
        clarifyOther.addActionListener(e -> choose(clarifyOther.getText()));
        JPanel otherRow = new JPanel(new BorderLayout(6, 0));
        otherRow.add(new JLabel("Or type what you meant:"), BorderLayout.WEST);
        otherRow.add(clarifyOther, BorderLayout.CENTER);
        otherRow.add(otherButton, BorderLayout.EAST);
        clarifyLabel.setAlignmentX(LEFT_ALIGNMENT);
        clarifyButtons.setAlignmentX(LEFT_ALIGNMENT);
        otherRow.setAlignmentX(LEFT_ALIGNMENT);
        clarifyBox.add(clarifyLabel);
        clarifyBox.add(clarifyButtons);
        clarifyBox.add(otherRow);
        clarifyBox.setVisible(false);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(askRow);
        north.add(Box.createVerticalStrut(6));
        north.add(clarifyBox);
        tab.add(north, BorderLayout.NORTH);

        // Left: steps and SQL editor
        JPanel left = new JPanel(new BorderLayout(0, 4));
        stepList.setCellRenderer(new WrappingCellRenderer(330));
        JPanel stepsPanel = labelled("Agent steps", new JScrollPane(stepList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER));
        sqlEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sqlEditor.setLineWrap(true);
        sqlEditor.setWrapStyleWord(true);
        runSqlButton.addActionListener(e -> controller.onRunSqlClicked(sqlEditor.getText()));
        JPanel sqlPanel = labelled("SQL (edit and run without the model)", new JScrollPane(sqlEditor));
        sqlPanel.add(runSqlButton, BorderLayout.SOUTH);
        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stepsPanel, sqlPanel);
        leftSplit.setResizeWeight(0.6);
        left.add(leftSplit, BorderLayout.CENTER);

        // Right: status, answer, chart, table
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.add(statusLabel, BorderLayout.WEST);
        statusRow.add(badge, BorderLayout.EAST);
        answerText.setEditable(false);
        answerText.setLineWrap(true);
        answerText.setWrapStyleWord(true);
        answerText.setOpaque(false);
        answerText.setFont(UIManager.getFont("Label.font").deriveFont(16f));
        answerText.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        chartType.addActionListener(e -> {
            if (!fillingChartType) {
                controller.onChartTypeChanged((String) chartType.getSelectedItem());
            }
        });
        JButton saveChart = new JButton("Save Image...");
        saveChart.addActionListener(e -> saveChart());
        JPanel chartRow = new JPanel(new BorderLayout(6, 0));
        JPanel chartLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chartLeft.add(new JLabel("Chart"));
        chartLeft.add(chartType);
        chartRow.add(chartLeft, BorderLayout.WEST);
        chartRow.add(saveChart, BorderLayout.EAST);

        JPanel rightTop = new JPanel();
        rightTop.setLayout(new BoxLayout(rightTop, BoxLayout.Y_AXIS));
        for (JComponent c : List.of(statusRow, answerText, chartRow)) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            rightTop.add(c);
        }
        chartHolder.setPreferredSize(new Dimension(700, 380));
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartHolder, new JScrollPane(resultTable));
        rightSplit.setResizeWeight(0.6);
        JPanel right = new JPanel(new BorderLayout(0, 4));
        right.add(rightTop, BorderLayout.NORTH);
        right.add(rightSplit, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(420);
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    private void choose(String choice) {
        if (!choice.isBlank()) {
            clarifyBox.setVisible(false);
            controller.onClarificationChosen(choice);
        }
    }

    private void addStep(AgentEvent event) {
        String msg = event.message();
        steps.addElement(event.step() + ". " + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        stepList.ensureIndexIsVisible(steps.size() - 1);
    }

    public void clearSteps() {
        steps.clear();
    }

    public void clearAnswer() {
        statusLabel.setText(" ");
        badge.setText(" ");
        answerText.setText("");
        resultTable.setModel(new DefaultTableModel());
        clearChart();
        clarifyBox.setVisible(false);
        showStatus("Started a new conversation.");
    }

    public void focusAsk(String text) {
        tabs.setSelectedIndex(0);
        question.setText(text);
    }

    public void displayAnswer(AgentAnswer answer) {
        clarifyBox.setVisible(false);
        String label = titleCase(answer.getStatus().name()) + (answer.isManual() ? " (manual SQL)" : "");
        statusLabel.setText(label);
        statusLabel.setForeground(STATUS_COLOURS.getOrDefault(answer.getStatus(), Color.BLACK));
        answerText.setText(answer.getInsight() != null ? answer.getInsight().text() : answer.getMessage());
        if (answer.getVerification() != null) {
            if (answer.getVerification().verified()) {
                badge.setText("\u2714 Numbers verified");
                badge.setForeground(new Color(0x1b7f3b));
            } else {
                badge.setText("Unverified: " + String.join(", ", answer.getVerification().unverified()));
                badge.setForeground(new Color(0x9a6700));
            }
        } else {
            badge.setText(" ");
        }
        if (!answer.getSql().isEmpty()) {
            sqlEditor.setText(answer.getSql());
        }
        if (answer.getResult() != null) {
            fillTable(resultTable, answer.getResult().getColumns(), answer.getResult().getRows());
        } else {
            resultTable.setModel(new DefaultTableModel());
        }
        if (answer.getChart() != null) {
            showChart(answer.getChart());
        } else {
            clearChart();
        }
        showStatus(answer.getHistoryId() != null ? "History #" + answer.getHistoryId() : "");
    }

    public void showChart(ChartSpec spec) {
        currentSpec = spec;
        fillingChartType = true;
        chartType.setSelectedItem(spec.chartType());
        fillingChartType = false;
        chartHolder.removeAll();
        JFreeChart chart = ChartRenderer.toJFreeChart(spec);
        if (chart != null) {
            chartHolder.add(new ChartPanel(chart), BorderLayout.CENTER);
        } else {
            JLabel note = new JLabel(spec.title() + " - shown as a table below.", SwingConstants.CENTER);
            chartHolder.add(note, BorderLayout.CENTER);
        }
        chartHolder.revalidate();
        chartHolder.repaint();
    }

    private void clearChart() {
        currentSpec = null;
        chartHolder.removeAll();
        chartHolder.revalidate();
        chartHolder.repaint();
    }

    private void saveChart() {
        if (currentSpec == null) {
            showError("There is no chart to save yet.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("chart_" + currentSpec.chartType() + ".png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG image (*.png)", "png"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path path = chooser.getSelectedFile().toPath();
            if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                path = Path.of(path + ".png");
            }
            try {
                showStatus("Chart saved to " + ChartRenderer.savePng(currentSpec, path));
            } catch (IOException e) {
                showError("Could not save the chart: " + e.getMessage());
            }
        }
    }

    public void showClarification(ClarificationRequest request) {
        clearAnswer();
        showStatus("Choose what you meant to continue.");
        statusLabel.setText("Needs clarification");
        statusLabel.setForeground(STATUS_COLOURS.get(AnswerStatus.NEEDS_CLARIFICATION));
        clarifyLabel.setText("<html>" + escapeHtml(request.question()) + "</html>");
        clarifyButtons.removeAll();
        for (String option : request.options()) {
            JButton button = new JButton(option);
            button.addActionListener(e -> choose(option));
            clarifyButtons.add(button);
        }
        clarifyOther.setText("");
        clarifyBox.setVisible(true);
        clarifyBox.revalidate();
        clarifyBox.repaint();
    }

    // Schema tab

    private JComponent buildSchemaTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 6));
        tab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        tab.add(new JLabel("Double-click a description to edit it. Edits are saved and used by the agent's "
                + "schema search."), BorderLayout.NORTH);
        schemaTable.setRowHeight(22);
        schemaTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        schemaTable.getColumnModel().getColumn(3).setPreferredWidth(640);
        schemaTable.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus,
                                                                    int row, int col) {
                java.awt.Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                boolean isTable = row < schemaIds.size() && !schemaIds.get(row).contains(".");
                c.setFont(c.getFont().deriveFont(isTable ? Font.BOLD : Font.PLAIN));
                return c;
            }
        });
        schemaModel.addTableModelListener(e -> {
            int row = e.getFirstRow();
            if (fillingSchema || e.getColumn() != 3 || row < 0 || row >= schemaIds.size()) {
                return;
            }
            controller.onDescriptionEdited(schemaIds.get(row), String.valueOf(schemaModel.getValueAt(row, 3)));
        });
        tab.add(new JScrollPane(schemaTable), BorderLayout.CENTER);
        return tab;
    }

    public void showSchema(SchemaInfo schema) {
        fillingSchema = true;
        schemaModel.setRowCount(0);
        schemaIds.clear();
        for (TableInfo table : schema.getTables()) {
            schemaModel.addRow(new Object[] {table.getName() + " (" + table.getRowCount() + " rows)", "", "",
                    table.getDescription()});
            schemaIds.add(table.getName());
            for (ColumnInfo col : table.getColumns()) {
                String key = col.isPrimaryKey() ? "PK"
                        : (col.getReferences() == null ? "" : "FK " + col.getReferences());
                schemaModel.addRow(new Object[] {"    " + col.getName(), col.getType(), key, col.getDescription()});
                schemaIds.add(table.getName() + "." + col.getName());
            }
        }
        fillingSchema = false;
        datasetLabel.setText("Dataset: " + schema.getSourceLabel() + " (" + schema.summary() + ")");
        showStatus("Dataset loaded. Ask a question on the Ask tab.");
    }

    // History tab

    private JComponent buildHistoryTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 6));
        tab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        historySearch.setToolTipText("Search questions and SQL");
        historySearch.addActionListener(e -> controller.onHistoryClicked(historySearch.getText()));
        JButton search = new JButton("Search");
        search.addActionListener(e -> controller.onHistoryClicked(historySearch.getText()));
        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.add(new JLabel("Search questions and SQL:"), BorderLayout.WEST);
        searchRow.add(historySearch, BorderLayout.CENTER);
        searchRow.add(search, BorderLayout.EAST);
        tab.add(searchRow, BorderLayout.NORTH);

        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton rerun = new JButton("Rerun SQL");
        rerun.addActionListener(e -> {
            HistoryEntry entry = selectedEntry();
            if (entry != null) {
                controller.onRerunClicked(entry.id());
            }
        });
        JButton again = new JButton("Ask Again");
        again.addActionListener(e -> {
            HistoryEntry entry = selectedEntry();
            if (entry != null) {
                controller.onAskAgainClicked(entry.question());
            }
        });
        JButton save = new JButton("Save As...");
        save.addActionListener(e -> saveSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(rerun);
        buttons.add(again);
        buttons.add(save);
        JPanel historyPanel = new JPanel(new BorderLayout(0, 4));
        historyPanel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        historyPanel.add(buttons, BorderLayout.SOUTH);

        savedList.setCellRenderer((list, item, index, selected, focus) -> {
            JLabel l = new JLabel(item.name() + ": " + item.question());
            l.setOpaque(true);
            l.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            l.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            return l;
        });
        savedList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                SavedQuestion item = savedList.getSelectedValue();
                if (e.getClickCount() == 2 && item != null) {
                    controller.onSavedRunClicked(item.name());
                }
            }
        });
        JPanel savedPanel = labelled("Saved questions (double-click to run)", new JScrollPane(savedList));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, historyPanel, savedPanel);
        split.setResizeWeight(0.75);
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    public void showHistory(List<HistoryEntry> entries, List<SavedQuestion> savedQuestions) {
        historyEntries = entries;
        List<List<Object>> rows = new ArrayList<>();
        for (HistoryEntry e : entries) {
            rows.add(Arrays.asList(e.id(), e.createdAt(), e.status(),
                    (e.manual() ? "[manual] " : "") + e.question(), e.rowCount(), e.sql()));
        }
        fillTable(historyTable, List.of("#", "When", "Status", "Question", "Rows", "SQL"), rows);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(360);
        historyTable.getColumnModel().getColumn(5).setPreferredWidth(360);
        if (savedQuestions != null) {
            saved.clear();
            savedQuestions.forEach(saved::addElement);
        }
    }

    private HistoryEntry selectedEntry() {
        int row = historyTable.getSelectedRow();
        if (row < 0) {
            showError("Select a history row first.");
            return null;
        }
        return historyEntries.get(historyTable.convertRowIndexToModel(row));
    }

    private void saveSelected() {
        HistoryEntry entry = selectedEntry();
        if (entry == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Name:", "Save question", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            controller.onSaveClicked(entry.id(), name.strip());
        }
    }

    // Usage and Trace tab

    private JComponent buildUsageTab() {
        JPanel tab = new JPanel(new BorderLayout(0, 6));
        tab.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> controller.onUsageClicked());
        JButton clearTrace = new JButton("Clear Trace");
        clearTrace.addActionListener(e -> trace.clear());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(refresh);
        buttons.add(clearTrace);
        JPanel row = new JPanel(new BorderLayout());
        row.add(usageLabel, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);
        tab.add(row, BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(usageTable),
                labelled("Trace (every agent step and model call)", new JScrollPane(trace.getComponent())));
        split.setResizeWeight(0.35);
        tab.add(split, BorderLayout.CENTER);
        return tab;
    }

    public void showUsage(UsageTracker.UsageSummary summary) {
        List<List<Object>> rows = new ArrayList<>();
        for (UsageTracker.ModelUsage m : summary.models()) {
            rows.add(List.of(m.provider().isEmpty() ? m.model() : m.provider() + ":" + m.model(), m.calls(),
                    m.failures(), m.fallbacks(), m.inputTokens(), m.outputTokens(),
                    Math.round(m.avgLatencyMs()), String.format(Locale.ROOT, "%.4f", m.costUsd())));
        }
        fillTable(usageTable, List.of("Model", "Calls", "Failed", "Fallbacks", "Tokens in", "Tokens out", "Avg ms",
                "Cost $"), rows);
        usageLabel.setText(String.format(Locale.ROOT, "Policy: %s. Total %d calls, %d tokens, $%.4f",
                summary.policy(), summary.totalCalls(), summary.totalTokens(), summary.totalCost()));
    }

    private void tabChanged(int index) {
        if (index == 2) {
            controller.onHistoryClicked(historySearch.getText());
        } else if (index == 3) {
            controller.onUsageClicked();
        }
    }

    // Shared

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser(Path.of("data").toAbsolutePath().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Import data");
        chooser.setFileFilter(new FileNameExtensionFilter("Data files (*.db, *.sqlite, *.sqlite3, *.csv)", "db",
                "sqlite", "sqlite3", "csv"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> paths = Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList();
            if (!paths.isEmpty()) {
                controller.onImportClicked(paths, true);
            }
        }
    }

    public void setBusy(boolean busy, String text) {
        for (JComponent c : List.of(askButton, runSqlButton, importButton, question)) {
            c.setEnabled(!busy);
        }
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        if (busy) {
            showStatus(text);
        }
    }

    public void showStatus(String message) {
        statusBar.setText(message.isEmpty() ? " " : message);
    }

    public void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "AskDB", JOptionPane.WARNING_MESSAGE);
    }

    private static JTable readOnlyTable() {
        JTable table = new JTable(new DefaultTableModel());
        table.setDefaultEditor(Object.class, null);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(22);
        return table;
    }

    private static void fillTable(JTable table, List<String> columns, List<List<Object>> rows) {
        List<List<Object>> shown = rows.subList(0, Math.min(TABLE_LIMIT, rows.size()));
        Object[][] data = new Object[shown.size()][];
        boolean[] numeric = new boolean[columns.size()];
        Arrays.fill(numeric, true);
        for (int r = 0; r < shown.size(); r++) {
            List<Object> row = shown.get(r);
            data[r] = new Object[columns.size()];
            for (int c = 0; c < columns.size(); c++) {
                Object v = c < row.size() ? row.get(c) : null;
                if (v != null && !(v instanceof Number)) {
                    numeric[c] = false;
                }
                data[r][c] = QueryResult.format(v);
            }
        }
        DefaultTableModel model = new DefaultTableModel(data, columns.toArray()) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.setModel(model);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 0; c < columns.size(); c++) {
            if (numeric[c] && !shown.isEmpty()) {
                table.getColumnModel().getColumn(c).setCellRenderer(right);
            }
            int width = Math.max(60, columns.get(c).length() * 9);
            for (int r = 0; r < Math.min(50, data.length); r++) {
                width = Math.max(width, Math.min(400, String.valueOf(data[r][c]).length() * 8 + 16));
            }
            table.getColumnModel().getColumn(c).setPreferredWidth(width);
        }
    }

    private static JPanel labelled(String label, JComponent body) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.add(new JLabel(label), BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private static String titleCase(String status) {
        String[] words = status.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** List cell that wraps long step messages instead of cutting them off. */
    private static final class WrappingCellRenderer extends javax.swing.DefaultListCellRenderer {
        private final int width;

        WrappingCellRenderer(int width) {
            this.width = width;
        }

        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean selected, boolean focus) {
            String html = "<html><body style='width:" + width + "px'>" + escapeHtml(String.valueOf(value))
                    + "</body></html>";
            return super.getListCellRendererComponent(list, html, index, selected, focus);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // the default look and feel is fine
            }
            try {
                MainWindow window = new MainWindow(new AskDBFacade(Settings.load()));
                window.setVisible(true);
                window.getController().restoreLastSession();
            } catch (RuntimeException e) {
                JOptionPane.showMessageDialog(null, "AskDB could not start: " + e.getMessage(), "AskDB",
                        JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
