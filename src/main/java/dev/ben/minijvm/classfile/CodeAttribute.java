package dev.ben.minijvm.classfile;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Parsed Code attribute of a method, containing bytecode instructions,
 * stack/local limits, exception handling table, and nested attributes.
 */
public record CodeAttribute(
        int maxStack,
        int maxLocals,
        byte[] code,
        List<ExceptionTableEntry> exceptionTable,
        List<Attribute> attributes
) implements Attribute {

    public CodeAttribute {
        Objects.requireNonNull(code, "code cannot be null");
        code = code.clone();
        exceptionTable = (exceptionTable == null) ? List.of() : List.copyOf(exceptionTable);
        attributes = (attributes == null) ? List.of() : List.copyOf(attributes);
    }

    @Override
    public String name() {
        return "Code";
    }

    @Override
    public byte[] code() {
        return code.clone();
    }

    public int codeLength() {
        return code.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CodeAttribute that)) return false;
        return maxStack == that.maxStack
                && maxLocals == that.maxLocals
                && Arrays.equals(code, that.code)
                && Objects.equals(exceptionTable, that.exceptionTable)
                && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(maxStack, maxLocals, exceptionTable, attributes);
        result = 31 * result + Arrays.hashCode(code);
        return result;
    }

    @Override
    public String toString() {
        return String.format("CodeAttribute[maxStack=%d, maxLocals=%d, codeLength=%d, exceptions=%d, attrs=%d]",
                maxStack, maxLocals, code.length, exceptionTable.size(), attributes.size());
    }
}
