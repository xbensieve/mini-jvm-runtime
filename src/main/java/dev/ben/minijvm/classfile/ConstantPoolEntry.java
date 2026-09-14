package dev.ben.minijvm.classfile;

/**
 * Represents an entry in the class-file constant pool.
 * Sealed to enforce immutable, pattern-matchable representations of all supported constant pool tags.
 */
public sealed interface ConstantPoolEntry {

    int tag();

    record Utf8Entry(String value) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 1;
        }
    }

    record IntegerEntry(int value) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 3;
        }
    }

    record FloatEntry(float value) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 4;
        }
    }

    record LongEntry(long value) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 5;
        }
    }

    record DoubleEntry(double value) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 6;
        }
    }

    record ClassEntry(int nameIndex) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 7;
        }
    }

    record StringEntry(int stringIndex) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 8;
        }
    }

    record FieldRefEntry(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 9;
        }
    }

    record MethodRefEntry(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 10;
        }
    }

    record InterfaceMethodRefEntry(int classIndex, int nameAndTypeIndex) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 11;
        }
    }

    record NameAndTypeEntry(int nameIndex, int descriptorIndex) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 12;
        }
    }

    /**
     * Placeholder entry for slot 0 and the second slot occupied by 8-byte constants (Long, Double).
     */
    record UnusableEntry(String reason) implements ConstantPoolEntry {
        @Override
        public int tag() {
            return 0;
        }
    }
}
