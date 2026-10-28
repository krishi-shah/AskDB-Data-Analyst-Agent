package ca.yorku.askdb.llm;

import java.util.Map;

/** No data leaves the machine. */
public class LocalOnlyPolicy extends RoutingPolicy {
    @Override
    public String name() {
        return "local_only";
    }

    @Override
    public boolean allowsCloud() {
        return false;
    }

    @Override
    public LLMProvider select(String task, int attempt, Map<String, LLMProvider> providers) {
        return providers.get("local");
    }
}
