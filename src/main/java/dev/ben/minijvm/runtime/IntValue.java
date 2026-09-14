package dev.ben.minijvm.runtime;

/**
 * Represents a 32-bit signed integer value (Category-1, 1 slot).
 */
public record IntValue(int value) implements PrimitiveValue {
    @Override
    public ValueType type() {
        return ValueType.INT;
    }

    @Override
    public int slotWidth() {
        return 1;
    }

    @Override
    public int asInt() {
        return value;
    }

    @Override
    public String toString() {
        return "IntValue[" + value + "]";
    }
}
