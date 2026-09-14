package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.exception.ArithmeticFaultException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.Value;

import java.util.Objects;

/**
 * Execution engine for JVM bytecode.
 * Coordinates instruction fetch, decoder invocation, deterministic PC advancement,
 * and opcode dispatch onto frame state.
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
        Objects.requireNonNull(frame, "frame cannot be null");

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

        // 2. Advance PC to next instruction
        frame.advancePc(ins.length());

        // 3. Dispatch opcode execution
        executeInstruction(ins, frame);

        return ins;
    }

    /**
     * Runs the frame sequentially until its PC reaches the end of the method's code array.
     */
    public void execute(Frame frame) {
        Objects.requireNonNull(frame, "frame cannot be null");

        CodeAttribute codeAttr = frame.method().code().orElseThrow(() ->
                new StackFaultException("Cannot execute frame for method without Code attribute")
        );
        int codeLength = codeAttr.codeLength();

        while (frame.pc() < codeLength) {
            step(frame);
        }
    }

    private void executeInstruction(Instruction ins, Frame frame) {
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
        }
    }
}
