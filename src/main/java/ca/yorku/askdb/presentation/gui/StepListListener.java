package ca.yorku.askdb.presentation.gui;

import ca.yorku.askdb.agent.AgentEvent;
import ca.yorku.askdb.agent.AgentEventListener;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Observer that feeds the short live step list on the Ask tab. */
public class StepListListener implements AgentEventListener {
    static final Set<String> SHOWN = Set.of("plan", "tool_call", "repair", "clarification", "clarified", "chart",
            "verify", "refused", "llm_error");

    private final Consumer<AgentEvent> onStep;

    public StepListListener(Consumer<AgentEvent> onStep) {
        this.onStep = onStep;
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (SHOWN.contains(event.kind())) {
            SwingUtilities.invokeLater(() -> onStep.accept(event));
        }
    }
}
