package ca.yorku.askdb.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Adaptee: the Gemini REST API. */
public class GeminiClient {
    private static final String URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final String apiKey;
    private final String model;
    private final double timeoutS;

    public GeminiClient(String apiKey, String model, double timeoutS) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutS = timeoutS;
    }

    public String getModel() {
        return model;
    }

    public JsonNode generate(Object payload) {
        return HttpJson.post(String.format(URL, model), payload, Map.of("x-goog-api-key", apiKey), timeoutS);
    }
}
