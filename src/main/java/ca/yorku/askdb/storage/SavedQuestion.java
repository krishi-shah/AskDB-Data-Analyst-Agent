package ca.yorku.askdb.storage;

public record SavedQuestion(String name, String question, String sql, String dataset, String createdAt) {
}
