package dev.ben.minijvm.exception;

/**
 * Base unchecked exception for all host-side runtime errors in Mini JVM.
 */
public class MiniJvmException extends RuntimeException {
    public MiniJvmException(String message) {
        super(message);
    }

    public MiniJvmException(String message, Throwable cause) {
        super(message, cause);
    }
}
