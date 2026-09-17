package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.ClassFormatException;

/**
 * Enumeration of primitive array types used by the newarray instruction (JVMS §6.5).
 */
public enum ArrayType {
    T_BOOLEAN(4, "[Z", "Z"),
    T_CHAR(5, "[C", "C"),
    T_FLOAT(6, "[F", "F"),
    T_DOUBLE(7, "[D", "D"),
    T_BYTE(8, "[B", "B"),
    T_SHORT(9, "[S", "S"),
    T_INT(10, "[I", "I"),
    T_LONG(11, "[J", "J");

    private final int atype;
    private final String arrayDescriptor;
    private final String componentDescriptor;

    ArrayType(int atype, String arrayDescriptor, String componentDescriptor) {
        this.atype = atype;
        this.arrayDescriptor = arrayDescriptor;
        this.componentDescriptor = componentDescriptor;
    }

    public int atype() {
        return atype;
    }

    public String arrayDescriptor() {
        return arrayDescriptor;
    }

    public String componentDescriptor() {
        return componentDescriptor;
    }

    public static ArrayType fromAtype(int atype) {
        for (ArrayType type : values()) {
            if (type.atype == atype) {
                return type;
            }
        }
        throw new ClassFormatException(
                String.format("Invalid atype %d for newarray (valid types are 4..11)", atype)
        );
    }
}
