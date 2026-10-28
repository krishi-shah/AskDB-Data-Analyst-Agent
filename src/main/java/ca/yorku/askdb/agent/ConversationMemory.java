package ca.yorku.askdb.agent;

import java.util.ArrayList;
import java.util.List;

/** Bounded list of recent turns used to resolve follow-up questions (F10). */
public class ConversationMemory {
    public record Turn(String question, String sql, String resultSummary, String answer) {
    }

    private final int maxTurns;
    private final List<Turn> turns = new ArrayList<>();

    public ConversationMemory(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public List<Turn> getTurns() {
        return List.copyOf(turns);
    }

    public void addTurn(Turn turn) {
        turns.add(turn);
        while (turns.size() > maxTurns) {
            turns.remove(0);
        }
    }

    public void clear() {
        turns.clear();
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    public String contextText() {
        if (turns.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Treat words like 'that', 'those', 'now only' or 'instead' as changes to the latest turn.");
        for (int i = 0; i < turns.size(); i++) {
            Turn t = turns.get(i);
            String line = "Turn " + (i + 1) + ": Q: " + t.question() + "\n  SQL: " + t.sql()
                    + "\n  Result: " + t.resultSummary();
            if (t.answer() != null && !t.answer().isEmpty()) {
                line += "\n  Answer: " + t.answer();
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }
}
