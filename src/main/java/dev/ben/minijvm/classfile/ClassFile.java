package dev.ben.minijvm.classfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable model of a parsed JVM class file.
 */
public record ClassFile(
        int minorVersion,
        int majorVersion,
        ConstantPool constantPool,
        int accessFlags,
        int thisClassIndex,
        int superClassIndex,
        List<Integer> interfaces,
        List<FieldInfo> fields,
        List<MethodInfo> methods,
        List<Attribute> attributes
) {
    public ClassFile {
        Objects.requireNonNull(constantPool, "constantPool cannot be null");
        interfaces = (interfaces == null) ? List.of() : List.copyOf(interfaces);
        fields = (fields == null) ? List.of() : List.copyOf(fields);
        methods = (methods == null) ? List.of() : List.copyOf(methods);
        attributes = (attributes == null) ? List.of() : List.copyOf(attributes);
    }

    public String thisClassName() {
        return constantPool.getClassName(thisClassIndex);
    }

    public Optional<String> superClassName() {
        if (superClassIndex == 0) {
            return Optional.empty();
        }
        return Optional.of(constantPool.getClassName(superClassIndex));
    }

    public List<String> interfaceNames() {
        return interfaces.stream()
                .map(constantPool::getClassName)
                .toList();
    }

    public Optional<MethodInfo> findMethod(String name, String descriptor) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        return methods.stream()
                .filter(m -> name.equals(m.name(constantPool)) && descriptor.equals(m.descriptor(constantPool)))
                .findFirst();
    }

    public Optional<FieldInfo> findField(String name, String descriptor) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        return fields.stream()
                .filter(f -> name.equals(f.name(constantPool)) && descriptor.equals(f.descriptor(constantPool)))
                .findFirst();
    }

    public Optional<Attribute> findAttribute(String name) {
        return attributes.stream()
                .filter(a -> a.name().equals(name))
                .findFirst();
    }
}
