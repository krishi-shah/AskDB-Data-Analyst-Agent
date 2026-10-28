package ca.yorku.askdb.data;

/** Base class for dataset problems the user must see. */
public class DatasetException extends RuntimeException {
    public DatasetException(String message) {
        super(message);
    }

    public DatasetException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class UnsupportedFile extends DatasetException {
        public UnsupportedFile(String message) {
            super(message);
        }
    }

    public static class FileTooLarge extends DatasetException {
        public FileTooLarge(String message) {
            super(message);
        }
    }

    public static class ImportFailed extends DatasetException {
        public ImportFailed(String message) {
            super(message);
        }

        public ImportFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class QueryFailed extends DatasetException {
        public QueryFailed(String message) {
            super(message);
        }

        public QueryFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class QueryTimeout extends QueryFailed {
        public QueryTimeout(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
