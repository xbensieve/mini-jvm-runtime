package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.exception.ArithmeticFaultException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Value;

import java.util.Objects;

/**
 * Execution engine for JVM bytecode.
 * Coordinates instruction fetch, decoder invocation, deterministic PC advancement,
 * branch target calculation, return handling, and opcode dispatch onto frame state.
 */
public final class Interpreter {

    private final BytecodeDecoder decoder;

    public Interpreter() {
        this(new BytecodeDecoder());
    }

    public Interpreter(BytecodeDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder cannot be null");
    }

    public BytecodeDecoder decoder() {
        return decoder;
    }

    /**
     * Executes a single instruction at the frame's current PC.
     * Advances the frame's PC by the instruction length before executing the instruction.
     *
     * @param frame The active execution frame.
     * @return The decoded instruction that was executed.
     */
    public Instruction step(Frame frame) {
        return step(frame, null);
    }

    /**
     * Executes a single instruction at the frame's current PC within an optional FrameStack context.
     *
     * @param frame      The active execution frame.
     * @param frameStack The active call stack, or null if executing a standalone frame.
     * @return The decoded instruction that was executed.
     */
    public Instruction step(Frame frame, FrameStack frameStack) {
        Objects.requireNonNull(frame, "frame cannot be null");

        if (frame.isCompleted()) {
            throw new StackFaultException("Cannot step frame: frame has already completed execution");
        }

        CodeAttribute codeAttr = frame.method().code().orElseThrow(() ->
                new StackFaultException("Cannot execute frame for method without Code attribute")
        );
        byte[] code = codeAttr.code();

        if (frame.pc() >= code.length) {
            throw new StackFaultException(
                    String.format("Cannot step frame: PC %d is at or beyond code length %d", frame.pc(), code.length)
            );
        }

        // 1. Fetch & decode instruction at current PC
        Instruction ins = decoder.decode(code, frame.pc());

        // 2. Record instruction start PC and advance PC to next sequential instruction
        frame.setLastInstructionPc(ins.pc());
        frame.advancePc(ins.length());

        // 3. Dispatch opcode execution
        executeInstruction(ins, frame, frameStack, code.length);

        return ins;
    }

    /**
     * Runs the frame sequentially until its PC reaches the end of the method's code array
     * or the frame completes.
     */
    public void execute(Frame frame) {
        Objects.requireNonNull(frame, "frame cannot be null");

        CodeAttribute codeAttr = frame.method().code().orElseThrow(() ->
                new StackFaultException("Cannot execute frame for method without Code attribute")
        );
        int codeLength = codeAttr.codeLength();

        while (!frame.isCompleted() && frame.pc() < codeLength) {
            step(frame, null);
        }
    }

    /**
     * Runs execution across a FrameStack until all frames have returned and the call stack is empty.
     */
    public void execute(FrameStack frameStack) {
        Objects.requireNonNull(frameStack, "frameStack cannot be null");
        while (!frameStack.isEmpty()) {
            Frame current = frameStack.current();
            if (current.isCompleted()) {
                frameStack.pop();
                continue;
            }
            step(current, frameStack);
        }
    }

    private void executeInstruction(Instruction ins, Frame frame, FrameStack frameStack, int codeLength) {
        switch (ins.opcode()) {
            case NOP -> {
                // Do nothing
            }
            case ACONST_NULL -> {
                frame.operandStack().push(Value.nullRef());
            }
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> {
                frame.operandStack().push(Value.ofInt(ins.operand()));
            }
            case BIPUSH, SIPUSH -> {
                frame.operandStack().push(Value.ofInt(ins.operand()));
            }
            case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> {
                int localIndex = ins.operand();
                int val = frame.locals().getInt(localIndex);
                frame.operandStack().push(Value.ofInt(val));
            }
            case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3 -> {
                int localIndex = ins.operand();
                int val = frame.operandStack().popInt();
                frame.locals().setInt(localIndex, val);
            }
            case IADD -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(val1 + val2));
            }
            case ISUB -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(val1 - val2));
            }
            case IMUL -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(val1 * val2));
            }
            case IDIV -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val2 == 0) {
                    throw new ArithmeticFaultException("/ by zero");
                }
                // JVMS 6.5.idiv: Integer.MIN_VALUE / -1 overflows to Integer.MIN_VALUE
                if (val1 == Integer.MIN_VALUE && val2 == -1) {
                    frame.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
                } else {
                    frame.operandStack().push(Value.ofInt(val1 / val2));
                }
            }
            case IREM -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val2 == 0) {
                    throw new ArithmeticFaultException("/ by zero");
                }
                // JVMS 6.5.irem: Integer.MIN_VALUE % -1 equals 0
                if (val1 == Integer.MIN_VALUE && val2 == -1) {
                    frame.operandStack().push(Value.ofInt(0));
                } else {
                    frame.operandStack().push(Value.ofInt(val1 % val2));
                }
            }
            case INEG -> {
                int val = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(-val));
            }
            case IINC -> {
                int localIndex = ins.localIndex();
                int constVal = ins.incrementConst();
                int currentVal = frame.locals().getInt(localIndex);
                frame.locals().setInt(localIndex, currentVal + constVal);
            }
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                int val = frame.operandStack().popInt();
                boolean condition = switch (ins.opcode()) {
                    case IFEQ -> val == 0;
                    case IFNE -> val != 0;
                    case IFLT -> val < 0;
                    case IFGE -> val >= 0;
                    case IFGT -> val > 0;
                    case IFLE -> val <= 0;
                    default -> throw new AssertionError();
                };
                if (condition) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                boolean condition = switch (ins.opcode()) {
                    case IF_ICMPEQ -> val1 == val2;
                    case IF_ICMPNE -> val1 != val2;
                    case IF_ICMPLT -> val1 < val2;
                    case IF_ICMPGE -> val1 >= val2;
                    case IF_ICMPGT -> val1 > val2;
                    case IF_ICMPLE -> val1 <= val2;
                    default -> throw new AssertionError();
                };
                if (condition) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case GOTO -> {
                jumpTo(frame, ins, codeLength);
            }
            case RETURN -> {
                String desc = frame.method().descriptor(frame.constantPool());
                if (!desc.endsWith("V")) {
                    throw new StackFaultException(
                            String.format("return opcode executed in method '%s' with non-void return descriptor '%s'",
                                    frame.method().name(frame.constantPool()), desc)
                    );
                }
                frame.markCompleted();
                if (frameStack != null) {
                    if (frameStack.isEmpty() || frameStack.current() != frame) {
                        throw new StackFaultException("FrameStack mismatch on return: active frame is not top of call stack");
                    }
                    frameStack.pop();
                }
            }
            case IRETURN -> {
                int returnVal = frame.operandStack().popInt();
                String desc = frame.method().descriptor(frame.constantPool());
                char retType = desc.charAt(desc.length() - 1);
                if (retType != 'I' && retType != 'Z' && retType != 'B' && retType != 'C' && retType != 'S') {
                    throw new StackFaultException(
                            String.format("ireturn opcode executed in method '%s' with incompatible return descriptor '%s'",
                                    frame.method().name(frame.constantPool()), desc)
                    );
                }
                frame.markCompleted();
                frame.setReturnValue(Value.ofInt(returnVal));
                if (frameStack != null) {
                    if (frameStack.isEmpty() || frameStack.current() != frame) {
                        throw new StackFaultException("FrameStack mismatch on ireturn: active frame is not top of call stack");
                    }
                    frameStack.pop();
                    if (!frameStack.isEmpty()) {
                        frameStack.current().operandStack().push(Value.ofInt(returnVal));
                    }
                }
            }
        }
    }

    private void jumpTo(Frame frame, Instruction ins, int codeLength) {
        int target = ins.pc() + ins.branchOffset();
        if (target < 0 || target >= codeLength) {
            throw new StackFaultException(
                    String.format("Branch target %d out of bounds (instruction PC %d, offset %+d, code length %d)",
                            target, ins.pc(), ins.branchOffset(), codeLength)
            );
        }
        frame.setPc(target);
    }
}
