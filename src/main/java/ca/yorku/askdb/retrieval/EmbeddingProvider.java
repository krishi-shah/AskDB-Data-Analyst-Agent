package ca.yorku.askdb.retrieval;

import java.util.List;

/** Turns texts into vectors for SchemaIndex. */
public interface EmbeddingProvider {
    List<double[]> embed(List<String> texts);

    String name();

    /** Thrown when no vectors can be produced; SchemaIndex then falls back to keyword search. */
    class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }
    }
}
