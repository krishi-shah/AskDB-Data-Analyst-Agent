package ca.yorku.askdb.storage;

/** The application database could not be read or written. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
