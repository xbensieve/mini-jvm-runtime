package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.AccessFlags;
import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry.MethodRefEntry;
import dev.ben.minijvm.classfile.ConstantPoolEntry.NameAndTypeEntry;
import dev.ben.minijvm.classfile.MethodDescriptor;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Opcode;

import java.util.Objects;
import java.util.Optional;

/**
 * Runtime component for method resolution (JVMS Section 5.4.3.3).
 * Resolves constant-pool Methodref entries to target ClassFile, MethodInfo, and MethodDescriptor,
 * enforcing opcode compatibility, CodeAttribute existence, and deterministic failure semantics.
 */
public final class MethodResolver {

    public record ResolvedMethod(ClassFile classFile, MethodInfo method, MethodDescriptor descriptor) {
        public ResolvedMethod {
            Objects.requireNonNull(classFile, "classFile cannot be null");
            Objects.requireNonNull(method, "method cannot be null");
            Objects.requireNonNull(descriptor, "descriptor cannot be null");
        }
    }

    private final ClassRepository classRepository;

    public MethodResolver() {
        this(new ClassRepository());
    }

    public MethodResolver(ClassRepository classRepository) {
        this.classRepository = Objects.requireNonNull(classRepository, "classRepository cannot be null");
    }

    public ClassRepository classRepository() {
        return classRepository;
    }

    /**
     * Resolves a symbolic method reference at constant pool index {@code methodRefIndex}.
     *
     * @param constantPool   The constant pool of the invoking class.
     * @param callerClass    The invoking class (optional, may be null).
     * @param methodRefIndex 1-based constant pool index of a Methodref entry.
     * @param invokeOpcode   The invoking bytecode opcode (INVOKESTATIC, INVOKEVIRTUAL, INVOKESPECIAL).
     * @return The resolved target method with its declaring ClassFile and parsed MethodDescriptor.
     * @throws ClassFormatException        if the constant pool index is invalid or not a Methodref.
     * @throws UnsupportedFeatureException if the descriptor specifies unsupported types (e.g. category-2).
     * @throws LinkageException            if the class or method cannot be found, has no code, or opcode is incompatible.
     */
    public ResolvedMethod resolveMethod(ConstantPool constantPool, ClassFile callerClass, int methodRefIndex, Opcode invokeOpcode) {
        Objects.requireNonNull(constantPool, "constantPool cannot be null");
        Objects.requireNonNull(invokeOpcode, "invokeOpcode cannot be null");

        // 1. Fetch MethodRefEntry (validates index and entry type)
        MethodRefEntry methodRef = constantPool.getMethodRef(methodRefIndex);

        // 2. Extract symbolic references
        String className = constantPool.getClassName(methodRef.classIndex());
        NameAndTypeEntry nat = constantPool.getNameAndType(methodRef.nameAndTypeIndex());
        String methodName = constantPool.getUtf8(nat.nameIndex());
        String descriptorStr = constantPool.getUtf8(nat.descriptorIndex());

        // 3. Parse and validate method descriptor
        MethodDescriptor descriptor = MethodDescriptor.parse(descriptorStr);
        if (descriptor.hasCategory2Parameters() || descriptor.isCategory2Return()) {
            throw new UnsupportedFeatureException(
                    "Category-2 (long/double) method invocation values are not supported: " + descriptorStr
            );
        }

        // 4. Locate target class
        ClassFile targetClass = null;
        if (callerClass != null && callerClass.thisClassName().equals(className)) {
            targetClass = callerClass;
        } else {
            Optional<ClassFile> found = classRepository.findClass(className);
            if (found.isPresent()) {
                targetClass = found.get();
            } else if (callerClass != null && callerClass.thisClassName().equals(className)) {
                targetClass = callerClass;
            }
        }

        if (targetClass == null) {
            throw new LinkageException(
                    String.format("Cannot resolve method: class '%s' not found", className)
            );
        }

        // 5. Locate method in target class or its superclasses
        ClassFile declaringClass = targetClass;
        Optional<MethodInfo> methodOpt = declaringClass.findMethod(methodName, descriptorStr);

        while (methodOpt.isEmpty() && declaringClass.superClassName().isPresent()) {
            String superName = declaringClass.superClassName().get();
            Optional<ClassFile> superCf = classRepository.findClass(superName);
            if (superCf.isEmpty()) {
                break;
            }
            declaringClass = superCf.get();
            methodOpt = declaringClass.findMethod(methodName, descriptorStr);
        }

        if (methodOpt.isEmpty()) {
            throw new LinkageException(
                    String.format("Method '%s%s' not found in class '%s'", methodName, descriptorStr, className)
            );
        }

        MethodInfo method = methodOpt.get();

        // 6. Ensure target method has a Code attribute (cannot be abstract or native)
        if (method.code().isEmpty()) {
            throw new LinkageException(
                    String.format("Method '%s%s' in class '%s' has no Code attribute",
                            methodName, descriptorStr, declaringClass.thisClassName())
            );
        }

        // 7. Verify invocation opcode compatibility
        boolean isStatic = AccessFlags.isStatic(method.accessFlags());

        if (invokeOpcode == Opcode.INVOKESTATIC) {
            if (!isStatic) {
                throw new LinkageException(
                        String.format("invokestatic attempted on non-static method '%s.%s%s'",
                                declaringClass.thisClassName(), methodName, descriptorStr)
                );
            }
        } else if (invokeOpcode == Opcode.INVOKEVIRTUAL) {
            if (isStatic) {
                throw new LinkageException(
                        String.format("invokevirtual attempted on static method '%s.%s%s'",
                                declaringClass.thisClassName(), methodName, descriptorStr)
                );
            }
            if (methodName.equals("<init>") || methodName.equals("<clinit>")) {
                throw new LinkageException("invokevirtual cannot invoke constructor: " + methodName);
            }
        } else if (invokeOpcode == Opcode.INVOKESPECIAL) {
            if (isStatic) {
                throw new LinkageException(
                        String.format("invokespecial attempted on static method '%s.%s%s'",
                                declaringClass.thisClassName(), methodName, descriptorStr)
                );
            }
        } else {
            throw new UnsupportedFeatureException("Unsupported invocation opcode: " + invokeOpcode);
        }

        return new ResolvedMethod(declaringClass, method, descriptor);
    }
}
