package dev.ben.minijvm.runtime;

import java.util.Objects;

/**
 * Unique identifier for a field according to JVM symbolic resolution semantics (JVMS §5.4.3.2).
 * Fields are uniquely identified by declaring class, field name, and field descriptor,
 * which distinguishes shadowed fields in class inheritance hierarchies.
 */
public record FieldKey(String declaringClassName, String name, String descriptor) {

    public FieldKey {
        Objects.requireNonNull(declaringClassName, "declaringClassName cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
    }

    @Override
    public String toString() {
        return declaringClassName + "." + name + ":" + descriptor;
    }
}
