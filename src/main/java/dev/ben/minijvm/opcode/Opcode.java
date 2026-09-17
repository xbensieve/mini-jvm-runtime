package dev.ben.minijvm.opcode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enumeration of JVM opcodes supported in Phase 03 through Phase 07
 * (85 concrete opcode values: 27 in Phase 03, 16 in Phase 04, 5 in Phase 05, 32 in Phase 06, 5 in Phase 07).
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
    ALOAD(0x19, "aload", 2),
    ILOAD_0(0x1A, "iload_0", 1),
    ILOAD_1(0x1B, "iload_1", 1),
    ILOAD_2(0x1C, "iload_2", 1),
    ILOAD_3(0x1D, "iload_3", 1),
    ALOAD_0(0x2A, "aload_0", 1),
    ALOAD_1(0x2B, "aload_1", 1),
    ALOAD_2(0x2C, "aload_2", 1),
    ALOAD_3(0x2D, "aload_3", 1),
    ISTORE(0x36, "istore", 2),
    ASTORE(0x3A, "astore", 2),
    ISTORE_0(0x3B, "istore_0", 1),
    ISTORE_1(0x3C, "istore_1", 1),
    ISTORE_2(0x3D, "istore_2", 1),
    ISTORE_3(0x3E, "istore_3", 1),
    ASTORE_0(0x4B, "astore_0", 1),
    ASTORE_1(0x4C, "astore_1", 1),
    ASTORE_2(0x4D, "astore_2", 1),
    ASTORE_3(0x4E, "astore_3", 1),
    POP(0x57, "pop", 1),
    DUP(0x59, "dup", 1),
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
    ARETURN(0xB0, "areturn", 1),
    RETURN(0xB1, "return", 1),
    GETSTATIC(0xB2, "getstatic", 3),
    PUTSTATIC(0xB3, "putstatic", 3),
    GETFIELD(0xB4, "getfield", 3),
    PUTFIELD(0xB5, "putfield", 3),
    INVOKEVIRTUAL(0xB6, "invokevirtual", 3),
    INVOKESPECIAL(0xB7, "invokespecial", 3),
    INVOKESTATIC(0xB8, "invokestatic", 3),
    IALOAD(0x2E, "iaload", 1),
    AALOAD(0x32, "aaload", 1),
    BALOAD(0x33, "baload", 1),
    CALOAD(0x34, "caload", 1),
    SALOAD(0x35, "saload", 1),
    IASTORE(0x4F, "iastore", 1),
    AASTORE(0x53, "aastore", 1),
    BASTORE(0x54, "bastore", 1),
    CASTORE(0x55, "castore", 1),
    SASTORE(0x56, "sastore", 1),
    IF_ACMPEQ(0xA5, "if_acmpeq", 3),
    IF_ACMPNE(0xA6, "if_acmpne", 3),
    NEW(0xBB, "new", 3),
    NEWARRAY(0xBC, "newarray", 2),
    ANEWARRAY(0xBD, "anewarray", 3),
    ARRAYLENGTH(0xBE, "arraylength", 1),
    ATHROW(0xBF, "athrow", 1),
    MULTIANEWARRAY(0xC5, "multianewarray", 4),
    IFNULL(0xC6, "ifnull", 3),
    IFNONNULL(0xC7, "ifnonnull", 3);

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
                 IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL,
                 GOTO -> true;
            default -> false;
        };
    }

    public boolean isConditionalBranch() {
        return switch (this) {
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE,
                 IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE,
                 IF_ACMPEQ, IF_ACMPNE, IFNULL, IFNONNULL -> true;
            default -> false;
        };
    }

    public boolean isReferenceBranch() {
        return this == IF_ACMPEQ || this == IF_ACMPNE || this == IFNULL || this == IFNONNULL;
    }

    public boolean isException() {
        return this == ATHROW;
    }

    public boolean isReturn() {
        return this == RETURN || this == IRETURN || this == ARETURN;
    }

    public boolean isInvocation() {
        return this == INVOKEVIRTUAL || this == INVOKESPECIAL || this == INVOKESTATIC;
    }

    public boolean isFieldAccess() {
        return this == GETFIELD || this == PUTFIELD || this == GETSTATIC || this == PUTSTATIC;
    }

    public boolean isArrayAccess() {
        return this == IALOAD || this == AALOAD || this == BALOAD || this == CALOAD || this == SALOAD
                || this == IASTORE || this == AASTORE || this == BASTORE || this == CASTORE || this == SASTORE
                || this == NEWARRAY || this == ANEWARRAY || this == ARRAYLENGTH || this == MULTIANEWARRAY;
    }

    public boolean isConstantPoolAccess() {
        return this == LDC || this == LDC_W || isInvocation() || isFieldAccess() || this == NEW
                || this == ANEWARRAY || this == MULTIANEWARRAY;
    }
}
