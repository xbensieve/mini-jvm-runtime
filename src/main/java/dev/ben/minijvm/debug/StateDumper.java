package dev.ben.minijvm.debug;

import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Value;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic execution state and frame dumper.
 * Formats call stack depth, frame lifecycle status, method location,
 * operand stack contents, and populated local variables into reproducible string representations.
 */
public final class StateDumper {

    private StateDumper() {}

    /**
     * Dumps the execution state of the specified frame within an optional call stack.
     *
     * @param frame      The active execution frame.
     * @param frameStack The active call stack (may be null).
     * @return Deterministically formatted state string.
     */
    public static String dump(Frame frame, FrameStack frameStack) {
        return dump(ExecutionSnapshot.capture(frame, frameStack));
    }

    /**
     * Convenience dump for a standalone frame without a call stack.
     */
    public static String dump(Frame frame) {
        return dump(frame, null);
    }

    /**
     * Dumps the specified execution snapshot.
     *
     * @param snapshot The execution snapshot to format.
     * @return Deterministically formatted state string.
     */
    public static String dump(ExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        StringBuilder sb = new StringBuilder();
        sb.append("FrameStack depth: ").append(snapshot.frameStackDepth()).append("\n");
        sb.append("Frame status: ").append(snapshot.frameStatus()).append("\n");
        sb.append("Method: ").append(snapshot.className()).append(".")
                .append(snapshot.methodName()).append(snapshot.methodDescriptor())
                .append(" [pc=").append(snapshot.pc()).append("]\n");

        sb.append("OperandStack (")
                .append(snapshot.operandStackSlots()).append("/")
                .append(snapshot.maxStack()).append(" slots): ")
                .append(formatOperandStack(snapshot.operandStack()))
                .append("\n");

        sb.append("LocalVariables (")
                .append(snapshot.localVariables().size()).append("/")
                .append(snapshot.maxLocals()).append(" populated): ")
                .append(formatLocalVariables(snapshot.localVariables()));

        snapshot.returnValue().ifPresent(val ->
                sb.append("\nReturnValue: ").append(val)
        );

        return sb.toString();
    }

    /**
     * Compact single-line summary of execution state.
     */
    public static String dumpCompact(ExecutionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        StringBuilder sb = new StringBuilder();
        sb.append("[depth=").append(snapshot.frameStackDepth())
                .append(", status=").append(snapshot.frameStatus())
                .append(", pc=").append(snapshot.pc())
                .append(", stack=").append(formatOperandStack(snapshot.operandStack()))
                .append(", locals=").append(formatLocalVariables(snapshot.localVariables()))
                .append("]");
        return sb.toString();
    }

    public static String formatOperandStack(List<Value> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i).toString());
        }
        sb.append("]");
        return sb.toString();
    }

    public static String formatLocalVariables(Map<Integer, Value> locals) {
        if (locals == null || locals.isEmpty()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (Map.Entry<Integer, Value> entry : locals.entrySet()) {
            if (count++ > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue().toString());
        }
        sb.append("]");
        return sb.toString();
    }
}
