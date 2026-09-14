package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded operand stack with explicit JVM slot accounting (JVMS 2.6.2).
 * Category-1 values occupy 1 slot, while Category-2 values (long, double) occupy 2 slots.
 * Enforces strict LIFO semantics and guards against underflow and capacity overflow.
 */
public final class OperandStack {
    private final int maxSlots;
    private final List<Value> elements;
    private int currentSlots;

    public OperandStack(int maxSlots) {
        if (maxSlots < 0) {
            throw new StackFaultException("Operand stack capacity cannot be negative: " + maxSlots);
        }
        this.maxSlots = maxSlots;
        this.elements = new ArrayList<>(maxSlots);
        this.currentSlots = 0;
    }

    public int maxSlots() {
        return maxSlots;
    }

    public int slots() {
        return currentSlots;
    }

    public int valueCount() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public void push(Value value) {
        if (value == null) {
            throw new StackFaultException("Cannot push null Value reference onto operand stack");
        }
        int width = value.slotWidth();
        if ((long) currentSlots + width > maxSlots) {
            throw new StackFaultException(
                    String.format("Operand stack overflow: max capacity is %d slots, currently %d slots, cannot push %d-slot value (%s)",
                            maxSlots, currentSlots, width, value)
            );
        }
        elements.add(value);
        currentSlots += width;
    }

    public Value pop() {
        if (elements.isEmpty()) {
            throw new StackFaultException("Operand stack underflow: attempted to pop from empty stack");
        }
        Value top = elements.remove(elements.size() - 1);
        currentSlots -= top.slotWidth();
        return top;
    }

    public Value peek() {
        if (elements.isEmpty()) {
            throw new StackFaultException("Operand stack underflow: attempted to peek empty stack");
        }
        return elements.get(elements.size() - 1);
    }

    public Value peek(int offsetFromTop) {
        if (offsetFromTop < 0 || offsetFromTop >= elements.size()) {
            throw new StackFaultException(
                    String.format("Operand stack peek offset out of bounds: offset %d (element count %d)",
                            offsetFromTop, elements.size())
            );
        }
        return elements.get(elements.size() - 1 - offsetFromTop);
    }

    public int popInt() {
        return pop().asInt();
    }

    public long popLong() {
        return pop().asLong();
    }

    public float popFloat() {
        return pop().asFloat();
    }

    public double popDouble() {
        return pop().asDouble();
    }

    public ReferenceValue popReference() {
        return pop().asReference();
    }

    public void clear() {
        elements.clear();
        currentSlots = 0;
    }

    /**
     * Returns an unmodifiable snapshot of values currently on the stack from bottom to top.
     */
    public List<Value> toList() {
        return List.copyOf(elements);
    }

    @Override
    public String toString() {
        return String.format("OperandStack[slots=%d/%d, values=%s]", currentSlots, maxSlots, elements);
    }
}
