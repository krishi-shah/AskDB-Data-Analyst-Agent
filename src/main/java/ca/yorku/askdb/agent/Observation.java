package ca.yorku.askdb.agent;

public record Observation(AgentAction action, boolean ok, String content) {
}
