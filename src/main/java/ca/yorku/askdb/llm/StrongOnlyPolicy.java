package ca.yorku.askdb.llm;

import java.util.Map;

public class StrongOnlyPolicy extends RoutingPolicy {
    @Override
    public String name() {
        return "strong_only";
    }

    @Override
    public LLMProvider select(String task, int attempt, Map<String, LLMProvider> providers) {
        return providers.getOrDefault("strong", providers.get("local"));
    }
}
