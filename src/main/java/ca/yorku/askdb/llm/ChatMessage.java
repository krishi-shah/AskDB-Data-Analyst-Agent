package ca.yorku.askdb.llm;

/** One chat message; role is "system", "user", or "assistant". */
public record ChatMessage(String role, String content) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
}
