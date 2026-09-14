package dev.ben.minijvm.exception;

/**
 * Thrown when an operand stack, local variable array, frame, or call stack
 * violates structural or capacity invariants (e.g. underflow, overflow,
 * uninitialized variable access, or slot type mismatch).
 */
public class StackFaultException extends MiniJvmException {
    public StackFaultException(String message) {
        super(message);
    }

    public StackFaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
