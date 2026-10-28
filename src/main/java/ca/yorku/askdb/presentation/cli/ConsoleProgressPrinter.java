package ca.yorku.askdb.presentation.cli;

import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.agent.AgentEventListener;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import picocli.CommandLine.Help.Ansi;

/** Observer that prints agent progress. With trace on it also prints model calls and tool output. */
public class ConsoleProgressPrinter implements AgentEventListener {
    private static final Map<String, String> SHORT = Map.of(
            "plan", "Planning",
            "tool_call", "Running tool",
            "repair", "Repairing",
            "clarification", "Needs clarification",
            "chart", "Chart",
            "verify", "Checking numbers");
    private static final Set<String> TRACE_KINDS = Set.of("action", "tool_result", "parse_error", "clarified");

    private final PrintStream out;
    private final boolean trace;

    public ConsoleProgressPrinter(PrintStream out, boolean trace) {
        this.out = out;
        this.trace = trace;
    }

    @Override
    public void onEvent(AgentEvent event) {
        String kind = event.kind();
        if (SHORT.containsKey(kind)) {
            out.println(Ansi.AUTO.string("@|faint   step " + event.step() + "|@ @|bold " + SHORT.get(kind) + ":|@ ")
                    + cut(event.message(), 160));
        } else if (trace && kind.equals("llm_call")) {
            String fallback = Boolean.TRUE.equals(event.get("fallback")) ? Ansi.AUTO.string(" @|yellow (fallback)|@")
                    : "";
            out.println(Ansi.AUTO.string("@|faint   model " + event.get("provider") + ":" + event.get("model")
                    + " task=" + event.get("task") + " tokens=" + event.get("input_tokens") + "+"
                    + event.get("output_tokens") + " "
                    + Math.round(((Number) event.get("latency_ms")).doubleValue()) + " ms|@") + fallback);
        } else if (kind.equals("llm_error")) {
            out.println(Ansi.AUTO.string("@|yellow   model problem:|@ ") + cut(event.message(), 160));
        } else if (trace && TRACE_KINDS.contains(kind)) {
            out.println("  " + kind + ": " + cut(event.message(), 400));
        }
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
