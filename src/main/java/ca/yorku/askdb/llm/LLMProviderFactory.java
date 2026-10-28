package ca.yorku.askdb.llm;

import ca.yorku.askdb.config.Settings;
import java.util.ArrayList;
import java.util.List;

/** Factory: creates a provider adapter from its configured name. */
public class LLMProviderFactory {
    private final Settings settings;

    public LLMProviderFactory(Settings settings) {
        this.settings = settings;
    }

    public List<String> available() {
        List<String> names = new ArrayList<>(List.of("ollama"));
        if (settings.geminiApiKey != null) {
            names.add("gemini");
        }
        if (settings.groqApiKey != null) {
            names.add("groq");
        }
        return names;
    }

    public LLMProvider create(String providerName) {
        Settings s = settings;
        return switch (providerName) {
            case "ollama" -> new OllamaAdapter(s.ollamaModel, s.ollamaUrl, s.llmTimeoutS);
            case "gemini" -> {
                if (s.geminiApiKey == null) {
                    throw new LLMException.Unavailable("GEMINI_API_KEY is not set in .env.");
                }
                yield new GeminiAdapter(new GeminiClient(s.geminiApiKey, s.geminiModel, s.llmTimeoutS));
            }
            case "groq" -> {
                if (s.groqApiKey == null) {
                    throw new LLMException.Unavailable("GROQ_API_KEY is not set in .env.");
                }
                yield new GroqAdapter(new GroqClient(s.groqApiKey, s.groqModel, s.llmTimeoutS));
            }
            default -> throw new IllegalArgumentException("Unknown provider: " + providerName);
        };
    }
}
