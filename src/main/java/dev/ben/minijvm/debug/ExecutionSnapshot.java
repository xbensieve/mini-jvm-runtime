package dev.ben.minijvm.debug;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.FrameStatus;
import dev.ben.minijvm.runtime.Value;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot capturing the complete execution state of a thread / call stack
 * at a discrete point in time.
 */
public record ExecutionSnapshot(
        int frameStackDepth,
        FrameStatus frameStatus,
        String className,
        String methodName,
        String methodDescriptor,
        int pc,
        int lastInstructionPc,
        List<Value> operandStack,
        int operandStackSlots,
        int maxStack,
        Map<Integer, Value> localVariables,
        int maxLocals,
        Optional<Value> returnValue
) {
    public ExecutionSnapshot {
        Objects.requireNonNull(frameStatus, "frameStatus cannot be null");
        Objects.requireNonNull(className, "className cannot be null");
        Objects.requireNonNull(methodName, "methodName cannot be null");
        Objects.requireNonNull(methodDescriptor, "methodDescriptor cannot be null");
        Objects.requireNonNull(operandStack, "operandStack cannot be null");
        Objects.requireNonNull(localVariables, "localVariables cannot be null");
        Objects.requireNonNull(returnValue, "returnValue cannot be null");
    }

    /**
     * Captures an immutable snapshot from the specified frame and optional call stack.
     *
     * @param frame      The active execution frame.
     * @param frameStack The active call stack (may be null).
     * @return An immutable ExecutionSnapshot.
     */
    public static ExecutionSnapshot capture(Frame frame, FrameStack frameStack) {
        Objects.requireNonNull(frame, "frame cannot be null");

        int depth = (frameStack != null) ? frameStack.depth() : 1;
        String className = frame.classFile().map(ClassFile::thisClassName).orElse("<unknown>");
        String methodName = frame.method().name(frame.constantPool());
        String methodDescriptor = frame.method().descriptor(frame.constantPool());

        return new ExecutionSnapshot(
                depth,
                frame.status(),
                className,
                methodName,
                methodDescriptor,
                frame.pc(),
                frame.lastInstructionPc(),
                frame.operandStack().toList(),
                frame.operandStack().slots(),
                frame.operandStack().maxSlots(),
                frame.locals().populatedSlots(),
                frame.locals().capacity(),
                frame.returnValue()
        );
    }
}
