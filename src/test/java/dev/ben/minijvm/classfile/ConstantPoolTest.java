package dev.ben.minijvm.classfile;

import dev.ben.minijvm.classfile.ConstantPoolEntry.*;
import dev.ben.minijvm.exception.ClassFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstantPoolTest {

    @Test
    @DisplayName("ConstantPool stores and retrieves all entry types correctly")
    void testSupportedEntries() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new UnusableEntry("Slot 0")); // 0
        entries.add(new Utf8Entry("Hello"));     // 1
        entries.add(new IntegerEntry(12345));    // 2
        entries.add(new FloatEntry(3.14f));      // 3
        entries.add(new LongEntry(9876543210L)); // 4
        entries.add(new UnusableEntry("Long 2nd slot")); // 5
        entries.add(new DoubleEntry(2.71828));   // 6
        entries.add(new UnusableEntry("Double 2nd slot")); // 7
        entries.add(new ClassEntry(1));          // 8 (points to "Hello")
        entries.add(new StringEntry(1));         // 9 (points to "Hello")
        entries.add(new NameAndTypeEntry(1, 1)); // 10
        entries.add(new FieldRefEntry(8, 10));   // 11
        entries.add(new MethodRefEntry(8, 10));  // 12
        entries.add(new InterfaceMethodRefEntry(8, 10)); // 13

        ConstantPool cp = new ConstantPool(entries);

        assertEquals("Hello", cp.getUtf8(1));
        assertEquals(12345, cp.getInteger(2));
        assertEquals(3.14f, cp.getFloat(3));
        assertEquals(9876543210L, cp.getLong(4));
        assertEquals(2.71828, cp.getDouble(6));
        assertEquals("Hello", cp.getClassName(8));
        assertEquals("Hello", cp.getString(9));
        assertEquals("Hello", cp.getNameAndTypeName(10));
        assertEquals("Hello", cp.getNameAndTypeDescriptor(10));
        assertEquals(8, cp.getFieldRef(11).classIndex());
        assertEquals(10, cp.getFieldRef(11).nameAndTypeIndex());
        assertEquals(8, cp.getMethodRef(12).classIndex());
        assertEquals(8, cp.getInterfaceMethodRef(13).classIndex());
    }

    @Test
    @DisplayName("Accessing slot 0 or unusable 2nd slots of Long/Double throws ClassFormatException")
    void testUnusableSlots() {
        List<ConstantPoolEntry> entries = List.of(
                new UnusableEntry("Slot 0"),
                new LongEntry(100L),
                new UnusableEntry("Long phantom slot")
        );
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(ClassFormatException.class, () -> cp.get(0));
        assertThrows(ClassFormatException.class, () -> cp.get(2));
    }

    @Test
    @DisplayName("Invalid indices throw ClassFormatException")
    void testInvalidIndex() {
        List<ConstantPoolEntry> entries = List.of(
                new UnusableEntry("Slot 0"),
                new Utf8Entry("test")
        );
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(ClassFormatException.class, () -> cp.get(-1));
        assertThrows(ClassFormatException.class, () -> cp.get(2));
        assertThrows(ClassFormatException.class, () -> cp.get(100));
    }

    @Test
    @DisplayName("Type mismatch access throws ClassFormatException")
    void testTypeMismatch() {
        List<ConstantPoolEntry> entries = List.of(
                new UnusableEntry("Slot 0"),
                new Utf8Entry("Not an integer")
        );
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(ClassFormatException.class, () -> cp.getInteger(1));
        assertThrows(ClassFormatException.class, () -> cp.getClassEntry(1));
    }
}
