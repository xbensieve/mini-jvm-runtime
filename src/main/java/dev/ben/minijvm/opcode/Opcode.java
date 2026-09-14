package dev.ben.minijvm.opcode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enumeration of JVM opcodes supported in Phase 03.
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
    INEG(0x74, "ineg", 1);

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
}
