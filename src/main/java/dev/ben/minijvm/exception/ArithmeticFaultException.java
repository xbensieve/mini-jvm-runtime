package dev.ben.minijvm.exception;

/**
 * Thrown when an arithmetic operation violates computational invariants,
 * such as integer division or remainder by zero (JVMS 6.5.idiv, 6.5.irem).
 */
public class ArithmeticFaultException extends MiniJvmException {
    public ArithmeticFaultException(String message) {
        super(message);
    }

    public ArithmeticFaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
