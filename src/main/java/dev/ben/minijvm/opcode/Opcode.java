package dev.ben.minijvm.opcode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enumeration of JVM opcodes supported in Phase 03, Phase 04, and Phase 05
 * (48 concrete opcode values: 27 in Phase 03, 16 in Phase 04, 5 in Phase 05).
 */
public enum Opcode {
    NOP(0x00, "nop", 1),
    ACONST_NULL(0x01, "aconst_null", 1),
    ICONST_M1(0x02, "iconst_m1", 1),
    ICONST_0(0x03, "iconst_0", 1),
    ICONST_1(0x04, "iconst_1", 1),
    ICONST_2(0x05, "iconst_2", 1),
    ICONST_3(0x06, "iconst_3", 1),
    ICONST_4(0x07, "iconst_4", 1),
    ICONST_5(0x08, "iconst_5", 1),
    BIPUSH(0x10, "bipush", 2),
    SIPUSH(0x11, "sipush", 3),
    LDC(0x12, "ldc", 2),
    LDC_W(0x13, "ldc_w", 3),
    ILOAD(0x15, "iload", 2),
    ILOAD_0(0x1A, "iload_0", 1),
    ILOAD_1(0x1B, "iload_1", 1),
    ILOAD_2(0x1C, "iload_2", 1),
    ILOAD_3(0x1D, "iload_3", 1),
    ISTORE(0x36, "istore", 2),
    ISTORE_0(0x3B, "istore_0", 1),
    ISTORE_1(0x3C, "istore_1", 1),
    ISTORE_2(0x3D, "istore_2", 1),
    ISTORE_3(0x3E, "istore_3", 1),
    IADD(0x60, "iadd", 1),
    ISUB(0x64, "isub", 1),
    IMUL(0x68, "imul", 1),
    IDIV(0x6C, "idiv", 1),
    IREM(0x70, "irem", 1),
    INEG(0x74, "ineg", 1),
    IINC(0x84, "iinc", 3),
    IFEQ(0x99, "ifeq", 3),
    IFNE(0x9A, "ifne", 3),
    IFLT(0x9B, "iflt", 3),
    IFGE(0x9C, "ifge", 3),
    IFGT(0x9D, "ifgt", 3),
    IFLE(0x9E, "ifle", 3),
    IF_ICMPEQ(0x9F, "if_icmpeq", 3),
    IF_ICMPNE(0xA0, "if_icmpne", 3),
    IF_ICMPLT(0xA1, "if_icmplt", 3),
    IF_ICMPGE(0xA2, "if_icmpge", 3),
    IF_ICMPGT(0xA3, "if_icmpgt", 3),
    IF_ICMPLE(0xA4, "if_icmple", 3),
    GOTO(0xA7, "goto", 3),
    IRETURN(0xAC, "ireturn", 1),
    RETURN(0xB1, "return", 1),
    INVOKEVIRTUAL(0xB6, "invokevirtual", 3),
    INVOKESPECIAL(0xB7, "invokespecial", 3),
    INVOKESTATIC(0xB8, "invokestatic", 3);

    private final int code;
    private final String mnemonic;
    private final int length;

    private static final Map<Integer, Opcode> BY_CODE = new HashMap<>();

    static {
        for (Opcode op : values()) {
            BY_CODE.put(op.code, op);
        }
    }

    Opcode(int code, String mnemonic, int length) {
        this.code = code;
        this.mnemonic = mnemonic;
        this.length = length;
    }

    public int code() {
        return code;
    }

    public String mnemonic() {
        return mnemonic;
    }

    public int length() {
        return length;
    }

    public static Optional<Opcode> findByCode(int byteCode) {
        return Optional.ofNullable(BY_CODE.get(byteCode & 0xFF));
    }

    public boolean isBranch() {
        return switch (this) {
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                 IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 GOTO -> true;
            default -> false;
        };
    }

    public boolean isConditionalBranch() {
        return switch (this) {
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                 IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> true;
            default -> false;
        };
    }

    public boolean isReturn() {
        return this == RETURN || this == IRETURN;
    }

    public boolean isInvocation() {
        return this == INVOKEVIRTUAL || this == INVOKESPECIAL || this == INVOKESTATIC;
    }

    public boolean isConstantPoolAccess() {
        return this == LDC || this == LDC_W || isInvocation();
    }
}
