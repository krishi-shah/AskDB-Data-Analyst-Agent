package ca.yorku.askdb.retrieval;

import ca.yorku.askdb.llm.HttpJson;
import ca.yorku.askdb.llm.LLMException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** nomic-embed-text through the local Ollama /api/embed endpoint. */
public class OllamaEmbeddingProvider implements EmbeddingProvider {
    public static final String DEFAULT_MODEL = "nomic-embed-text";

    private final String baseUrl;
    private final String model;
    private final double timeoutS;

    public OllamaEmbeddingProvider(String baseUrl, String model, double timeoutS) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.timeoutS = timeoutS;
    }

    @Override
    public String name() {
        return "ollama " + model;
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        JsonNode body;
        try {
            body = HttpJson.post(baseUrl + "/api/embed", Map.of("model", model, "input", texts), Map.of(), timeoutS);
        } catch (LLMException e) {
            throw new EmbeddingException(e.getMessage());
        }
        JsonNode vectors = body.path("embeddings");
        if (!vectors.isArray() || vectors.size() != texts.size()) {
            throw new EmbeddingException("Ollama returned no embeddings: " + body.path("error").asText("unknown error"));
        }
        List<double[]> out = new ArrayList<>();
        for (JsonNode vec : vectors) {
            double[] v = new double[vec.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = vec.get(i).asDouble();
            }
            out.add(v);
        }
        return out;
    }

    /** Uses Ollama embeddings when the model is pulled; otherwise the offline local embedder. */
    public static EmbeddingProvider defaultFor(String ollamaUrl) {
        try {
            JsonNode tags = HttpJson.get(ollamaUrl.replaceAll("/+$", "") + "/api/tags", 3);
            for (JsonNode m : tags.path("models")) {
                if (m.path("name").asText().startsWith(DEFAULT_MODEL)) {
                    return new OllamaEmbeddingProvider(ollamaUrl, DEFAULT_MODEL, 60);
                }
            }
        } catch (LLMException e) {
            // Ollama is not running; the local embedder still works
        }
        return new LocalEmbeddingProvider();
    }
}
