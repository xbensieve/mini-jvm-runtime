package dev.ben.minijvm.exception;

/**
 * Thrown when a class file is structurally malformed, corrupted, truncated,
 * or contains invalid metadata.
 */
public class ClassFormatException extends MiniJvmException {
    public ClassFormatException(String message) {
        super(message);
    }

    public ClassFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
