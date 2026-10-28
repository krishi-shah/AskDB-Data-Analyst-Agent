package ca.yorku.askdb.storage;

public record HistoryEntry(Long id, String createdAt, String dataset, String question, String sql, String status,
                           int rowCount, String summary, boolean manual) {
}
