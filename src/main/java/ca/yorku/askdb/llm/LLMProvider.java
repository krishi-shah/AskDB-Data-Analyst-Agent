package ca.yorku.askdb.llm;

import java.util.List;

/** Target interface of the Adapter pattern: every model, local or cloud, is called through it. */
public interface LLMProvider {
    LLMResponse complete(List<ChatMessage> messages, int maxTokens);

    String modelName();

    /** "local", "fast", or "strong". */
    String tier();

    default boolean isCloud() {
        return !"local".equals(tier());
    }
}
