package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

/**
 * Represents a non-null guest reference pointing to an allocated guest heap identity (handle)
 * and carrying its runtime class identity per ADR-005 and ADR-011.
 * Strictly encapsulates guest identities without exposing host Java Class<?> instances.
 */
public record ObjectReference(long handle, String runtimeClassName) implements ReferenceValue {

    public static final String DEFAULT_CLASS_NAME = "java/lang/Object";

    public ObjectReference {
        if (handle == 0) {
            throw new StackFaultException("ObjectReference handle cannot be zero; use NullReference for null");
        }
        if (runtimeClassName == null || runtimeClassName.isBlank()) {
            throw new StackFaultException("ObjectReference runtimeClassName cannot be null or blank");
        }
    }

    public ObjectReference(long handle) {
        this(handle, DEFAULT_CLASS_NAME);
    }

    @Override
    public ValueType type() {
        return ValueType.REFERENCE;
    }

    @Override
    public boolean isNull() {
        return false;
    }

    @Override
    public String toString() {
        return "ObjectReference[@" + handle + ", class=" + runtimeClassName + "]";
    }
}
