package ca.yorku.askdb.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Shared Jackson mapper and small JSON helpers. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    public static int integer(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null ? 0 : v.asInt(0);
    }

    public static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
