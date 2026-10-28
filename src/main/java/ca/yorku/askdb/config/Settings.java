package ca.yorku.askdb.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings loaded from the environment and an optional local .env file.
 * Real keys live only in .env, which git ignores. Values that still hold the
 * placeholder from .env.example are treated as missing.
 */
public class Settings {
    public static final String PLACEHOLDER = "YOUR_LOCAL_API_KEY_HERE";

    public String ollamaUrl = "http://localhost:11434";
    public String ollamaModel = "qwen2.5-coder:7b";
    public String geminiApiKey;
    public String geminiModel = "gemini-2.5-flash";
    public String groqApiKey;
    public String groqModel = "openai/gpt-oss-120b";
    public String policy = "local_only";
    public Path home = Path.of(System.getProperty("user.home"), ".askdb");
    public int rowLimit = 1000;
    public double queryTimeoutS = 10.0;
    public int maxFileMb = 200;
    public int maxSteps = 8;
    public int maxRepairs = 3;
    public int maxClarifications = 2;
    public int memoryTurns = 5;
    public boolean shareSamples = true;
    public double llmTimeoutS = 180.0;

    public Path appDb() {
        return home.resolve("askdb.sqlite");
    }

    public Path stateFile() {
        return home.resolve("state.json");
    }

    public void ensureHome() {
        try {
            Files.createDirectories(home);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + home + ": " + e.getMessage(), e);
        }
    }

    /** Reads settings from .env in the current directory and the environment. */
    public static Settings load() {
        return load(Path.of(".env"));
    }

    public static Settings load(Path envFile) {
        Map<String, String> fileValues = readEnvFile(envFile);
        Settings s = new Settings();
        s.ollamaUrl = stripSlash(get(fileValues, "OLLAMA_URL", s.ollamaUrl));
        s.ollamaModel = get(fileValues, "OLLAMA_MODEL", s.ollamaModel);
        s.geminiApiKey = cleanKey(get(fileValues, "GEMINI_API_KEY", null));
        s.geminiModel = get(fileValues, "GEMINI_MODEL", s.geminiModel);
        s.groqApiKey = cleanKey(get(fileValues, "GROQ_API_KEY", null));
        s.groqModel = get(fileValues, "GROQ_MODEL", s.groqModel);
        s.policy = get(fileValues, "ASKDB_POLICY", s.policy);
        String home = get(fileValues, "ASKDB_HOME", null);
        if (home != null) {
            s.home = Path.of(home.replaceFirst("^~", System.getProperty("user.home")));
        }
        return s;
    }

    private static String get(Map<String, String> fileValues, String name, String fallback) {
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String file = fileValues.get(name);
        if (file != null && !file.isBlank()) {
            return file;
        }
        return fallback;
    }

    private static String cleanKey(String value) {
        if (value == null || value.isBlank() || value.equals(PLACEHOLDER)) {
            return null;
        }
        return value;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static Map<String, String> readEnvFile(Path path) {
        Map<String, String> values = new HashMap<>();
        if (path == null || !Files.isRegularFile(path)) {
            return values;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return values;
        }
        for (String raw : lines) {
            String line = raw.strip();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq < 0) {
                continue;
            }
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(line.substring(0, eq).strip(), value);
        }
        return values;
    }
}
