package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.agent.AgentEventListener;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

/** Observer: shows every agent event, including model calls, in the Usage and Trace tab. */
public class TraceView implements AgentEventListener {
    private static final int MAX_LINES = 5000;

    private final JTextArea area = new JTextArea();

    public TraceView() {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    }

    public JTextArea getComponent() {
        return area;
    }

    /** Called on the worker thread; the text is added on the Swing event thread. */
    @Override
    public void onEvent(AgentEvent event) {
        String line = format(event);
        SwingUtilities.invokeLater(() -> append(line));
    }

    static String format(AgentEvent event) {
        String stamp = new SimpleDateFormat("HH:mm:ss").format(new Date(event.timestampMs()));
        String line = String.format(Locale.ROOT, "%s step %2d %-13s %s", stamp, event.step(), event.kind(),
                event.message());
        if (event.kind().equals("llm_call")) {
            line += String.format(Locale.ROOT, "  [tokens %s+%s, %.0f ms%s]", event.get("input_tokens"),
                    event.get("output_tokens"), ((Number) event.get("latency_ms")).doubleValue(),
                    Boolean.TRUE.equals(event.get("fallback")) ? ", fallback" : "");
        }
        return line;
    }

    private void append(String line) {
        area.append(line + "\n");
        int extra = area.getLineCount() - MAX_LINES;
        if (extra > 0) {
            try {
                area.replaceRange("", 0, area.getLineEndOffset(extra - 1));
            } catch (BadLocationException ignored) {
                area.setText("");
            }
        }
        area.setCaretPosition(area.getDocument().getLength());
    }

    public void clear() {
        area.setText("");
    }
}
