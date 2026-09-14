package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

/**
 * Base sealed interface for all runtime values manipulated by operand stacks,
 * local variables, and execution frames in the Mini JVM.
 */
public sealed interface Value permits PrimitiveValue, ReferenceValue {

    ValueType type();

    /**
     * The number of JVM slots this value occupies in local variables or operand stack.
     * Category-1 values occupy 1 slot; Category-2 values (long, double) occupy 2 slots.
     */
    int slotWidth();

    default boolean isCategory2() {
        return slotWidth() == 2;
    }

    default boolean isReference() {
        return type() == ValueType.REFERENCE || type() == ValueType.NULL;
    }

    default boolean isNull() {
        return type() == ValueType.NULL;
    }

    default int asInt() {
        throw new StackFaultException("Value is not an int: " + this);
    }

    default long asLong() {
        throw new StackFaultException("Value is not a long: " + this);
    }

    default float asFloat() {
        throw new StackFaultException("Value is not a float: " + this);
    }

    default double asDouble() {
        throw new StackFaultException("Value is not a double: " + this);
    }

    default ReferenceValue asReference() {
        throw new StackFaultException("Value is not a reference: " + this);
    }

    static IntValue ofInt(int value) {
        return new IntValue(value);
    }

    static LongValue ofLong(long value) {
        return new LongValue(value);
    }

    static FloatValue ofFloat(float value) {
        return new FloatValue(value);
    }

    static DoubleValue ofDouble(double value) {
        return new DoubleValue(value);
    }

    static ObjectReference ofReference(long handle) {
        return new ObjectReference(handle);
    }

    static NullReference nullRef() {
        return NullReference.INSTANCE;
    }
}
