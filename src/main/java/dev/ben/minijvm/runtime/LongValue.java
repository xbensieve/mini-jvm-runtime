package dev.ben.minijvm.runtime;

/**
 * Represents a 64-bit signed integer value (Category-2, 2 slots).
 */
public record LongValue(long value) implements PrimitiveValue {
    @Override
    public ValueType type() {
        return ValueType.LONG;
    }

    @Override
    public int slotWidth() {
        return 2;
    }

    @Override
    public long asLong() {
        return value;
    }

    @Override
    public String toString() {
        return "LongValue[" + value + "L]";
    }
}
