package ca.yorku.askdb.agent;

import java.util.Map;

/** Notification data of the Observer pattern. */
public record AgentEvent(String kind, String message, int step, Map<String, Object> data, long timestampMs) {

    public Object get(String key) {
        return data.get(key);
    }
}
