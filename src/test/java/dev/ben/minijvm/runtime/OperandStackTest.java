package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OperandStackTest {

    @Test
    @DisplayName("Push and pop Category-1 values updates slot depth and returns in LIFO order")
    void testPushPopCategory1() {
        OperandStack stack = new OperandStack(4);
        assertTrue(stack.isEmpty());
        assertEquals(0, stack.slots());
        assertEquals(0, stack.valueCount());

        stack.push(Value.ofInt(10));
        assertEquals(1, stack.slots());
        assertEquals(1, stack.valueCount());

        stack.push(Value.ofFloat(2.5f));
        assertEquals(2, stack.slots());
        assertEquals(2, stack.valueCount());

        stack.push(Value.nullRef());
        assertEquals(3, stack.slots());

        stack.push(Value.ofReference(99L));
        assertEquals(4, stack.slots());

        // LIFO pop
        assertEquals(Value.ofReference(99L), stack.pop());
        assertEquals(3, stack.slots());

        assertEquals(Value.nullRef(), stack.pop());
        assertEquals(2, stack.slots());

        assertEquals(Value.ofFloat(2.5f), stack.pop());
        assertEquals(1, stack.slots());

        assertEquals(Value.ofInt(10), stack.pop());
        assertEquals(0, stack.slots());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("Push and pop Category-2 values (Long, Double) accounts for 2 slots per value")
    void testPushPopCategory2() {
        OperandStack stack = new OperandStack(4);

        stack.push(Value.ofLong(123456789L));
        assertEquals(2, stack.slots());
        assertEquals(1, stack.valueCount());

        stack.push(Value.ofDouble(3.14159));
        assertEquals(4, stack.slots());
        assertEquals(2, stack.valueCount());

        assertEquals(Value.ofDouble(3.14159), stack.pop());
        assertEquals(2, stack.slots());

        assertEquals(Value.ofLong(123456789L), stack.pop());
        assertEquals(0, stack.slots());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("Operand stack overflow occurs when capacity is exceeded by Category-1 or Category-2 values")
    void testOverflow() {
        OperandStack stack = new OperandStack(3);
        stack.push(Value.ofInt(1));
        stack.push(Value.ofInt(2));
        assertEquals(2, stack.slots());

        // Only 1 slot left; pushing a Category-2 value (needs 2 slots) must fail
        StackFaultException ex1 = assertThrows(StackFaultException.class, () -> stack.push(Value.ofLong(500L)));
        assertTrue(ex1.getMessage().contains("overflow"));

        // Push 1 more Category-1 value -> fills the 3 slots
        stack.push(Value.ofInt(3));
        assertEquals(3, stack.slots());

        // Pushing another 1-slot value must fail
        StackFaultException ex2 = assertThrows(StackFaultException.class, () -> stack.push(Value.ofInt(4)));
        assertTrue(ex2.getMessage().contains("overflow"));
    }

    @Test
    @DisplayName("Operand stack underflow on pop or peek from empty stack")
    void testUnderflow() {
        OperandStack stack = new OperandStack(2);
        assertThrows(StackFaultException.class, stack::pop);
        assertThrows(StackFaultException.class, stack::peek);
        assertThrows(StackFaultException.class, () -> stack.peek(0));
    }

    @Test
    @DisplayName("Peek operations inspect top and depth offsets without popping")
    void testPeek() {
        OperandStack stack = new OperandStack(3);
        stack.push(Value.ofInt(10));
        stack.push(Value.ofInt(20));
        stack.push(Value.ofInt(30));

        assertEquals(Value.ofInt(30), stack.peek());
        assertEquals(Value.ofInt(30), stack.peek(0)); // top
        assertEquals(Value.ofInt(20), stack.peek(1)); // 1 below top
        assertEquals(Value.ofInt(10), stack.peek(2)); // 2 below top

        assertThrows(StackFaultException.class, () -> stack.peek(3));
        assertThrows(StackFaultException.class, () -> stack.peek(-1));

        assertEquals(3, stack.slots());
    }

    @Test
    @DisplayName("Negative capacity is rejected")
    void testNegativeCapacity() {
        assertThrows(StackFaultException.class, () -> new OperandStack(-1));
    }

    @Test
    @DisplayName("Pushing null Value reference is rejected")
    void testPushNullValueObject() {
        OperandStack stack = new OperandStack(2);
        assertThrows(StackFaultException.class, () -> stack.push(null));
    }

    @Test
    @DisplayName("Type-specialized pop methods return unpacked values")
    void testTypeSpecializedPops() {
        OperandStack stack = new OperandStack(7); // 1 (int) + 2 (long) + 1 (float) + 2 (double) + 1 (ref) = 7 slots
        stack.push(Value.ofInt(42));
        stack.push(Value.ofLong(888L));
        stack.push(Value.ofFloat(1.23f));
        stack.push(Value.ofDouble(9.81));
        stack.push(Value.ofReference(7L));

        assertEquals(Value.ofReference(7L), stack.popReference());
        assertEquals(9.81, stack.popDouble());
        assertEquals(1.23f, stack.popFloat());
        assertEquals(888L, stack.popLong());
        assertEquals(42, stack.popInt());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("toList returns an unmodifiable snapshot without exposing internal array")
    void testToListSnapshot() {
        OperandStack stack = new OperandStack(3);
        stack.push(Value.ofInt(1));
        stack.push(Value.ofInt(2));

        List<Value> snapshot = stack.toList();
        assertEquals(2, snapshot.size());
        assertEquals(Value.ofInt(1), snapshot.get(0));
        assertEquals(Value.ofInt(2), snapshot.get(1));

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(Value.ofInt(3)));
    }

    @Test
    @DisplayName("Pathological capacity and integer boundary checks reject overflow safely")
    void testPathologicalCapacityOverflow() {
        // Zero capacity stack cannot accept even 1-slot push
        OperandStack zeroStack = new OperandStack(0);
        assertEquals(0, zeroStack.maxSlots());
        assertThrows(StackFaultException.class, () -> zeroStack.push(Value.ofInt(1)));

        // Stack with 1 slot cannot accept 2-slot push
        OperandStack oneSlotStack = new OperandStack(1);
        assertThrows(StackFaultException.class, () -> oneSlotStack.push(Value.ofLong(99L)));
        assertThrows(StackFaultException.class, () -> oneSlotStack.push(Value.ofDouble(1.0)));
    }
}
