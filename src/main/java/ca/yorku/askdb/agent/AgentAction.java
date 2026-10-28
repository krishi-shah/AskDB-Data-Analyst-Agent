package ca.yorku.askdb.agent;

import java.util.Map;

/** kind is "tool", "final", or "invalid". */
public record AgentAction(String kind, String tool, Map<String, Object> args, String thought, String answer) {

    public static AgentAction tool(String tool, Map<String, Object> args, String thought) {
        return new AgentAction("tool", tool, args, thought, "");
    }

    public static AgentAction fin(String thought, String answer) {
        return new AgentAction("final", "", Map.of(), thought, answer);
    }

    public static AgentAction invalid() {
        return new AgentAction("invalid", "", Map.of(), "", "");
    }

    public boolean isFinal() {
        return "final".equals(kind);
    }
}
