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
        int operand
) {
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

    @Override
    public String toString() {
        return switch (opcode) {
            case BIPUSH, SIPUSH -> String.format("%d: %s %d", pc, opcode.mnemonic(), operand);
            case ILOAD, ISTORE -> String.format("%d: %s %d", pc, opcode.mnemonic(), operand);
            default -> String.format("%d: %s", pc, opcode.mnemonic());
        };
    }
}
