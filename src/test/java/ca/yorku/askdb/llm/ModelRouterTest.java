package ca.yorku.askdb.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelRouterTest {
    @Test
    void cheapFirstUsesTheStrongTierFromTheSecondRepair() {
        Stub fast = new Stub("fast", "gemini");
        Stub strong = new Stub("strong", "groq");
        Stub local = new Stub("local", "ollama");
        ModelRouter router = router(new CheapFirstPolicy(), fast, strong, local);

        router.complete(List.of(ChatMessage.user("q")), "repair", 1, 32);
        router.complete(List.of(ChatMessage.user("q")), "repair", 2, 32);

        assertEquals(List.of("fast"), fast.called);
        assertEquals(List.of("strong"), strong.called);
        assertTrue(local.called.isEmpty());
    }

    @Test
    void localOnlyNeverCallsACloudProvider() {
        Stub fast = new Stub("fast", "gemini");
        Stub strong = new Stub("strong", "groq");
        Stub local = new Stub("local", "ollama");
        local.fail(new LLMException("down"));
        ModelRouter router = router(new LocalOnlyPolicy(), fast, strong, local);

        LLMException error = assertThrows(LLMException.class,
                () -> router.complete(List.of(ChatMessage.user("q")), "plan", 1, 32));

        assertTrue(error.getMessage().contains("down"));
        assertEquals(List.of("local"), local.called);
        assertTrue(fast.called.isEmpty());
        assertTrue(strong.called.isEmpty());
    }

    @Test
    void rateLimitIsRetriedThenSucceeds() {
        Stub local = new Stub("local", "ollama");
        local.fail(new LLMException.RateLimited("429"));
        local.fail(new LLMException.RateLimited("429"));
        ModelRouter router = new ModelRouter(Map.of("local", local), new LocalOnlyPolicy(), new long[] {0, 0});

        LLMResponse response = router.complete(List.of(ChatMessage.user("q")), "plan", 1, 32);

        assertEquals("ok", response.text());
        assertEquals(3, local.called.size());
    }

    private static ModelRouter router(RoutingPolicy policy, Stub fast, Stub strong, Stub local) {
        Map<String, LLMProvider> providers = new LinkedHashMap<>();
        providers.put("fast", fast);
        providers.put("strong", strong);
        providers.put("local", local);
        return new ModelRouter(providers, policy, new long[0]);
    }

    /** Records each call and returns scripted failures, then a plain reply. */
    private static final class Stub implements LLMProvider {
        private final String tier;
        private final String model;
        private final List<LLMException> failures = new ArrayList<>();
        final List<String> called = new ArrayList<>();

        Stub(String tier, String model) {
            this.tier = tier;
            this.model = model;
        }

        void fail(LLMException error) {
            failures.add(error);
        }

        @Override
        public LLMResponse complete(List<ChatMessage> messages, int maxTokens) {
            called.add(tier);
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
            return new LLMResponse("ok", tier, model, 1, 1, 0, 0);
        }

        @Override
        public String modelName() {
            return model;
        }

        @Override
        public String tier() {
            return tier;
        }
    }
}
