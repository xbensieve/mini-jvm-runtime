package dev.ben.minijvm.exception;

/**
 * Thrown when runtime resolution or linkage of a class, method, or field fails.
 */
public class LinkageException extends MiniJvmException {

    public LinkageException(String message) {
        super(message);
    }

    public LinkageException(String message, Throwable cause) {
        super(message, cause);
    }
}
