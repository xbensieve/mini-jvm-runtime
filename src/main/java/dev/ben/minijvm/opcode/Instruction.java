package dev.ben.minijvm.opcode;

import java.util.Objects;

/**
 * Immutable decoded instruction representation.
 * Encapsulates the opcode, its program counter location, byte length, and immediate operand (if any).
 */
public record Instruction(
        Opcode opcode,
        int pc,
        int length,
        int operand,
        int secondaryOperand
) {
    public Instruction(Opcode opcode, int pc, int length, int operand) {
        this(opcode, pc, length, operand, 0);
    }

    public Instruction {
        Objects.requireNonNull(opcode, "opcode cannot be null");
        if (pc < 0) {
            throw new IllegalArgumentException("pc cannot be negative: " + pc);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive: " + length);
        }
    }

    public String mnemonic() {
        return opcode.mnemonic();
    }

    public int branchOffset() {
        return operand;
    }

    public int localIndex() {
        return operand;
    }

    public int constantPoolIndex() {
        return operand;
    }

    public int incrementConst() {
        return secondaryOperand;
    }

    @Override
    public String toString() {
        return switch (opcode) {
            case BIPUSH, SIPUSH -> String.format("%d: %s %d", pc, opcode.mnemonic(), operand);
            case LDC, LDC_W -> String.format("%d: %s #%d", pc, opcode.mnemonic(), operand);
            case INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC -> String.format("%d: %s #%d", pc, opcode.mnemonic(), operand);
            case ILOAD, ISTORE -> String.format("%d: %s %d", pc, opcode.mnemonic(), operand);
            case IINC -> String.format("%d: %s %d by %d", pc, opcode.mnemonic(), operand, secondaryOperand);
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                 IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 GOTO -> String.format("%d: %s %+d -> %d", pc, opcode.mnemonic(), operand, pc + operand);
            default -> String.format("%d: %s", pc, opcode.mnemonic());
        };
    }
}
