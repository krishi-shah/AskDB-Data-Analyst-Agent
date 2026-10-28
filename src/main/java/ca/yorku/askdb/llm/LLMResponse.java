package ca.yorku.askdb.llm;

public record LLMResponse(String text, String provider, String model, int inputTokens, int outputTokens,
                          double latencyMs, double costUsd) {
}
