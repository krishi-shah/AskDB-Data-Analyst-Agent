package ca.yorku.askdb.llm;

import ca.yorku.askdb.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapter for a local Ollama server (adaptee: the Ollama HTTP API). */
public class OllamaAdapter implements LLMProvider {
    private final String model;
    private final String baseUrl;
    private final double timeoutS;

    public OllamaAdapter(String model, String baseUrl, double timeoutS) {
        this.model = model;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeoutS = timeoutS;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public String tier() {
        return "local";
    }

    @Override
    public LLMResponse complete(List<ChatMessage> messages, int maxTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", false);
        payload.put("format", "json");
        payload.put("options", Map.of("temperature", 0, "num_predict", maxTokens));
        long start = System.nanoTime();
        JsonNode body = HttpJson.post(baseUrl + "/api/chat", payload, Map.of(), timeoutS);
        if (body.has("error")) {
            throw new LLMException("Ollama: " + body.get("error").asText());
        }
        return new LLMResponse(
                Json.text(body.path("message"), "content", ""), "ollama", model,
                Json.integer(body, "prompt_eval_count"), Json.integer(body, "eval_count"),
                (System.nanoTime() - start) / 1_000_000.0, 0.0);
    }
}
