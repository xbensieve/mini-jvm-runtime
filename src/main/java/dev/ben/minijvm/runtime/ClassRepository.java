package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.LinkageException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository of parsed ClassFile definitions loaded into the Mini JVM runtime.
 * Provides class lookup by internal JVM class name (e.g. "com/example/MyClass").
 * Synthesizes standard JDK base and exception classes on demand to support fixture execution.
 */
public final class ClassRepository {

    private static final Map<String, String> STANDARD_CLASS_SUPERS = Map.of(
            "java/lang/Object", "",
            "java/lang/Throwable", "java/lang/Object",
            "java/lang/Exception", "java/lang/Throwable",
            "java/lang/RuntimeException", "java/lang/Exception",
            "java/lang/Error", "java/lang/Throwable",
            "java/lang/NullPointerException", "java/lang/RuntimeException",
            "java/lang/IllegalArgumentException", "java/lang/RuntimeException",
            "java/lang/IllegalStateException", "java/lang/RuntimeException",
            "java/lang/String", "java/lang/Object"
    );

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
        ClassFile cf = classes.get(internalName);
        if (cf != null) {
            return Optional.of(cf);
        }
        if (STANDARD_CLASS_SUPERS.containsKey(internalName)) {
            String superName = STANDARD_CLASS_SUPERS.get(internalName);
            ClassFile synthetic = createSyntheticClass(internalName, superName.isEmpty() ? null : superName);
            classes.put(internalName, synthetic);
            return Optional.of(synthetic);
        }
        return Optional.empty();
    }

    public ClassFile getClass(String internalName) {
        return findClass(internalName).orElseThrow(() ->
                new LinkageException("Class not found in repository: " + internalName)
        );
    }

    public boolean contains(String internalName) {
        return classes.containsKey(internalName) || STANDARD_CLASS_SUPERS.containsKey(internalName);
    }

    public int size() {
        return classes.size();
    }

    public Map<String, ClassFile> classes() {
        return Collections.unmodifiableMap(classes);
    }

    private static ClassFile createSyntheticClass(String className, String superClassName) {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("Slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry(className));       // #1
        entries.add(new ConstantPoolEntry.ClassEntry(1));              // #2: this_class
        int superClassIdx = 0;
        if (superClassName != null) {
            entries.add(new ConstantPoolEntry.Utf8Entry(superClassName)); // #3
            entries.add(new ConstantPoolEntry.ClassEntry(3));             // #4: super_class
            superClassIdx = 4;
        }
        entries.add(new ConstantPoolEntry.Utf8Entry("<init>"));        // #5 (or #3 if super null)
        int nameIdx = entries.size() - 1;
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));           // #6 (or #4 if super null)
        int descIdx = entries.size() - 1;

        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{(byte) 0xB1}; // return (0xB1)
        CodeAttribute codeAttr = new CodeAttribute(1, 1, code, List.of(), List.of());
        MethodInfo initMethod = new MethodInfo(0x0001, nameIdx, descIdx, List.of(), codeAttr);

        return new ClassFile(
                0, 65, cp, 0x0001, 2, superClassIdx,
                List.of(), List.of(), List.of(initMethod), List.of()
        );
    }
}
