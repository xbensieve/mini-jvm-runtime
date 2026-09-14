package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalVariablesTest {

    @Test
    @DisplayName("Read and write Category-1 values at valid indices")
    void testCategory1Variables() {
        LocalVariables locals = new LocalVariables(4);
        assertEquals(4, locals.capacity());

        locals.setInt(0, 100);
        locals.setFloat(1, 4.5f);
        locals.setReference(2, Value.ofReference(777L));
        locals.set(3, Value.nullRef());

        assertEquals(100, locals.getInt(0));
        assertEquals(4.5f, locals.getFloat(1));
        assertEquals(Value.ofReference(777L), locals.getReference(2));
        assertSame(Value.nullRef(), locals.get(3));

        assertTrue(locals.isSet(0));
        assertTrue(locals.isSet(1));
        assertTrue(locals.isSet(2));
        assertTrue(locals.isSet(3));
    }

    @Test
    @DisplayName("Reading an uninitialized local variable slot throws StackFaultException")
    void testUninitializedRead() {
        LocalVariables locals = new LocalVariables(2);
        assertTrue(locals.isUninitialized(0));
        assertTrue(locals.isUninitialized(1));

        StackFaultException ex = assertThrows(StackFaultException.class, () -> locals.get(0));
        assertTrue(ex.getMessage().contains("uninitialized"));
    }

    @Test
    @DisplayName("Negative index or out of bounds access throws StackFaultException")
    void testBoundsChecking() {
        LocalVariables locals = new LocalVariables(3);

        assertThrows(StackFaultException.class, () -> locals.get(-1));
        assertThrows(StackFaultException.class, () -> locals.get(3));
        assertThrows(StackFaultException.class, () -> locals.set(-1, Value.ofInt(1)));
        assertThrows(StackFaultException.class, () -> locals.set(3, Value.ofInt(1)));
    }

    @Test
    @DisplayName("Category-2 values occupy index n and n+1; secondary slot cannot be loaded directly")
    void testCategory2SlotSemantics() {
        LocalVariables locals = new LocalVariables(4);

        locals.setLong(0, 999999999L);
        assertTrue(locals.isSet(0));
        assertTrue(locals.isPhantom(1));

        assertEquals(999999999L, locals.getLong(0));

        // Loading from index 1 (secondary slot) must fail per JVMS 2.6.1
        StackFaultException ex = assertThrows(StackFaultException.class, () -> locals.get(1));
        assertTrue(ex.getMessage().contains("secondary slot"));
    }

    @Test
    @DisplayName("Storing Category-2 value at capacity - 1 fails because secondary slot exceeds table bounds")
    void testCategory2AtEndFails() {
        LocalVariables locals = new LocalVariables(3);
        assertThrows(StackFaultException.class, () -> locals.setLong(2, 50L));
    }

    @Test
    @DisplayName("Writing to secondary slot n+1 invalidates the Category-2 value at n")
    void testInvalidationOnSecondaryOverwrite() {
        LocalVariables locals = new LocalVariables(3);
        locals.setLong(0, 1000L);

        assertEquals(1000L, locals.getLong(0));
        assertTrue(locals.isPhantom(1));

        // Overwrite secondary slot 1 with a Category-1 int
        locals.setInt(1, 42);

        // Slot 1 is now an int
        assertEquals(42, locals.getInt(1));
        assertFalse(locals.isPhantom(1));

        // Slot 0 was invalidated because its second half was overwritten
        assertTrue(locals.isUninitialized(0));
        assertThrows(StackFaultException.class, () -> locals.get(0));
    }

    @Test
    @DisplayName("Overwriting slot n with Category-1 value clears phantom status of n+1")
    void testInvalidationOnPrimaryOverwrite() {
        LocalVariables locals = new LocalVariables(3);
        locals.setDouble(0, 3.14159);

        assertEquals(3.14159, locals.getDouble(0));
        assertTrue(locals.isPhantom(1));

        // Overwrite slot 0 with int
        locals.setInt(0, 99);
        assertEquals(99, locals.getInt(0));

        // Slot 1 is no longer phantom, it is uninitialized
        assertTrue(locals.isUninitialized(1));
        assertFalse(locals.isPhantom(1));
        assertThrows(StackFaultException.class, () -> locals.get(1));
    }

    @Test
    @DisplayName("Null reference is distinct from uninitialized slot")
    void testNullReferenceDistinctFromUninitialized() {
        LocalVariables locals = new LocalVariables(2);
        assertTrue(locals.isUninitialized(0));

        locals.set(0, Value.nullRef());
        assertFalse(locals.isUninitialized(0));
        assertTrue(locals.isSet(0));

        Value val = locals.get(0);
        assertTrue(val.isNull());
        assertSame(Value.nullRef(), val);
    }
}
