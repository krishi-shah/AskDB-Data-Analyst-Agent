package ca.yorku.askdb.llm;

import ca.yorku.askdb.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Small HTTP helper shared by the adapters. Maps transport errors to LLMException types. */
public final class HttpJson {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private HttpJson() {
    }

    public static JsonNode get(String url, double timeoutS) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis((long) (timeoutS * 1000)))
                .GET(), url, timeoutS);
    }

    public static JsonNode post(String url, Object payload, Map<String, String> headers, double timeoutS) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis((long) (timeoutS * 1000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload)));
        headers.forEach(builder::header);
        return send(builder, url, timeoutS);
    }

    private static JsonNode send(HttpRequest.Builder builder, String url, double timeoutS) {
        HttpResponse<String> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new LLMException.Timeout("No reply within " + Json.trimNumber(timeoutS) + " seconds.", e);
        } catch (ConnectException e) {
            throw new LLMException.Unavailable("Cannot reach " + url + ": connection refused", e);
        } catch (IOException e) {
            throw new LLMException.Unavailable("Cannot reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("The request was interrupted.", e);
        }
        int code = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (code == 429) {
            throw new LLMException.RateLimited("Rate limited (HTTP 429): " + cut(body));
        }
        if (code == 401 || code == 403) {
            throw new LLMException.Unavailable("The API key was rejected (HTTP " + code + ").");
        }
        if (code >= 400) {
            throw new LLMException("HTTP " + code + ": " + cut(body));
        }
        try {
            return Json.MAPPER.readTree(body);
        } catch (IOException e) {
            throw new LLMException("The reply was not JSON: " + cut(body), e);
        }
    }

    private static String cut(String body) {
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
