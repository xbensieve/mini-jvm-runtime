package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;

import java.util.Optional;

/**
 * Pure bytecode decoder.
 * Reads an instruction at a given program counter without mutating execution state.
 */
public final class BytecodeDecoder {

    public Instruction decode(byte[] code, int pc) {
        if (code == null) {
            throw new ClassFormatException("Code array cannot be null");
        }
        if (pc < 0 || pc >= code.length) {
            throw new ClassFormatException(
                    String.format("Instruction fetch out of bounds: pc=%d in code of length %d", pc, code.length)
            );
        }

        int byteCode = code[pc] & 0xFF;
        Optional<Opcode> opcodeOpt = Opcode.findByCode(byteCode);

        if (opcodeOpt.isEmpty()) {
            if (isReservedOpcode(byteCode)) {
                throw new ClassFormatException(
                        String.format("Reserved opcode byte 0x%02X (%s) at PC %d is not permitted in valid class files",
                                byteCode, reservedOpcodeName(byteCode), pc)
                );
            } else if (isStandardJvmOpcode(byteCode)) {
                throw new UnsupportedFeatureException(
                        String.format("Unsupported standard JVM opcode byte 0x%02X at PC %d", byteCode, pc)
                );
            } else {
                throw new ClassFormatException(
                        String.format("Undefined opcode byte 0x%02X at PC %d", byteCode, pc)
                );
            }
        }

        Opcode opcode = opcodeOpt.get();
        int insLength = opcode.length();

        if (pc + insLength > code.length) {
            throw new ClassFormatException(
                    String.format("Truncated instruction %s at PC %d: requires %d bytes, only %d remaining",
                            opcode.mnemonic(), pc, insLength, code.length - pc)
            );
        }

        int operand = switch (opcode) {
            case NOP, ACONST_NULL -> 0;
            case ICONST_M1 -> -1;
            case ICONST_0 -> 0;
            case ICONST_1 -> 1;
            case ICONST_2 -> 2;
            case ICONST_3 -> 3;
            case ICONST_4 -> 4;
            case ICONST_5 -> 5;
            case BIPUSH -> (byte) code[pc + 1];
            case SIPUSH -> (short) (((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
            case LDC -> code[pc + 1] & 0xFF;
            case LDC_W, INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC,
                 GETSTATIC, PUTSTATIC, GETFIELD, PUTFIELD, NEW,
                 ANEWARRAY, MULTIANEWARRAY ->
                    ((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF);
            case NEWARRAY -> code[pc + 1] & 0xFF;
            case ARRAYLENGTH, ATHROW, IALOAD, AALOAD, BALOAD, CALOAD, SALOAD,
                 IASTORE, AASTORE, BASTORE, CASTORE, SASTORE -> 0;
            case ILOAD, ISTORE, ALOAD, ASTORE -> code[pc + 1] & 0xFF;
            case ILOAD_0, ISTORE_0, ALOAD_0, ASTORE_0 -> 0;
            case ILOAD_1, ISTORE_1, ALOAD_1, ASTORE_1 -> 1;
            case ILOAD_2, ISTORE_2, ALOAD_2, ASTORE_2 -> 2;
            case ILOAD_3, ISTORE_3, ALOAD_3, ASTORE_3 -> 3;
            case DUP, POP -> 0;
            case IADD, ISUB, IMUL, IDIV, IREM, INEG -> 0;
            case IINC -> code[pc + 1] & 0xFF;
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                 IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL,
                 GOTO -> (short) (((code[pc + 1] & 0xFF) << 8) | (code[pc + 2] & 0xFF));
            case IRETURN, ARETURN, RETURN -> 0;
        };

        int secondaryOperand = (opcode == Opcode.IINC) ? (byte) code[pc + 2]
                : (opcode == Opcode.MULTIANEWARRAY) ? (code[pc + 3] & 0xFF) : 0;

        return new Instruction(opcode, pc, insLength, operand, secondaryOperand);
    }

    public static boolean isReservedOpcode(int byteCode) {
        return byteCode == 0xCA || byteCode == 0xFE || byteCode == 0xFF;
    }

    public static String reservedOpcodeName(int byteCode) {
        return switch (byteCode) {
            case 0xCA -> "breakpoint";
            case 0xFE -> "impdep1";
            case 0xFF -> "impdep2";
            default -> "reserved";
        };
    }

    public static boolean isStandardJvmOpcode(int byteCode) {
        return byteCode >= 0x00 && byteCode <= 0xC9;
    }
}
