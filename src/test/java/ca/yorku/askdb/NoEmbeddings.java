package ca.yorku.askdb;

import ca.yorku.askdb.retrieval.EmbeddingProvider;
import java.util.List;

/** Stand-in embedder that forces keyword search. */
public class NoEmbeddings implements EmbeddingProvider {
    @Override
    public List<double[]> embed(List<String> texts) {
        throw new EmbeddingException("disabled in tests");
    }

    @Override
    public String name() {
        return "none";
    }
}
