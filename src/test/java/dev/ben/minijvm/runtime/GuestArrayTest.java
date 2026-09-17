package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.StackFaultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GuestArrayTest {

    private ClassRepository repository;
    private Heap heap;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        heap = new Heap(repository);
    }

    @Test
    @DisplayName("Create primitive GuestArray initializes length, descriptors, and zero defaults")
    void testPrimitiveArrayInitialization() {
        GuestArray intArray = new GuestArray(1L, "[I", 5);
        assertEquals(1L, intArray.handle());
        assertEquals(5, intArray.length());
        assertEquals("[I", intArray.arrayTypeDescriptor());
        assertEquals("I", intArray.componentTypeDescriptor());
        assertEquals("[I", intArray.runtimeClassName());
        assertTrue(intArray.isArray());
        assertSame(intArray, intArray.asArray());
        assertTrue(intArray.findRuntimeClass().isEmpty());
        assertThrows(StackFaultException.class, intArray::runtimeClass);

        for (int i = 0; i < 5; i++) {
            assertEquals(Value.ofInt(0), intArray.get(i));
        }

        // Test other primitives
        GuestArray boolArray = new GuestArray(2L, "[Z", 2);
        assertEquals(Value.ofInt(0), boolArray.get(0));

        GuestArray byteArray = new GuestArray(3L, "[B", 3);
        assertEquals(Value.ofInt(0), byteArray.get(0));

        GuestArray charArray = new GuestArray(4L, "[C", 4);
        assertEquals(Value.ofInt(0), charArray.get(0));

        GuestArray shortArray = new GuestArray(5L, "[S", 5);
        assertEquals(Value.ofInt(0), shortArray.get(0));
    }

    @Test
    @DisplayName("Create reference GuestArray initializes length, descriptors, and null defaults")
    void testReferenceArrayInitialization() {
        GuestArray refArray = new GuestArray(10L, "[Ljava/lang/String;", 3);
        assertEquals(10L, refArray.handle());
        assertEquals(3, refArray.length());
        assertEquals("[Ljava/lang/String;", refArray.arrayTypeDescriptor());
        assertEquals("Ljava/lang/String;", refArray.componentTypeDescriptor());

        for (int i = 0; i < 3; i++) {
            assertTrue(refArray.get(i).isNull());
        }

        GuestArray multiRefArray = new GuestArray(11L, "[[I", 2);
        assertEquals("[[I", multiRefArray.arrayTypeDescriptor());
        assertEquals("[I", multiRefArray.componentTypeDescriptor());
        assertTrue(multiRefArray.get(0).isNull());
    }

    @Test
    @DisplayName("Negative array length throws StackFaultException")
    void testNegativeArrayLengthThrows() {
        StackFaultException ex = assertThrows(StackFaultException.class, () -> new GuestArray(1L, "[I", -1));
        assertTrue(ex.getMessage().contains("Negative array size"));
    }

    @Test
    @DisplayName("Invalid array descriptor throws IllegalArgumentException")
    void testInvalidDescriptorThrows() {
        assertThrows(IllegalArgumentException.class, () -> new GuestArray(1L, "I", 5));
        assertThrows(NullPointerException.class, () -> new GuestArray(1L, null, 5));
    }

    @Test
    @DisplayName("Array bounds validation on get and set")
    void testArrayBoundsValidation() {
        GuestArray array = new GuestArray(1L, "[I", 3);

        // Valid set and get
        array.set(0, Value.ofInt(10));
        array.set(1, Value.ofInt(20));
        array.set(2, Value.ofInt(30));

        assertEquals(Value.ofInt(10), array.get(0));
        assertEquals(Value.ofInt(20), array.get(1));
        assertEquals(Value.ofInt(30), array.get(2));

        // Lower bound check
        assertThrows(StackFaultException.class, () -> array.get(-1));
        assertThrows(StackFaultException.class, () -> array.set(-1, Value.ofInt(99)));

        // Upper bound check
        assertThrows(StackFaultException.class, () -> array.get(3));
        assertThrows(StackFaultException.class, () -> array.set(3, Value.ofInt(99)));
        assertThrows(StackFaultException.class, () -> array.get(10));

        // Null element check
        assertThrows(NullPointerException.class, () -> array.set(0, null));
    }

    @Test
    @DisplayName("ArrayType enum coverage and validation")
    void testArrayTypeEnum() {
        assertEquals(ArrayType.T_BOOLEAN, ArrayType.fromAtype(4));
        assertEquals(ArrayType.T_CHAR, ArrayType.fromAtype(5));
        assertEquals(ArrayType.T_FLOAT, ArrayType.fromAtype(6));
        assertEquals(ArrayType.T_DOUBLE, ArrayType.fromAtype(7));
        assertEquals(ArrayType.T_BYTE, ArrayType.fromAtype(8));
        assertEquals(ArrayType.T_SHORT, ArrayType.fromAtype(9));
        assertEquals(ArrayType.T_INT, ArrayType.fromAtype(10));
        assertEquals(ArrayType.T_LONG, ArrayType.fromAtype(11));

        assertEquals("[Z", ArrayType.T_BOOLEAN.arrayDescriptor());
        assertEquals("Z", ArrayType.T_BOOLEAN.componentDescriptor());

        assertThrows(ClassFormatException.class, () -> ArrayType.fromAtype(3));
        assertThrows(ClassFormatException.class, () -> ArrayType.fromAtype(12));
    }

    @Test
    @DisplayName("Heap array allocation and retrieval")
    void testHeapArrayAllocation() {
        ObjectReference ref = heap.allocateArray("[I", 7);
        assertNotNull(ref);
        assertEquals(1L, ref.handle());
        assertEquals("[I", ref.runtimeClassName());
        assertTrue(heap.containsObject(ref.handle()));

        GuestArray array = heap.getArray(ref.handle());
        assertNotNull(array);
        assertEquals(7, array.length());

        // Attempting getArray on standard GuestObject throws StackFaultException
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test")
        ));
        ClassFile cf = new ClassFile(0, 65, cp, AccessFlags.ACC_PUBLIC, 1, 0, List.of(), List.of(), List.of(), List.of());
        repository.register(cf);
        ObjectReference objRef = heap.allocate(cf);

        assertThrows(StackFaultException.class, () -> heap.getArray(objRef.handle()));
    }

    @Test
    @DisplayName("Heap multi-dimensional array recursive allocation")
    void testHeapMultiArrayAllocation() {
        ObjectReference rootRef = heap.allocateMultiArray("[[I", new int[]{3, 4});
        assertNotNull(rootRef);
        assertEquals("[[I", rootRef.runtimeClassName());

        GuestArray rootArray = heap.getArray(rootRef.handle());
        assertEquals(3, rootArray.length());

        for (int i = 0; i < 3; i++) {
            Value subVal = rootArray.get(i);
            assertTrue(subVal.isReference());
            assertFalse(subVal.isNull());

            ObjectReference subRef = (ObjectReference) subVal;
            assertEquals("[I", subRef.runtimeClassName());

            GuestArray subArray = heap.getArray(subRef.handle());
            assertEquals(4, subArray.length());

            for (int j = 0; j < 4; j++) {
                assertEquals(Value.ofInt(0), subArray.get(j));
            }
        }

        // Negative dimensions throw
        assertThrows(StackFaultException.class, () -> heap.allocateMultiArray("[[I", new int[]{2, -1}));
        assertThrows(StackFaultException.class, () -> heap.allocateMultiArray("[[I", new int[]{-2, 1}));
    }
}
