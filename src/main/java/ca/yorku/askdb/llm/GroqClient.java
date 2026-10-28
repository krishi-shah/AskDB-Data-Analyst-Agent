package ca.yorku.askdb.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Adaptee: the Groq chat completions API. */
public class GroqClient {
    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final double timeoutS;

    public GroqClient(String apiKey, String model, double timeoutS) {
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutS = timeoutS;
    }

    public String getModel() {
        return model;
    }

    public JsonNode generate(Object payload) {
        return HttpJson.post(URL, payload, Map.of("Authorization", "Bearer " + apiKey), timeoutS);
    }
}
