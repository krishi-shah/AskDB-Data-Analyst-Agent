package ca.yorku.askdb.agent;

/** The model reply could not be turned into the expected object. */
public class ParseException extends RuntimeException {
    public ParseException(String message) {
        super(message);
    }
}
