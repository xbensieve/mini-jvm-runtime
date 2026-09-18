package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.StackFaultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HeapTest {

    private ClassRepository repository;
    private Heap heap;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        heap = new Heap(repository);
    }

    private ClassFile createClassWithFields(String className, String superClassName, List<FieldInfo> fields, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1, // thisClassIndex -> #1
                superClassName != null ? 3 : 0,
                List.of(),
                fields,
                List.of(),
                List.of()
        );
    }

    private ConstantPool createClassConstantPool(String className, String superClassName) {
        return new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.ClassEntry(2),                      // #1 Class this
                new ConstantPoolEntry.Utf8Entry(className),               // #2 Utf8 this
                new ConstantPoolEntry.ClassEntry(4),                      // #3 Class super
                new ConstantPoolEntry.Utf8Entry(superClassName != null ? superClassName : "java/lang/Object"), // #4 Utf8 super
                new ConstantPoolEntry.Utf8Entry("count"),                 // #5
                new ConstantPoolEntry.Utf8Entry("I"),                     // #6
                new ConstantPoolEntry.Utf8Entry("name"),                  // #7
                new ConstantPoolEntry.Utf8Entry("Ljava/lang/String;")     // #8
        ));
    }

    @Test
    @DisplayName("Allocate guest object initializes unique monotonic handles and default field values")
    void testAllocateObject() {
        ConstantPool cp = createClassConstantPool("com/example/MyClass", null);
        FieldInfo fieldCount = new FieldInfo(AccessFlags.ACC_PUBLIC, 5, 6, List.of());
        FieldInfo fieldName = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());

        ClassFile cf = createClassWithFields("com/example/MyClass", null, List.of(fieldCount, fieldName), cp);
        repository.register(cf);

        ObjectReference ref1 = heap.allocate(cf);
        ObjectReference ref2 = heap.allocate(cf);

        assertNotNull(ref1);
        assertNotNull(ref2);
        assertEquals(1L, ref1.handle());
        assertEquals(2L, ref2.handle());
        assertEquals("com/example/MyClass", ref1.runtimeClassName());
        assertEquals(2, heap.objectCount());

        GuestObject obj1 = heap.getObject(ref1.handle());
        assertSame(cf, obj1.runtimeClass());

        FieldKey countKey = new FieldKey("com/example/MyClass", "count", "I");
        FieldKey nameKey = new FieldKey("com/example/MyClass", "name", "Ljava/lang/String;");

        assertEquals(Value.ofInt(0), obj1.getField(countKey));
        assertTrue(obj1.getField(nameKey).isNull());
    }

    @Test
    @DisplayName("Object allocation inherits fields from superclass hierarchy")
    void testInheritedFieldInitialization() {
        ConstantPool superCp = createClassConstantPool("com/example/SuperClass", null);
        FieldInfo superField = new FieldInfo(AccessFlags.ACC_PUBLIC, 5, 6, List.of()); // count:I
        ClassFile superCf = createClassWithFields("com/example/SuperClass", null, List.of(superField), superCp);
        repository.register(superCf);

        ConstantPool subCp = createClassConstantPool("com/example/SubClass", "com/example/SuperClass");
        FieldInfo subField = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of()); // name:Ljava/lang/String;
        ClassFile subCf = createClassWithFields("com/example/SubClass", "com/example/SuperClass", List.of(subField), subCp);
        repository.register(subCf);

        ObjectReference ref = heap.allocate(subCf);
        GuestObject obj = heap.getObject(ref.handle());

        FieldKey superKey = new FieldKey("com/example/SuperClass", "count", "I");
        FieldKey subKey = new FieldKey("com/example/SubClass", "name", "Ljava/lang/String;");

        assertTrue(obj.hasField(superKey));
        assertTrue(obj.hasField(subKey));
        assertEquals(Value.ofInt(0), obj.getField(superKey));
        assertTrue(obj.getField(subKey).isNull());
    }

    @Test
    @DisplayName("getObject throws StackFaultException for invalid or unknown handles")
    void testInvalidObjectHandleThrows() {
        assertThrows(StackFaultException.class, () -> heap.getObject(999L));
        assertFalse(heap.containsObject(999L));
        assertTrue(heap.findObject(999L).isEmpty());
    }

    @Test
    @DisplayName("Static fields get and set operate correctly with default initialization")
    void testStaticFields() {
        FieldKey staticKey = new FieldKey("com/example/MyClass", "globalCounter", "I");

        // First access returns default value for descriptor
        Value initial = heap.getStaticField(staticKey);
        assertEquals(Value.ofInt(0), initial);

        // Update static field
        heap.setStaticField(staticKey, Value.ofInt(42));
        assertEquals(Value.ofInt(42), heap.getStaticField(staticKey));
    }

    @Test
    @DisplayName("Reset clears all allocated objects and static fields")
    void testReset() {
        ConstantPool cp = createClassConstantPool("com/example/MyClass", null);
        ClassFile cf = createClassWithFields("com/example/MyClass", null, List.of(), cp);
        repository.register(cf);

        ObjectReference ref = heap.allocate(cf);
        assertEquals(1, heap.objectCount());

        FieldKey staticKey = new FieldKey("com/example/MyClass", "flag", "I");
        heap.setStaticField(staticKey, Value.ofInt(1));

        heap.reset();
        assertEquals(0, heap.objectCount());
        assertFalse(heap.containsObject(ref.handle()));

        // Next allocation starts at handle 1 again
        ObjectReference newRef = heap.allocate(cf);
        assertEquals(1L, newRef.handle());
    }

    @Test
    @DisplayName("Default value for descriptors covers primitives and references according to JVMS Section 2.3/Section 2.4")
    void testDefaultValueForDescriptor() {
        assertEquals(Value.ofInt(0), Heap.defaultValueForDescriptor("I"));
        assertEquals(Value.ofInt(0), Heap.defaultValueForDescriptor("Z"));
        assertEquals(Value.ofInt(0), Heap.defaultValueForDescriptor("B"));
        assertEquals(Value.ofInt(0), Heap.defaultValueForDescriptor("C"));
        assertEquals(Value.ofInt(0), Heap.defaultValueForDescriptor("S"));
        assertEquals(Value.ofLong(0L), Heap.defaultValueForDescriptor("J"));
        assertEquals(Value.ofFloat(0.0f), Heap.defaultValueForDescriptor("F"));
        assertEquals(Value.ofDouble(0.0d), Heap.defaultValueForDescriptor("D"));
        assertTrue(Heap.defaultValueForDescriptor("Ljava/lang/String;").isNull());
        assertTrue(Heap.defaultValueForDescriptor("[I").isNull());

        assertThrows(IllegalArgumentException.class, () -> Heap.defaultValueForDescriptor(""));
        assertThrows(IllegalArgumentException.class, () -> Heap.defaultValueForDescriptor("V"));
    }

    @Test
    @DisplayName("GuestObject mutate field and invariant validations")
    void testGuestObjectMutations() {
        ConstantPool cp = createClassConstantPool("com/example/Test", null);
        ClassFile cf = createClassWithFields("com/example/Test", null, List.of(), cp);

        FieldKey key = new FieldKey("com/example/Test", "val", "I");
        GuestObject obj = new GuestObject(10L, cf, Map.of(key, Value.ofInt(100)));

        assertEquals(10L, obj.handle());
        assertEquals("com/example/Test", obj.runtimeClassName());
        assertEquals(Value.ofInt(100), obj.getField(key));

        obj.setField(key, Value.ofInt(200));
        assertEquals(Value.ofInt(200), obj.getField(key));

        FieldKey unknownKey = new FieldKey("com/example/Test", "unknown", "I");
        assertThrows(StackFaultException.class, () -> obj.getField(unknownKey));
        assertThrows(StackFaultException.class, () -> obj.setField(unknownKey, Value.ofInt(1)));

        assertThrows(StackFaultException.class, () -> new GuestObject(0L, cf, Map.of()));
        assertThrows(StackFaultException.class, () -> new GuestObject(-1L, cf, Map.of()));

        ObjectReference ref = obj.toReference();
        assertEquals(10L, ref.handle());
        assertEquals("com/example/Test", ref.runtimeClassName());
    }

    @Test
    @DisplayName("FieldKey invariants and string formatting")
    void testFieldKey() {
        FieldKey key = new FieldKey("pkg/Cls", "fld", "I");
        assertEquals("pkg/Cls", key.declaringClassName());
        assertEquals("fld", key.name());
        assertEquals("I", key.descriptor());
        assertEquals("pkg/Cls.fld:I", key.toString());

        assertThrows(NullPointerException.class, () -> new FieldKey(null, "f", "I"));
        assertThrows(NullPointerException.class, () -> new FieldKey("c", null, "I"));
        assertThrows(NullPointerException.class, () -> new FieldKey("c", "f", null));
    }

    @Test
    @DisplayName("removeObject and sweep deallocate guest handles and reclaim heap capacity")
    void testRemoveObjectAndSweep() {
        ConstantPool cp = createClassConstantPool("com/example/Node", null);
        ClassFile cf = createClassWithFields("com/example/Node", null, List.of(), cp);
        repository.register(cf);

        ObjectReference ref1 = heap.allocate(cf);
        ObjectReference ref2 = heap.allocate(cf);
        ObjectReference ref3 = heap.allocate(cf);

        assertEquals(3, heap.objectCount());
        assertEquals(java.util.Set.of(1L, 2L, 3L), heap.allocatedHandles());

        // Test removeObject
        assertTrue(heap.removeObject(2L));
        assertFalse(heap.removeObject(2L)); // already removed
        assertFalse(heap.containsObject(2L));
        assertEquals(2, heap.objectCount());
        assertThrows(StackFaultException.class, () -> heap.getObject(2L));

        // Test sweep retaining only handle 3
        var reclaimed = heap.sweep(java.util.Set.of(3L));
        assertEquals(java.util.Set.of(1L), reclaimed);
        assertEquals(1, heap.objectCount());
        assertTrue(heap.containsObject(3L));
        assertFalse(heap.containsObject(1L));
        assertThrows(StackFaultException.class, () -> heap.getObject(1L));
    }
}
