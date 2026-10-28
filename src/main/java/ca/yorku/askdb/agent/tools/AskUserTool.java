package ca.yorku.askdb.agent.tools;

import ca.yorku.askdb.agent.ClarificationRequest;
import ca.yorku.askdb.app.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AskUserTool implements Tool {
    @Override
    public String name() {
        return "ask_user";
    }

    @Override
    public String description() {
        return "Ask the analyst to choose between 2 to 4 meanings when the question is truly ambiguous. "
                + "Use it only when guessing would likely give the wrong answer.";
    }

    @Override
    public Map<String, Param> parameters() {
        return SearchSchemaTool.ordered(Map.of(
                "question", new Param("string", true, "the clarifying question"),
                "options", new Param("list", true, "2 to 4 short choices")), "question", "options");
    }

    @Override
    public ToolResult execute(Map<String, Object> args, Session session) {
        List<String> options = new ArrayList<>();
        for (Object o : (List<?>) args.get("options")) {
            String s = String.valueOf(o).strip();
            if (!s.isEmpty()) {
                options.add(s);
            }
        }
        if (options.size() < 2) {
            return ToolResult.failure("ask_user needs at least two options.");
        }
        ClarificationRequest request = new ClarificationRequest(((String) args.get("question")).strip(),
                options.subList(0, Math.min(4, options.size())));
        return ToolResult.success("Asked the analyst: " + request.question(), request);
    }
}
