package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ExceptionTableEntry;

import java.util.Objects;
import java.util.Optional;

/**
 * Searches and resolves matching catch handlers in a method's exception table (JVMS §4.7.3 & §6.5).
 * Evaluates active instruction PC ranges [startPc, endPc) and checks catch-type subtype compatibility.
 */
public final class ExceptionTableResolver {

    private final ClassRepository classRepository;

    public ExceptionTableResolver() {
        this(new ClassRepository());
    }

    public ExceptionTableResolver(ClassRepository classRepository) {
        this.classRepository = Objects.requireNonNull(classRepository, "classRepository cannot be null");
    }

    public ClassRepository classRepository() {
        return classRepository;
    }

    /**
     * Searches a frame's method exception table for the first handler that protects the instruction at
     * {@code instructionPc} and is compatible with {@code thrownExceptionClass}.
     *
     * @param frame                The active execution frame.
     * @param thrownExceptionClass The internal JVM class name of the thrown exception (e.g. "java/lang/RuntimeException").
     * @param instructionPc        The PC of the instruction that caused or propagated the exception.
     * @return An Optional containing the matching ExceptionTableEntry, or empty if no handler matched.
     */
    public Optional<ExceptionTableEntry> findHandler(Frame frame, String thrownExceptionClass, int instructionPc) {
        if (frame == null || frame.method() == null) {
            return Optional.empty();
        }

        Optional<CodeAttribute> codeOpt = frame.method().code();
        if (codeOpt.isEmpty()) {
            return Optional.empty();
        }

        CodeAttribute code = codeOpt.get();
        for (ExceptionTableEntry entry : code.exceptionTable()) {
            // JVMS §4.7.3: start_pc is inclusive, end_pc is exclusive
            if (instructionPc >= entry.startPc() && instructionPc < entry.endPc()) {
                if (entry.catchType() == 0) {
                    // Catch-all handler (finally block)
                    return Optional.of(entry);
                }

                String catchClassName = frame.constantPool().getClassName(entry.catchType());
                if (isSubtypeOf(thrownExceptionClass, catchClassName)) {
                    return Optional.of(entry);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Determines whether {@code thrownClassName} is a subtype of or identical to {@code targetClassName}.
     * Supports classfile hierarchy traversal in ClassRepository and recognized standard JDK exception bases.
     *
     * @param thrownClassName The internal name of the thrown exception class.
     * @param targetClassName The internal name of the caught exception class.
     * @return true if thrownClassName can be assigned to targetClassName.
     */
    public boolean isSubtypeOf(String thrownClassName, String targetClassName) {
        if (thrownClassName == null || targetClassName == null) {
            return false;
        }
        if (thrownClassName.equals(targetClassName)) {
            return true;
        }
        if ("java/lang/Object".equals(targetClassName) || "java/lang/Throwable".equals(targetClassName)) {
            return true;
        }

        // Standard JVM exception hierarchy shortcuts
        if ("java/lang/Exception".equals(targetClassName)) {
            if ("java/lang/RuntimeException".equals(thrownClassName) || "java/lang/Exception".equals(thrownClassName)) {
                return true;
            }
        }

        // Traverse hierarchy in ClassRepository
        Optional<ClassFile> curOpt = classRepository.findClass(thrownClassName);
        while (curOpt.isPresent()) {
            ClassFile cur = curOpt.get();
            if (cur.superClassName().isEmpty()) {
                break;
            }
            String parentName = cur.superClassName().get();
            if (parentName.equals(targetClassName)) {
                return true;
            }
            if ("java/lang/Object".equals(parentName) || "java/lang/Throwable".equals(parentName)) {
                return "java/lang/Object".equals(targetClassName) || "java/lang/Throwable".equals(targetClassName);
            }
            if ("java/lang/Exception".equals(parentName)) {
                return "java/lang/Exception".equals(targetClassName)
                        || "java/lang/Throwable".equals(targetClassName)
                        || "java/lang/Object".equals(targetClassName);
            }
            if ("java/lang/RuntimeException".equals(parentName)) {
                return "java/lang/RuntimeException".equals(targetClassName)
                        || "java/lang/Exception".equals(targetClassName)
                        || "java/lang/Throwable".equals(targetClassName)
                        || "java/lang/Object".equals(targetClassName);
            }
            if ("java/lang/Error".equals(parentName)) {
                return "java/lang/Error".equals(targetClassName)
                        || "java/lang/Throwable".equals(targetClassName)
                        || "java/lang/Object".equals(targetClassName);
            }

            curOpt = classRepository.findClass(parentName);
        }

        return false;
    }
}
