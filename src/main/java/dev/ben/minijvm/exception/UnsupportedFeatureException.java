package dev.ben.minijvm.exception;

/**
 * Thrown when encountering a valid-looking but unsupported JVM feature,
 * class-file version, attribute, or opcode outside the educational scope.
 */
public class UnsupportedFeatureException extends MiniJvmException {
    public UnsupportedFeatureException(String message) {
        super(message);
    }

    public UnsupportedFeatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
