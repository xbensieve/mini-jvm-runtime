package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.MethodDescriptor;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.LinkageException;

import java.util.Objects;
import java.util.Optional;

/**
 * Runtime component for JVM virtual method selection (JVMS Section 5.4.6 & Section 6.5).
 * Given a symbolically resolved method and a receiver runtime class, selects the
 * actual method to execute by searching the receiver's class hierarchy from bottom to top.
 *
 * <p>Separates symbolic resolution (MethodResolver) from dynamic dispatch (MethodSelector).
 */
public final class MethodSelector {

    public record SelectedMethod(ClassFile declaringClass, MethodInfo method, MethodDescriptor descriptor) {
        public SelectedMethod {
            Objects.requireNonNull(declaringClass, "declaringClass cannot be null");
            Objects.requireNonNull(method, "method cannot be null");
            Objects.requireNonNull(descriptor, "descriptor cannot be null");
        }
    }

    private final ClassRepository classRepository;

    public MethodSelector(ClassRepository classRepository) {
        this.classRepository = Objects.requireNonNull(classRepository, "classRepository cannot be null");
    }

    public ClassRepository classRepository() {
        return classRepository;
    }

    /**
     * Performs virtual method selection per JVMS Section 5.4.6.
     *
     * @param resolvedMethod The symbolically resolved target method (JVMS Section 5.4.3.3).
     * @param receiverClass  The actual runtime class of the receiver object.
     * @return The selected method and its declaring ClassFile.
     * @throws LinkageException if receiverClass is not a subtype of the resolved declaring class,
     *                          if the method cannot be found in the receiver's hierarchy,
     *                          or if the selected method lacks a Code attribute.
     */
    public SelectedMethod selectMethod(MethodResolver.ResolvedMethod resolvedMethod, ClassFile receiverClass) {
        Objects.requireNonNull(resolvedMethod, "resolvedMethod cannot be null");
        Objects.requireNonNull(receiverClass, "receiverClass cannot be null");

        String methodName = resolvedMethod.method().name(resolvedMethod.classFile().constantPool());
        String descriptor = resolvedMethod.method().descriptor(resolvedMethod.classFile().constantPool());
        String resolvedClassName = resolvedMethod.classFile().thisClassName();

        // 1. Verify receiver class is a subtype of the resolved class
        if (!isSubtypeOf(receiverClass, resolvedClassName)) {
            throw new LinkageException(
                    String.format("Receiver class '%s' is not a subtype of resolved method declaring class '%s'",
                            receiverClass.thisClassName(), resolvedClassName)
            );
        }

        // 2. Method Selection (JVMS Section 5.4.6): search from receiverClass up the superclass hierarchy
        ClassFile current = receiverClass;
        while (current != null) {
            Optional<MethodInfo> candidate = current.findMethod(methodName, descriptor);
            if (candidate.isPresent()) {
                MethodInfo selectedMethod = candidate.get();
                if (selectedMethod.code().isEmpty()) {
                    throw new LinkageException(
                            String.format("Selected method '%s%s' in class '%s' has no Code attribute",
                                    methodName, descriptor, current.thisClassName())
                    );
                }
                return new SelectedMethod(current, selectedMethod, resolvedMethod.descriptor());
            }

            if (current.superClassName().isPresent()) {
                String superName = current.superClassName().get();
                current = classRepository.findClass(superName).orElse(null);
            } else {
                current = null;
            }
        }

        throw new LinkageException(
                String.format("Method selection failed: no implementation of '%s%s' found in hierarchy of receiver '%s'",
                        methodName, descriptor, receiverClass.thisClassName())
        );
    }

    /**
     * Checks if {@code sub} is a subclass of or identical to {@code superClassName}.
     */
    public boolean isSubtypeOf(ClassFile sub, String superClassName) {
        if (sub == null || superClassName == null) {
            return false;
        }
        if (sub.thisClassName().equals(superClassName)) {
            return true;
        }
        if ("java/lang/Object".equals(superClassName)) {
            return true;
        }
        ClassFile cur = sub;
        while (cur.superClassName().isPresent()) {
            String parentName = cur.superClassName().get();
            if (parentName.equals(superClassName)) {
                return true;
            }
            Optional<ClassFile> parent = classRepository.findClass(parentName);
            if (parent.isEmpty()) {
                break;
            }
            cur = parent.get();
        }
        return false;
    }
}
