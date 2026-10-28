package ca.yorku.askdb.llm;

import ca.yorku.askdb.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the neutral message list to the Gemini format and back. */
public class GeminiAdapter implements LLMProvider {
    private final GeminiClient client;

    public GeminiAdapter(GeminiClient client) {
        this.client = client;
    }

    @Override
    public String modelName() {
        return client.getModel();
    }

    @Override
    public String tier() {
        return "fast";
    }

    @Override
    public LLMResponse complete(List<ChatMessage> messages, int maxTokens) {
        List<String> system = new ArrayList<>();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage m : messages) {
            if ("system".equals(m.role())) {
                system.add(m.content());
            } else {
                contents.add(Map.of("role", "assistant".equals(m.role()) ? "model" : "user",
                        "parts", List.of(Map.of("text", m.content()))));
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contents", contents);
        payload.put("generationConfig", Map.of("temperature", 0, "maxOutputTokens", maxTokens,
                "responseMimeType", "application/json"));
        if (!system.isEmpty()) {
            payload.put("systemInstruction", Map.of("parts", List.of(Map.of("text", String.join("\n\n", system)))));
        }
        long start = System.nanoTime();
        JsonNode body = client.generate(payload);
        JsonNode parts = body.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new LLMException("Gemini returned no candidate text.");
        }
        StringBuilder text = new StringBuilder();
        parts.forEach(p -> text.append(Json.text(p, "text", "")));
        JsonNode usage = body.path("usageMetadata");
        return new LLMResponse(text.toString(), "gemini", client.getModel(),
                Json.integer(usage, "promptTokenCount"), Json.integer(usage, "candidatesTokenCount"),
                (System.nanoTime() - start) / 1_000_000.0, 0.0);
    }
}
