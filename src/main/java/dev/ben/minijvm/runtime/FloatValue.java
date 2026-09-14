package dev.ben.minijvm.runtime;

/**
 * Represents a 32-bit IEEE 754 floating-point value (Category-1, 1 slot).
 */
public record FloatValue(float value) implements PrimitiveValue {
    @Override
    public ValueType type() {
        return ValueType.FLOAT;
    }

    @Override
    public int slotWidth() {
        return 1;
    }

    @Override
    public float asFloat() {
        return value;
    }

    @Override
    public String toString() {
        return "FloatValue[" + value + "f]";
    }
}
