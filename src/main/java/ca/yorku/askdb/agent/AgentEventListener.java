package ca.yorku.askdb.agent;

/** Observer interface: told about every agent step, tool call, and model call. */
public interface AgentEventListener {
    void onEvent(AgentEvent event);
}
