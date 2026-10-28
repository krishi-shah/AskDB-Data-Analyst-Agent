package ca.yorku.askdb.retrieval;

import ca.yorku.askdb.data.ColumnInfo;
import ca.yorku.askdb.data.SchemaInfo;
import ca.yorku.askdb.data.TableInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Semantic search over table and column entries (F02). If the embedding provider fails, the index falls back
 * to keyword overlap, so questions still work.
 */
public class SchemaIndex {
    private final EmbeddingProvider embedder;
    private final Map<String, SchemaEntry> entries = new LinkedHashMap<>();
    private String mode = "keyword";
    private String warning = "";

    public SchemaIndex(EmbeddingProvider embedder) {
        this.embedder = embedder;
    }

    public String getMode() {
        return mode;
    }

    public String getWarning() {
        return warning;
    }

    public Map<String, SchemaEntry> getEntries() {
        return entries;
    }

    public void build(SchemaInfo schema) {
        entries.clear();
        for (TableInfo table : schema.getTables()) {
            entries.put(table.getName(), new SchemaEntry(table.getName(), table.getName(), tableText(table)));
            for (ColumnInfo col : table.getColumns()) {
                String id = table.getName() + "." + col.getName();
                entries.put(id, new SchemaEntry(id, table.getName(), columnText(table, col)));
            }
        }
        embedAll();
    }

    public static String tableText(TableInfo table) {
        String cols = table.getColumns().stream().map(ColumnInfo::getName).collect(Collectors.joining(", "));
        String desc = table.getDescription().isEmpty() ? "" : table.getDescription() + " ";
        return "table " + table.getName() + ": " + desc + "columns: " + cols;
    }

    public static String columnText(TableInfo table, ColumnInfo col) {
        String key = col.isPrimaryKey() ? " PK" : col.getReferences() != null ? " FK->" + col.getReferences() : "";
        String desc = col.getDescription().isEmpty() ? "" : " - " + col.getDescription();
        return table.getName() + "." + col.getName() + " " + col.getType() + key + desc;
    }

    private void embedAll() {
        if (embedder == null) {
            mode = "keyword";
            return;
        }
        try {
            List<SchemaEntry> items = new ArrayList<>(entries.values());
            List<double[]> vectors = embedder.embed(items.stream().map(SchemaEntry::getText).toList());
            for (int i = 0; i < items.size(); i++) {
                items.get(i).setVector(vectors.get(i));
            }
            mode = "semantic";
            warning = "";
        } catch (EmbeddingProvider.EmbeddingException e) {
            mode = "keyword";
            warning = "Semantic search unavailable, using keyword search: " + e.getMessage();
        }
    }

    public List<SchemaEntry> search(String query, int k) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scores = new HashMap<>();
        if (mode.equals("semantic")) {
            try {
                double[] qv = embedder.embed(List.of(query)).get(0);
                for (SchemaEntry e : entries.values()) {
                    if (e.getVector() != null) {
                        scores.put(e.getEntryId(), cosine(qv, e.getVector()));
                    }
                }
            } catch (EmbeddingProvider.EmbeddingException e) {
                scores.clear();
            }
        }
        Set<String> q = tokens(query);
        for (SchemaEntry e : entries.values()) {
            Set<String> overlap = new HashSet<>(q);
            overlap.retainAll(tokens(e.getText()));
            double share = (double) overlap.size() / Math.max(1, q.size());
            scores.merge(e.getEntryId(), 0.5 * share, Double::sum);
        }
        List<SchemaEntry> ranked = new ArrayList<>(entries.values());
        ranked.sort(Comparator.comparingDouble((SchemaEntry e) -> scores.getOrDefault(e.getEntryId(), 0.0))
                .reversed());
        List<SchemaEntry> hits = new ArrayList<>();
        for (SchemaEntry e : ranked.subList(0, Math.min(k, ranked.size()))) {
            e.setScore(Math.round(scores.getOrDefault(e.getEntryId(), 0.0) * 1000) / 1000.0);
            hits.add(e);
        }
        return hits;
    }

    public void updateEntry(String entryId, String text) {
        SchemaEntry entry = entries.get(entryId);
        if (entry == null) {
            return;
        }
        entry.setText(text);
        if (mode.equals("semantic")) {
            try {
                entry.setVector(embedder.embed(List.of(text)).get(0));
            } catch (EmbeddingProvider.EmbeddingException e) {
                entry.setVector(null);
            }
        }
    }

    static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.isEmpty()) {
                continue;
            }
            out.add(w);
            if (w.endsWith("s") && w.length() > 3) {
                out.add(w.substring(0, w.length() - 1));
            }
        }
        return out;
    }

    static double cosine(double[] a, double[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na > 0 && nb > 0 ? dot / (Math.sqrt(na) * Math.sqrt(nb)) : 0.0;
    }
}
