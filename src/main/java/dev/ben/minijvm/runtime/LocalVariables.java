package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fixed-capacity local variable table for a single method frame (JVMS 2.6.1).
 * Strictly enforces JVM 2-slot semantics for long and double, tracks uninitialized slots,
 * and invalidates broken pairs upon partial overwrites.
 */
public final class LocalVariables {

    private sealed interface Slot permits Uninitialized, ValueSlot, PhantomSlot {}
    private record Uninitialized() implements Slot {}
    private record ValueSlot(Value value) implements Slot {}
    private record PhantomSlot(int primaryIndex) implements Slot {}

    private final int capacity;
    private final Slot[] slots;

    public LocalVariables(int capacity) {
        if (capacity < 0) {
            throw new StackFaultException("Local variables capacity cannot be negative: " + capacity);
        }
        this.capacity = capacity;
        this.slots = new Slot[capacity];
        Arrays.fill(this.slots, new Uninitialized());
    }

    public int capacity() {
        return capacity;
    }

    public void set(int index, Value value) {
        if (value == null) {
            throw new StackFaultException("Cannot store null Value reference in local variables");
        }
        checkBounds(index);

        // 1. Invalidate prior associations of slot 'index'
        invalidatePriorSlot(index);

        // 2. Handle 2-slot vs 1-slot storage
        if (value.isCategory2()) {
            if (index + 1 >= capacity) {
                throw new StackFaultException(
                        String.format("Cannot store 2-slot value at index %d in table of capacity %d (requires slot %d)",
                                index, capacity, index + 1)
                );
            }
            // Invalidate slot 'index + 1' before using it as a phantom slot
            invalidatePriorSlot(index + 1);

            slots[index] = new ValueSlot(value);
            slots[index + 1] = new PhantomSlot(index);
        } else {
            slots[index] = new ValueSlot(value);
        }
    }

    public Value get(int index) {
        checkBounds(index);
        Slot slot = slots[index];
        if (slot instanceof Uninitialized) {
            throw new StackFaultException("Attempted to read uninitialized local variable at index " + index);
        }
        if (slot instanceof PhantomSlot phantom) {
            throw new StackFaultException(
                    String.format("Local variable slot %d is the secondary slot of a 2-slot value at index %d and cannot be read directly",
                            index, phantom.primaryIndex())
            );
        }
        if (slot instanceof ValueSlot vs) {
            return vs.value();
        }
        throw new StackFaultException("Unknown slot state at index " + index);
    }

    private void invalidatePriorSlot(int idx) {
        Slot current = slots[idx];
        if (current instanceof PhantomSlot phantom) {
            // Overwriting the phantom slot breaks the primary 2-slot value at phantom.primaryIndex
            slots[phantom.primaryIndex()] = new Uninitialized();
        } else if (current instanceof ValueSlot vs && vs.value().isCategory2()) {
            // Overwriting the primary 2-slot value breaks its phantom slot at idx + 1
            if (idx + 1 < capacity && slots[idx + 1] instanceof PhantomSlot) {
                slots[idx + 1] = new Uninitialized();
            }
        }
    }

    private void checkBounds(int index) {
        if (index < 0 || index >= capacity) {
            throw new StackFaultException(
                    String.format("Local variable index out of bounds: %d (capacity %d)", index, capacity)
            );
        }
    }

    public boolean isSet(int index) {
        return index >= 0 && index < capacity && slots[index] instanceof ValueSlot;
    }

    public boolean isUninitialized(int index) {
        return index >= 0 && index < capacity && slots[index] instanceof Uninitialized;
    }

    public boolean isPhantom(int index) {
        return index >= 0 && index < capacity && slots[index] instanceof PhantomSlot;
    }

    /**
     * Returns an unmodifiable list of all active slot values in this local variable table,
     * ignoring uninitialized and phantom slots.
     */
    public List<Value> activeValues() {
        List<Value> values = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            if (slots[i] instanceof ValueSlot vs) {
                values.add(vs.value());
            }
        }
        return Collections.unmodifiableList(values);
    }

    public int getInt(int index) {
        return get(index).asInt();
    }

    public void setInt(int index, int value) {
        set(index, Value.ofInt(value));
    }

    public long getLong(int index) {
        return get(index).asLong();
    }

    public void setLong(int index, long value) {
        set(index, Value.ofLong(value));
    }

    public float getFloat(int index) {
        return get(index).asFloat();
    }

    public void setFloat(int index, float value) {
        set(index, Value.ofFloat(value));
    }

    public double getDouble(int index) {
        return get(index).asDouble();
    }

    public void setDouble(int index, double value) {
        set(index, Value.ofDouble(value));
    }

    public ReferenceValue getReference(int index) {
        return get(index).asReference();
    }

    public void setReference(int index, ReferenceValue ref) {
        set(index, ref);
    }

    @Override
    public String toString() {
        return String.format("LocalVariables[capacity=%d]", capacity);
    }
}
