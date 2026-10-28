package ca.yorku.askdb.llm;

/** A provider could not answer. */
public class LLMException extends RuntimeException {
    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class RateLimited extends LLMException {
        public RateLimited(String message) {
            super(message);
        }
    }

    public static class Timeout extends LLMException {
        public Timeout(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class Unavailable extends LLMException {
        public Unavailable(String message) {
            super(message);
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
