package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

/**
 * Represents a non-null guest reference pointing to an allocated guest heap identity (handle).
 * Strictly encapsulates the handle ID per ADR-005.
 */
public record ObjectReference(long handle) implements ReferenceValue {
    public ObjectReference {
        if (handle == 0) {
            throw new StackFaultException("ObjectReference handle cannot be zero; use NullReference for null");
        }
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
        return "ObjectReference[@" + handle + "]";
    }
}
