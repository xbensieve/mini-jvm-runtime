package dev.ben.minijvm.classfile;

import dev.ben.minijvm.classfile.ConstantPoolEntry.*;
import dev.ben.minijvm.exception.ClassFormatException;

import java.util.Collections;
import java.util.List;

/**
 * Immutable representation of a class file constant pool.
 * Constant pool indexes are 1-based. Slot 0 is reserved.
 * 8-byte entries (Long, Double) occupy two logical slots.
 */
public final class ConstantPool {
    private final List<ConstantPoolEntry> entries;

    public ConstantPool(List<ConstantPoolEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new ClassFormatException("Constant pool entries list cannot be empty or null");
        }
        this.entries = List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }

    public ConstantPoolEntry get(int index) {
        if (index <= 0 || index >= entries.size()) {
            throw new ClassFormatException(
                    String.format("Invalid constant pool index %d (pool size %d)", index, entries.size())
            );
        }
        ConstantPoolEntry entry = entries.get(index);
        if (entry instanceof UnusableEntry unusable) {
            throw new ClassFormatException(
                    String.format("Attempted access to unusable constant pool index %d: %s", index, unusable.reason())
            );
        }
        return entry;
    }

    @SuppressWarnings("unchecked")
    public <T extends ConstantPoolEntry> T getAs(int index, Class<T> expectedType) {
        ConstantPoolEntry entry = get(index);
        if (!expectedType.isInstance(entry)) {
            throw new ClassFormatException(
                    String.format("Expected %s at constant pool index %d, but found %s",
                            expectedType.getSimpleName(), index, entry.getClass().getSimpleName())
            );
        }
        return (T) entry;
    }

    public String getUtf8(int index) {
        return getAs(index, Utf8Entry.class).value();
    }

    public int getInteger(int index) {
        return getAs(index, IntegerEntry.class).value();
    }

    public float getFloat(int index) {
        return getAs(index, FloatEntry.class).value();
    }

    public long getLong(int index) {
        return getAs(index, LongEntry.class).value();
    }

    public double getDouble(int index) {
        return getAs(index, DoubleEntry.class).value();
    }

    public String getString(int index) {
        StringEntry stringEntry = getAs(index, StringEntry.class);
        return getUtf8(stringEntry.stringIndex());
    }

    public ClassEntry getClassEntry(int index) {
        return getAs(index, ClassEntry.class);
    }

    public String getClassName(int index) {
        ClassEntry classEntry = getClassEntry(index);
        return getUtf8(classEntry.nameIndex());
    }

    public FieldRefEntry getFieldRef(int index) {
        return getAs(index, FieldRefEntry.class);
    }

    public MethodRefEntry getMethodRef(int index) {
        return getAs(index, MethodRefEntry.class);
    }

    public InterfaceMethodRefEntry getInterfaceMethodRef(int index) {
        return getAs(index, InterfaceMethodRefEntry.class);
    }

    public NameAndTypeEntry getNameAndType(int index) {
        return getAs(index, NameAndTypeEntry.class);
    }

    public String getNameAndTypeName(int index) {
        NameAndTypeEntry entry = getNameAndType(index);
        return getUtf8(entry.nameIndex());
    }

    public String getNameAndTypeDescriptor(int index) {
        NameAndTypeEntry entry = getNameAndType(index);
        return getUtf8(entry.descriptorIndex());
    }

    public List<ConstantPoolEntry> rawEntries() {
        return entries;
    }
}
