package dev.ben.minijvm.runtime;

/**
 * Lifecycle execution status of a Frame (JVMS 2.6).
 * Explicitly distinguishes active execution, normal return,
 * reaching end-of-code without return, and execution faults.
 */
public enum FrameStatus {
    /**
     * Frame is active and eligible for instruction stepping.
     */
    RUNNING,

    /**
     * Frame completed normally via an explicit return opcode (return, ireturn).
     */
    RETURNED,

    /**
     * Frame execution reached the end of method code (PC == codeLength)
     * without an explicit return opcode.
     */
    COMPLETED_AT_END,

    /**
     * Frame execution terminated abruptly due to an unhandled runtime fault.
     */
    FAILED
}
