package dev.ben.minijvm.classfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable metadata for a class method.
 */
public record MethodInfo(
        int accessFlags,
        int nameIndex,
        int descriptorIndex,
        List<Attribute> attributes,
        CodeAttribute codeAttribute
) {
    public MethodInfo {
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

    public Optional<CodeAttribute> code() {
        return Optional.ofNullable(codeAttribute);
    }

    public Optional<Attribute> findAttribute(String name) {
        return attributes.stream()
                .filter(a -> a.name().equals(name))
                .findFirst();
    }
}
