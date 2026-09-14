package dev.ben.minijvm.runtime;

/**
 * Singleton representation of a guest null reference (Category-1, 1 slot).
 */
public final class NullReference implements ReferenceValue {
    public static final NullReference INSTANCE = new NullReference();

    private NullReference() {}

    @Override
    public ValueType type() {
        return ValueType.NULL;
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof NullReference;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "NullReference";
    }
}
