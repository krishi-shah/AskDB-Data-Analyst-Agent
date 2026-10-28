package ca.yorku.askdb.llm;

import java.util.List;
import java.util.Map;

/** Fast tier for routine work; strong tier from the second repair attempt. */
public class CheapFirstPolicy extends RoutingPolicy {
    @Override
    public String name() {
        return "cheap_first";
    }

    @Override
    public LLMProvider select(String task, int attempt, Map<String, LLMProvider> providers) {
        if ("repair".equals(task) && attempt >= 2 && providers.containsKey("strong")) {
            return providers.get("strong");
        }
        if (providers.containsKey("fast")) {
            return providers.get("fast");
        }
        return providers.getOrDefault("strong", providers.get("local"));
    }

    @Override
    public List<LLMProvider> fallbackOrder(Map<String, LLMProvider> providers) {
        return pick(providers, List.of("fast", "strong", "local"));
    }
}
