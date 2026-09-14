package dev.ben.minijvm.runtime;

import dev.ben.minijvm.exception.StackFaultException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueTest {

    @Test
    @DisplayName("Primitive values represent types and slot widths accurately")
    void testPrimitiveValues() {
        IntValue i = Value.ofInt(42);
        assertEquals(ValueType.INT, i.type());
        assertEquals(1, i.slotWidth());
        assertFalse(i.isCategory2());
        assertFalse(i.isReference());
        assertFalse(i.isNull());
        assertEquals(42, i.asInt());

        LongValue l = Value.ofLong(1234567890123L);
        assertEquals(ValueType.LONG, l.type());
        assertEquals(2, l.slotWidth());
        assertTrue(l.isCategory2());
        assertFalse(l.isReference());
        assertFalse(l.isNull());
        assertEquals(1234567890123L, l.asLong());

        FloatValue f = Value.ofFloat(3.14f);
        assertEquals(ValueType.FLOAT, f.type());
        assertEquals(1, f.slotWidth());
        assertFalse(f.isCategory2());
        assertEquals(3.14f, f.asFloat());

        DoubleValue d = Value.ofDouble(2.71828);
        assertEquals(ValueType.DOUBLE, d.type());
        assertEquals(2, d.slotWidth());
        assertTrue(d.isCategory2());
        assertEquals(2.71828, d.asDouble());
    }

    @Test
    @DisplayName("References and null are Category-1 reference values")
    void testReferenceValues() {
        ObjectReference ref = Value.ofReference(1001L);
        assertEquals(ValueType.REFERENCE, ref.type());
        assertEquals(1, ref.slotWidth());
        assertFalse(ref.isCategory2());
        assertTrue(ref.isReference());
        assertFalse(ref.isNull());
        assertEquals(1001L, ref.handle());
        assertSame(ref, ref.asReference());

        NullReference nullRef = Value.nullRef();
        assertEquals(ValueType.NULL, nullRef.type());
        assertEquals(1, nullRef.slotWidth());
        assertFalse(nullRef.isCategory2());
        assertTrue(nullRef.isReference());
        assertTrue(nullRef.isNull());
        assertSame(nullRef, nullRef.asReference());
    }

    @Test
    @DisplayName("ObjectReference forbids zero handle; NullReference must be used instead")
    void testZeroHandleForbidden() {
        assertThrows(StackFaultException.class, () -> new ObjectReference(0L));
    }

    @Test
    @DisplayName("Type unwrap methods throw StackFaultException on mismatched types")
    void testTypeMismatchUnwraps() {
        IntValue i = Value.ofInt(10);
        assertThrows(StackFaultException.class, i::asLong);
        assertThrows(StackFaultException.class, i::asFloat);
        assertThrows(StackFaultException.class, i::asDouble);
        assertThrows(StackFaultException.class, i::asReference);

        NullReference n = Value.nullRef();
        assertThrows(StackFaultException.class, n::asInt);
        assertThrows(StackFaultException.class, n::asLong);
    }

    @Test
    @DisplayName("Predictable value equality across identical and different types")
    void testEquality() {
        assertEquals(new IntValue(100), Value.ofInt(100));
        assertNotEquals(Value.ofInt(100), Value.ofInt(200));

        assertEquals(new LongValue(100L), Value.ofLong(100L));
        // Int and Long with same numeric value must not be equal
        assertNotEquals((Object) Value.ofInt(100), (Object) Value.ofLong(100L));

        assertEquals(new ObjectReference(55L), Value.ofReference(55L));
        assertNotEquals(Value.ofReference(55L), Value.ofReference(56L));

        assertEquals(NullReference.INSTANCE, Value.nullRef());
        assertNotEquals((Object) Value.nullRef(), (Object) Value.ofReference(1L));
    }
}
