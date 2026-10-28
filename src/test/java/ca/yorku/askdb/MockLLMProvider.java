package ca.yorku.askdb;

import ca.yorku.askdb.llm.ChatMessage;
import ca.yorku.askdb.llm.LLMProvider;
import ca.yorku.askdb.llm.LLMResponse;
import ca.yorku.askdb.util.Json;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Replays scripted replies so agent checks run without a real model. Test code only. */
public class MockLLMProvider implements LLMProvider {
    private final Deque<Object> replies = new ArrayDeque<>();
    private final List<List<ChatMessage>> prompts = new ArrayList<>();

    /** Each reply is a String sent as is, or an object (usually a Map) sent as JSON. */
    public MockLLMProvider reply(Object... values) {
        for (Object v : values) {
            replies.addLast(v);
        }
        return this;
    }

    public List<List<ChatMessage>> getPrompts() {
        return prompts;
    }

    @Override
    public LLMResponse complete(List<ChatMessage> messages, int maxTokens) {
        prompts.add(messages);
        if (replies.isEmpty()) {
            throw new AssertionError("MockLLMProvider ran out of scripted replies");
        }
        Object reply = replies.removeFirst();
        String text = reply instanceof String s ? s : Json.write(reply);
        return new LLMResponse(text, "mock", "mock", 10, 5, 0.0, 0.0);
    }

    @Override
    public String modelName() {
        return "mock";
    }

    @Override
    public String tier() {
        return "local";
    }
}
