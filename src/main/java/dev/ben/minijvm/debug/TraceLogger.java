package dev.ben.minijvm.debug;

import dev.ben.minijvm.interpreter.InterpreterListener;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deterministic execution trace logger implementing {@link InterpreterListener}.
 * Records instruction execution and optional frame/stack state snapshots into
 * reproducible trace outputs suitable for golden-master testing and interactive debugging.
 */
public final class TraceLogger implements InterpreterListener {

    private final TraceLevel level;
    private final Consumer<String> lineConsumer;
    private final List<String> lines = new ArrayList<>();

    public TraceLogger() {
        this(TraceLevel.INSTRUCTIONS);
    }

    public TraceLogger(TraceLevel level) {
        this(level, (Consumer<String>) null);
    }

    public TraceLogger(TraceLevel level, PrintStream out) {
        this(level, out != null ? out::println : null);
    }

    public TraceLogger(TraceLevel level, Consumer<String> lineConsumer) {
        this.level = Objects.requireNonNull(level, "level cannot be null");
        this.lineConsumer = lineConsumer;
    }

    public TraceLevel level() {
        return level;
    }

    @Override
    public void beforeInstruction(Frame frame, Instruction ins, FrameStack frameStack) {
        emit(ins.toString());
    }

    @Override
    public void afterInstruction(Frame frame, Instruction ins, FrameStack frameStack) {
        if (level == TraceLevel.DETAILED) {
            ExecutionSnapshot snapshot = ExecutionSnapshot.capture(frame, frameStack);
            String dump = StateDumper.dump(snapshot);
            for (String line : dump.split("\n")) {
                emit("  " + line);
            }
        }
    }

    @Override
    public void onFramePushed(Frame frame, FrameStack frameStack) {
        if (level == TraceLevel.DETAILED) {
            String methodName = frame.method().name(frame.constantPool());
            int depth = (frameStack != null) ? frameStack.depth() : 1;
            emit(String.format("  [Call Stack] PUSH -> %s (depth %d)", methodName, depth));
        }
    }

    @Override
    public void onFramePopped(Frame frame, FrameStack frameStack) {
        if (level == TraceLevel.DETAILED) {
            String methodName = frame.method().name(frame.constantPool());
            int depth = (frameStack != null) ? frameStack.depth() : 0;
            emit(String.format("  [Call Stack] POP <- %s (depth %d)", methodName, depth));
        }
    }

    @Override
    public void onFault(Frame frame, Instruction ins, Throwable fault, FrameStack frameStack) {
        emit(String.format("  [Execution Fault] PC %d (%s) failed: %s: %s",
                ins != null ? ins.pc() : frame.lastInstructionPc(),
                ins != null ? ins.mnemonic() : "?",
                fault.getClass().getSimpleName(),
                fault.getMessage() != null ? fault.getMessage() : ""));
    }

    private void emit(String line) {
        lines.add(line);
        if (lineConsumer != null) {
            lineConsumer.accept(line);
        }
    }

    /**
     * Returns an unmodifiable list of all trace lines emitted so far.
     */
    public List<String> lines() {
        return Collections.unmodifiableList(lines);
    }

    /**
     * Formats all collected trace lines into a single deterministic string,
     * normalized with newline endings.
     */
    public String toTraceString() {
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Clears all collected trace lines.
     */
    public void clear() {
        lines.clear();
    }
}
