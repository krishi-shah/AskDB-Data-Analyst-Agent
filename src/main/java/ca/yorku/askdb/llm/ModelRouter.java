package ca.yorku.askdb.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Context of the State pattern: the current RoutingPolicy chooses the provider for each call. */
public class ModelRouter {
    /** Told about every call: the task, the response (null on failure), and a note. */
    public interface CallListener {
        void onCall(String task, LLMResponse response, String note);
    }

    private final Map<String, LLMProvider> providers;
    private final long[] retryDelaysMs;
    private RoutingPolicy policy;
    private CallListener listener;

    public ModelRouter(Map<String, LLMProvider> providers) {
        this(providers, new LocalOnlyPolicy(), new long[] {1000, 2000});
    }

    public ModelRouter(Map<String, LLMProvider> providers, RoutingPolicy policy, long[] retryDelaysMs) {
        if (!providers.containsKey("local")) {
            throw new IllegalArgumentException("A local provider is required.");
        }
        this.providers = new LinkedHashMap<>(providers);
        this.policy = policy == null ? new LocalOnlyPolicy() : policy;
        this.retryDelaysMs = retryDelaysMs;
    }

    public RoutingPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(RoutingPolicy policy) {
        this.policy = policy;
    }

    public Map<String, LLMProvider> getProviders() {
        return providers;
    }

    public void addProvider(String tier, LLMProvider provider) {
        providers.put(tier, provider);
    }

    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    public LLMResponse complete(List<ChatMessage> messages, String task) {
        return complete(messages, task, 1, 1024);
    }

    public LLMResponse complete(List<ChatMessage> messages, String task, int attempt, int maxTokens) {
        LLMProvider provider = policy.select(task, attempt, providers);
        LLMResponse response;
        try {
            response = callWithBackoff(provider, messages, maxTokens);
        } catch (LLMException e) {
            notify(task, null, provider.modelName() + " failed: " + e.getMessage());
            return fallback(provider, messages, task, maxTokens, e);
        }
        notify(task, response, "");
        return response;
    }

    public LLMResponse fallback(LLMProvider failed, List<ChatMessage> messages, String task, int maxTokens,
                                Exception error) {
        Exception last = error;
        for (LLMProvider provider : policy.fallbackOrder(providers)) {
            if (provider == failed) {
                continue;
            }
            try {
                LLMResponse response = provider.complete(messages, maxTokens);
                notify(task, response, "fallback from " + failed.modelName());
                return response;
            } catch (LLMException e) {
                last = e;
                notify(task, null, provider.modelName() + " failed: " + e.getMessage());
            }
        }
        throw new LLMException("No model could answer. Last error: " + (last == null ? "none" : last.getMessage()));
    }

    private LLMResponse callWithBackoff(LLMProvider provider, List<ChatMessage> messages, int maxTokens) {
        for (int i = 0; ; i++) {
            try {
                return provider.complete(messages, maxTokens);
            } catch (LLMException.RateLimited e) {
                if (i >= retryDelaysMs.length) {
                    throw e;
                }
                try {
                    Thread.sleep(retryDelaysMs[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void notify(String task, LLMResponse response, String note) {
        if (listener != null) {
            listener.onCall(task, response, note);
        }
    }
}
