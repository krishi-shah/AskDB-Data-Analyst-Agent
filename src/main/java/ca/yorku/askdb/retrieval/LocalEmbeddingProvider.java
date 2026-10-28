package ca.yorku.askdb.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline embedder written in plain Java: each text becomes a hashed bag of words and character trigrams,
 * normalised to unit length. It finds shared words and word parts (for example "order" and "orders"),
 * not synonyms; OllamaEmbeddingProvider is used for that when its model is available.
 */
public class LocalEmbeddingProvider implements EmbeddingProvider {
    private static final int DIMENSIONS = 1024;

    @Override
    public String name() {
        return "local n-gram";
    }

    @Override
    public List<double[]> embed(List<String> texts) {
        List<double[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(vector(text));
        }
        return out;
    }

    private static double[] vector(String text) {
        double[] v = new double[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            v[bucket("w:" + word)] += 2.0;
            String padded = "#" + word + "#";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                v[bucket(padded.substring(i, i + 3))] += 1.0;
            }
        }
        double norm = 0;
        for (double x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private static int bucket(String feature) {
        return Math.floorMod(feature.hashCode(), DIMENSIONS);
    }
}
