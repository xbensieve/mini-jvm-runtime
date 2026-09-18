package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;

/**
 * Lifecycle and execution listener for the Mini JVM interpreter.
 * Receives callbacks before and after instruction dispatch, on frame pushes/pops,
 * and upon execution faults.
 */
public interface InterpreterListener {

    /**
     * Invoked immediately before an instruction is executed.
     *
     * @param frame      The active execution frame.
     * @param ins        The decoded instruction to be executed.
     * @param frameStack The active call stack (may be null if executing a standalone frame).
     */
    default void beforeInstruction(Frame frame, Instruction ins, FrameStack frameStack) {}

    /**
     * Invoked immediately after an instruction is executed successfully.
     *
     * @param frame      The execution frame that ran the instruction.
     * @param ins        The decoded instruction that was executed.
     * @param frameStack The active call stack (may be null if executing a standalone frame).
     */
    default void afterInstruction(Frame frame, Instruction ins, FrameStack frameStack) {}

    /**
     * Invoked when a new frame is pushed onto the call stack (e.g. method invocation).
     *
     * @param frame      The newly pushed callee frame.
     * @param frameStack The active call stack.
     */
    default void onFramePushed(Frame frame, FrameStack frameStack) {}

    /**
     * Invoked when a frame is popped from the call stack (e.g. method return or unwinding).
     *
     * @param frame      The frame that was popped.
     * @param frameStack The active call stack.
     */
    default void onFramePopped(Frame frame, FrameStack frameStack) {}

    /**
     * Invoked when instruction execution or frame dispatch terminates abruptly with a fault.
     *
     * @param frame      The faulting execution frame.
     * @param ins        The faulting instruction (if decoded).
     * @param fault      The exception or error that caused execution to terminate.
     * @param frameStack The active call stack (may be null).
     */
    default void onFault(Frame frame, Instruction ins, Throwable fault, FrameStack frameStack) {}
}
