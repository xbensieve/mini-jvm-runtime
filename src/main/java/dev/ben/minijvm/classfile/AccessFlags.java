package dev.ben.minijvm.classfile;

/**
 * Common JVM access and property flags for classes, fields, and methods.
 */
public final class AccessFlags {
    private AccessFlags() {}

    // Common flags
    public static final int ACC_PUBLIC       = 0x0001;
    public static final int ACC_PRIVATE      = 0x0002;
    public static final int ACC_PROTECTED    = 0x0004;
    public static final int ACC_STATIC       = 0x0008;
    public static final int ACC_FINAL        = 0x0010;
    public static final int ACC_SYNTHETIC    = 0x1000;

    // Class specific
    public static final int ACC_SUPER        = 0x0020;
    public static final int ACC_INTERFACE    = 0x0200;
    public static final int ACC_ABSTRACT     = 0x0400;
    public static final int ACC_ANNOTATION   = 0x2000;
    public static final int ACC_ENUM         = 0x4000;
    public static final int ACC_MODULE       = 0x8000;

    // Field specific
    public static final int ACC_VOLATILE     = 0x0040;
    public static final int ACC_TRANSIENT    = 0x0080;

    // Method specific
    public static final int ACC_SYNCHRONIZED = 0x0020;
    public static final int ACC_BRIDGE       = 0x0040;
    public static final int ACC_VARARGS      = 0x0080;
    public static final int ACC_NATIVE       = 0x0100;
    public static final int ACC_STRICT       = 0x0800;

    public static boolean isPublic(int flags) {
        return (flags & ACC_PUBLIC) != 0;
    }

    public static boolean isPrivate(int flags) {
        return (flags & ACC_PRIVATE) != 0;
    }

    public static boolean isProtected(int flags) {
        return (flags & ACC_PROTECTED) != 0;
    }

    public static boolean isStatic(int flags) {
        return (flags & ACC_STATIC) != 0;
    }

    public static boolean isFinal(int flags) {
        return (flags & ACC_FINAL) != 0;
    }

    public static boolean isInterface(int flags) {
        return (flags & ACC_INTERFACE) != 0;
    }

    public static boolean isAbstract(int flags) {
        return (flags & ACC_ABSTRACT) != 0;
    }

    public static boolean isNative(int flags) {
        return (flags & ACC_NATIVE) != 0;
    }
}
