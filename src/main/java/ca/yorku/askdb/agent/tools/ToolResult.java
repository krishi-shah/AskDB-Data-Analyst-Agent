package ca.yorku.askdb.agent.tools;

/** content is what the model sees; data is the typed result for the program. */
public record ToolResult(boolean ok, String content, Object data, String error) {

    public static ToolResult success(String content, Object data) {
        return new ToolResult(true, content, data, "");
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, "Error: " + error, null, error);
    }
}
