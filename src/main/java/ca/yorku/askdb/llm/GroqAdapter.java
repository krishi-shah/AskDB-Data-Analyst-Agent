package ca.yorku.askdb.llm;

import ca.yorku.askdb.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts the neutral message list to the Groq format and back. */
public class GroqAdapter implements LLMProvider {
    private final GroqClient client;

    public GroqAdapter(GroqClient client) {
        this.client = client;
    }

    @Override
    public String modelName() {
        return client.getModel();
    }

    @Override
    public String tier() {
        return "strong";
    }

    @Override
    public LLMResponse complete(List<ChatMessage> messages, int maxTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", client.getModel());
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("max_tokens", maxTokens);
        payload.put("response_format", Map.of("type", "json_object"));
        long start = System.nanoTime();
        JsonNode body = client.generate(payload);
        JsonNode message = body.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw new LLMException("Groq returned no message.");
        }
        JsonNode usage = body.path("usage");
        return new LLMResponse(Json.text(message, "content", ""), "groq", client.getModel(),
                Json.integer(usage, "prompt_tokens"), Json.integer(usage, "completion_tokens"),
                (System.nanoTime() - start) / 1_000_000.0, 0.0);
    }
}
