package dev.ben.minijvm.debug;

/**
 * Granularity of trace logging.
 */
public enum TraceLevel {
    /**
     * Emits instruction disassembly lines:
     * e.g. "0: iconst_1"
     */
    INSTRUCTIONS,

    /**
     * Emits instruction disassembly lines along with complete execution state snapshots
     * (call stack depth, frame status, operand stack, local variables).
     */
    DETAILED
}
