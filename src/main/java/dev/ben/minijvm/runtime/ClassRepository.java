package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.exception.LinkageException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository of parsed ClassFile definitions loaded into the Mini JVM runtime.
 * Provides class lookup by internal JVM class name (e.g. "com/example/MyClass").
 */
public final class ClassRepository {
    private final Map<String, ClassFile> classes = new HashMap<>();

    public ClassRepository() {}

    public void register(ClassFile classFile) {
        Objects.requireNonNull(classFile, "classFile cannot be null");
        classes.put(classFile.thisClassName(), classFile);
    }

    public Optional<ClassFile> findClass(String internalName) {
        if (internalName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(classes.get(internalName));
    }

    public ClassFile getClass(String internalName) {
        return findClass(internalName).orElseThrow(() ->
                new LinkageException("Class not found in repository: " + internalName)
        );
    }

    public boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    public int size() {
        return classes.size();
    }

    public Map<String, ClassFile> classes() {
        return Collections.unmodifiableMap(classes);
    }
}
