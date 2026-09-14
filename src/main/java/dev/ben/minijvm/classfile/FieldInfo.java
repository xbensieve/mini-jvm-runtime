package dev.ben.minijvm.classfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata for a class field.
 */
public record FieldInfo(
        int accessFlags,
        int nameIndex,
        int descriptorIndex,
        List<Attribute> attributes
) {
    public FieldInfo {
        attributes = (attributes == null) ? List.of() : List.copyOf(attributes);
    }

    public String name(ConstantPool constantPool) {
        Objects.requireNonNull(constantPool, "constantPool cannot be null");
        return constantPool.getUtf8(nameIndex);
    }

    public String descriptor(ConstantPool constantPool) {
        Objects.requireNonNull(constantPool, "constantPool cannot be null");
        return constantPool.getUtf8(descriptorIndex);
    }

    public Optional<Attribute> findAttribute(String name) {
        return attributes.stream()
                .filter(a -> a.name().equals(name))
                .findFirst();
    }
}
