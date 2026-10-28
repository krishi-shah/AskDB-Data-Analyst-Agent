package ca.yorku.askdb.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * State of ModelRouter (State pattern). providers maps a tier ("fast", "strong", "local") to an LLMProvider;
 * "local" is always present, and the cloud tiers exist only when a key is set.
 */
public abstract class RoutingPolicy {
    public static final List<String> NAMES = List.of("cheap_first", "strong_only", "local_only");

    public abstract String name();

    public boolean allowsCloud() {
        return true;
    }

    public abstract LLMProvider select(String task, int attempt, Map<String, LLMProvider> providers);

    /** Providers to try, in order, after the selected one fails. */
    public List<LLMProvider> fallbackOrder(Map<String, LLMProvider> providers) {
        List<String> tiers = allowsCloud() ? List.of("strong", "fast", "local") : List.of("local");
        return pick(providers, tiers);
    }

    protected static List<LLMProvider> pick(Map<String, LLMProvider> providers, List<String> tiers) {
        List<LLMProvider> out = new ArrayList<>();
        for (String t : tiers) {
            if (providers.containsKey(t)) {
                out.add(providers.get(t));
            }
        }
        return out;
    }

    public static RoutingPolicy byName(String name) {
        return switch (name == null ? "" : name) {
            case "cheap_first" -> new CheapFirstPolicy();
            case "strong_only" -> new StrongOnlyPolicy();
            case "local_only" -> new LocalOnlyPolicy();
            default -> throw new IllegalArgumentException(
                    "Unknown policy '" + name + "'. Choose one of: " + String.join(", ", NAMES) + ".");
        };
    }
}
