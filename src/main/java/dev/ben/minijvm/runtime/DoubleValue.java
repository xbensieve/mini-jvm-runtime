package dev.ben.minijvm.runtime;

/**
 * Represents a 64-bit IEEE 754 floating-point value (Category-2, 2 slots).
 */
public record DoubleValue(double value) implements PrimitiveValue {
    @Override
    public ValueType type() {
        return ValueType.DOUBLE;
    }

    @Override
    public int slotWidth() {
        return 2;
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public String toString() {
        return "DoubleValue[" + value + "]";
    }
}
