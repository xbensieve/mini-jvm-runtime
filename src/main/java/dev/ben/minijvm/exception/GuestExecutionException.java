package dev.ben.minijvm.exception;

import dev.ben.minijvm.runtime.ObjectReference;

/**
 * Thrown when a guest exception is raised (via athrow or fault) and unwinds past
 * the root frame of the call stack without encountering a matching exception handler.
 */
public class GuestExecutionException extends MiniJvmException {

    private final ObjectReference guestException;
    private final String exceptionClassName;

    public GuestExecutionException(ObjectReference guestException, String message) {
        super(message);
        this.guestException = guestException;
        this.exceptionClassName = (guestException != null) ? guestException.runtimeClassName() : "unknown";
    }

    public GuestExecutionException(ObjectReference guestException, String message, Throwable cause) {
        super(message, cause);
        this.guestException = guestException;
        this.exceptionClassName = (guestException != null) ? guestException.runtimeClassName() : "unknown";
    }

    public ObjectReference guestException() {
        return guestException;
    }

    public String exceptionClassName() {
        return exceptionClassName;
    }
}
