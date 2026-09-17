package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents an allocated guest array resident in the guest Heap.
 * Extends GuestObject to seamlessly integrate with the guest reference and handle model,
 * providing contiguous slot storage for primitive and reference arrays (JVMS §2.3, §2.4, §6.5).
 */
public final class GuestArray extends GuestObject {

    private final String arrayTypeDescriptor;
    private final String componentTypeDescriptor;
    private final Value[] elements;

    public GuestArray(long handle, String arrayTypeDescriptor, int length) {
        super(handle, Objects.requireNonNull(arrayTypeDescriptor, "arrayTypeDescriptor cannot be null"), Collections.emptyMap());
        if (length < 0) {
            throw new StackFaultException("Negative array size: " + length);
        }
        if (!arrayTypeDescriptor.startsWith("[")) {
            throw new IllegalArgumentException("Invalid array type descriptor: " + arrayTypeDescriptor);
        }

        this.arrayTypeDescriptor = arrayTypeDescriptor;
        this.componentTypeDescriptor = arrayTypeDescriptor.substring(1);
        this.elements = new Value[length];

        Value defaultValue = Heap.defaultValueForDescriptor(componentTypeDescriptor);
        Arrays.fill(this.elements, defaultValue);
    }

    public int length() {
        return elements.length;
    }

    public String arrayTypeDescriptor() {
        return arrayTypeDescriptor;
    }

    public String componentTypeDescriptor() {
        return componentTypeDescriptor;
    }

    /**
     * Retrieves the element at the specified index, enforcing bounds validation.
     *
     * @param index The 0-based array index.
     * @return The element Value.
     * @throws StackFaultException if index is negative or >= length.
     */
    public Value get(int index) {
        checkBounds(index);
        return elements[index];
    }

    /**
     * Stores an element at the specified index, enforcing bounds validation.
     *
     * @param index The 0-based array index.
     * @param value The value to store.
     * @throws StackFaultException if index is negative or >= length.
     */
    public void set(int index, Value value) {
        checkBounds(index);
        Objects.requireNonNull(value, "Array element cannot be null");
        elements[index] = value;
    }

    /**
     * Validates that the specified index is within the valid range [0, length - 1].
     *
     * @param index The index to validate.
     * @throws StackFaultException if the index is out of bounds.
     */
    public void checkBounds(int index) {
        if (index < 0 || index >= elements.length) {
            throw new StackFaultException(
                    String.format("Array index out of bounds: index %d, length %d (array @%d of type '%s')",
                            index, elements.length, handle(), arrayTypeDescriptor)
            );
        }
    }

    public List<Value> elements() {
        return Collections.unmodifiableList(Arrays.asList(elements));
    }

    @Override
    public String toString() {
        return "GuestArray[@" + handle() + ", type=" + arrayTypeDescriptor + ", length=" + elements.length + "]";
    }
}
