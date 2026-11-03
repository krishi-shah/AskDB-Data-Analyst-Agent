package ca.yorku.askdb.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = ("{\"message\":{\"content\":\"{}\"},\"prompt_eval_count\":1,\"eval_count\":1}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OllamaAdapter adapter() {
        return new OllamaAdapter("qwen2.5-coder:7b", "http://127.0.0.1:" + server.getAddress().getPort(), 5);
    }

    private JsonNode sent() throws Exception {
        return MAPPER.readTree(body.get());
    }

    @Test
    void completeKeepsTheModelLoaded() throws Exception {
        adapter().complete(List.of(ChatMessage.user("hi")), 16);
        assertEquals("/api/chat", path.get());
        assertEquals(-1, sent().path("keep_alive").asInt(0));
    }

    @Test
    void warmupAsksOllamaToLoadTheModel() throws Exception {
        adapter().warmup();
        assertEquals("/api/generate", path.get());
        assertEquals("qwen2.5-coder:7b", sent().path("model").asText());
        assertEquals("hi", sent().path("prompt").asText());
        assertEquals(-1, sent().path("keep_alive").asInt(0));
        assertEquals(1, sent().path("options").path("num_predict").asInt(0));
    }
}
