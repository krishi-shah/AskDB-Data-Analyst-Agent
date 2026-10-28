package ca.yorku.askdb.agent;

import java.util.List;

public record ClarificationRequest(String question, List<String> options) {
    public ClarificationRequest {
        options = List.copyOf(options);
    }
}
