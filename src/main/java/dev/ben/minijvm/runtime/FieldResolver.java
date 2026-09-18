package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Opcode;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves symbolic field references from class constant pools (JVMS Section 5.4.3.2).
 * Strictly separated from instruction execution and field storage access.
 */
public final class FieldResolver {

    public record ResolvedField(
            ClassFile declaringClass,
            FieldInfo field,
            FieldKey key,
            boolean isStatic
    ) {
        public ResolvedField {
            Objects.requireNonNull(declaringClass, "declaringClass cannot be null");
            Objects.requireNonNull(field, "field cannot be null");
            Objects.requireNonNull(key, "key cannot be null");
        }
    }

    private final ClassRepository classRepository;

    public FieldResolver(ClassRepository classRepository) {
        this.classRepository = Objects.requireNonNull(classRepository, "classRepository cannot be null");
    }

    public ClassRepository classRepository() {
        return classRepository;
    }

    /**
     * Resolves a symbolic field reference given a constant pool and a 1-based index.
     *
     * @param cp            The caller's constant pool.
     * @param callerClass   The caller's class file context.
     * @param fieldRefIndex 1-based constant pool index of a FieldRefEntry.
     * @param opcode        The opcode attempting field access (GETFIELD, PUTFIELD, GETSTATIC, PUTSTATIC).
     * @return The resolved field metadata and key.
     * @throws ClassFormatException        if the constant pool index or entry tag is invalid.
     * @throws LinkageException            if the target class or field cannot be found,
     *                                     or if opcode access flag constraints are violated.
     * @throws UnsupportedFeatureException if an unsupported opcode is provided.
     */
    public ResolvedField resolveField(ConstantPool cp, ClassFile callerClass, int fieldRefIndex, Opcode opcode) {
        Objects.requireNonNull(cp, "ConstantPool cannot be null");
        Objects.requireNonNull(opcode, "Opcode cannot be null");

        // 1. Validate constant pool bounds
        if (fieldRefIndex < 1 || fieldRefIndex >= cp.size()) {
            throw new ClassFormatException(
                    String.format("Invalid constant pool index for field reference: %d (pool size %d)",
                            fieldRefIndex, cp.size())
            );
        }

        // 2. Validate entry tag
        ConstantPoolEntry entry = cp.get(fieldRefIndex);
        if (!(entry instanceof ConstantPoolEntry.FieldRefEntry fieldRef)) {
            throw new ClassFormatException(
                    String.format("Expected FieldRefEntry at constant pool index %d, but found %s",
                            fieldRefIndex, entry.getClass().getSimpleName())
            );
        }

        // 3. Resolve class name, field name, and descriptor
        String className = cp.getClassName(fieldRef.classIndex());
        String fieldName = cp.getNameAndTypeName(fieldRef.nameAndTypeIndex());
        String descriptor = cp.getNameAndTypeDescriptor(fieldRef.nameAndTypeIndex());

        // 4. Locate target class in repository
        ClassFile targetClass = null;
        if (callerClass != null && className.equals(callerClass.thisClassName())) {
            targetClass = callerClass;
        } else {
            Optional<ClassFile> cfOpt = classRepository.findClass(className);
            if (cfOpt.isPresent()) {
                targetClass = cfOpt.get();
            }
        }

        if (targetClass == null) {
            throw new LinkageException(
                    String.format("Cannot resolve field: class '%s' not found", className)
            );
        }

        // 5. Search for field definition in target class and its superclasses (JVMS Section 5.4.3.2)
        ClassFile declaringClass = targetClass;
        Optional<FieldInfo> fieldOpt = declaringClass.findField(fieldName, descriptor);

        while (fieldOpt.isEmpty() && declaringClass.superClassName().isPresent()) {
            String superName = declaringClass.superClassName().get();
            Optional<ClassFile> superCf = classRepository.findClass(superName);
            if (superCf.isEmpty()) {
                break;
            }
            declaringClass = superCf.get();
            fieldOpt = declaringClass.findField(fieldName, descriptor);
        }

        if (fieldOpt.isEmpty()) {
            throw new LinkageException(
                    String.format("Field '%s:%s' not found in class '%s' or its hierarchy",
                            fieldName, descriptor, className)
            );
        }

        FieldInfo field = fieldOpt.get();
        boolean isStatic = AccessFlags.isStatic(field.accessFlags());

        // 6. Enforce opcode access compatibility
        if (opcode == Opcode.GETSTATIC || opcode == Opcode.PUTSTATIC) {
            if (!isStatic) {
                throw new LinkageException(
                        String.format("%s attempted on non-static field '%s.%s:%s'",
                                opcode.mnemonic(), declaringClass.thisClassName(), fieldName, descriptor)
                );
            }
        } else if (opcode == Opcode.GETFIELD || opcode == Opcode.PUTFIELD) {
            if (isStatic) {
                throw new LinkageException(
                        String.format("%s attempted on static field '%s.%s:%s'",
                                opcode.mnemonic(), declaringClass.thisClassName(), fieldName, descriptor)
                );
            }
        } else {
            throw new UnsupportedFeatureException("Unsupported field access opcode: " + opcode);
        }

        FieldKey key = new FieldKey(declaringClass.thisClassName(), fieldName, descriptor);
        return new ResolvedField(declaringClass, field, key, isStatic);
    }
}
