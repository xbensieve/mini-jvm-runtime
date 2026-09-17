package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.AccessFlags;
import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.ExceptionTableEntry;
import dev.ben.minijvm.classfile.MethodDescriptor;
import dev.ben.minijvm.exception.ArithmeticFaultException;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.GuestExecutionException;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.ArrayType;
import dev.ben.minijvm.runtime.ExceptionTableResolver;
import dev.ben.minijvm.runtime.FieldResolver;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.FrameStatus;
import dev.ben.minijvm.runtime.GcResult;
import dev.ben.minijvm.runtime.GuestArray;
import dev.ben.minijvm.runtime.GuestObject;
import dev.ben.minijvm.runtime.Heap;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.ObjectReference;
import dev.ben.minijvm.runtime.ReferenceValue;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.runtime.ValueType;

import java.util.Objects;
import java.util.Optional;

/**
 * Execution engine for JVM bytecode.
 * Coordinates instruction fetch, decoder invocation, deterministic PC advancement,
 * branch target calculation, return handling, heap/field access, and opcode dispatch onto frame state.
 */
public final class Interpreter {

    private final BytecodeDecoder decoder;
    private final MethodResolver methodResolver;
    private final MethodSelector methodSelector;
    private final FieldResolver fieldResolver;
    private final Heap heap;
    private final ExceptionTableResolver exceptionTableResolver;

    public Interpreter() {
        this(new BytecodeDecoder(), new MethodResolver());
    }

    public Interpreter(BytecodeDecoder decoder) {
        this(decoder, new MethodResolver());
    }

    public Interpreter(MethodResolver methodResolver) {
        this(new BytecodeDecoder(), methodResolver);
    }

    public Interpreter(BytecodeDecoder decoder, MethodResolver methodResolver) {
        this(decoder, methodResolver, new MethodSelector(methodResolver.classRepository()));
    }

    public Interpreter(BytecodeDecoder decoder, MethodResolver methodResolver, MethodSelector methodSelector) {
        this(decoder, methodResolver, methodSelector,
                new FieldResolver(methodResolver.classRepository()),
                new Heap(methodResolver.classRepository()));
    }

    public Interpreter(BytecodeDecoder decoder, MethodResolver methodResolver, MethodSelector methodSelector,
                       FieldResolver fieldResolver, Heap heap) {
        this(decoder, methodResolver, methodSelector, fieldResolver, heap,
                new ExceptionTableResolver(methodResolver.classRepository()));
    }

    public Interpreter(BytecodeDecoder decoder, MethodResolver methodResolver, MethodSelector methodSelector,
                       FieldResolver fieldResolver, Heap heap, ExceptionTableResolver exceptionTableResolver) {
        this.decoder = Objects.requireNonNull(decoder, "decoder cannot be null");
        this.methodResolver = Objects.requireNonNull(methodResolver, "methodResolver cannot be null");
        this.methodSelector = Objects.requireNonNull(methodSelector, "methodSelector cannot be null");
        this.fieldResolver = Objects.requireNonNull(fieldResolver, "fieldResolver cannot be null");
        this.heap = Objects.requireNonNull(heap, "heap cannot be null");
        this.exceptionTableResolver = Objects.requireNonNull(exceptionTableResolver, "exceptionTableResolver cannot be null");
    }

    public BytecodeDecoder decoder() {
        return decoder;
    }

    public MethodResolver methodResolver() {
        return methodResolver;
    }

    public MethodSelector methodSelector() {
        return methodSelector;
    }

    public FieldResolver fieldResolver() {
        return fieldResolver;
    }

    public Heap heap() {
        return heap;
    }

    public ExceptionTableResolver exceptionTableResolver() {
        return exceptionTableResolver;
    }

    /**
     * Executes explicit mark-and-sweep garbage collection over the interpreter's heap using the specified call stack.
     */
    public GcResult collectGarbage(FrameStack frameStack) {
        return heap.collectGarbage(frameStack);
    }

    /**
     * Executes explicit mark-and-sweep garbage collection over the interpreter's heap when no frames are active.
     */
    public GcResult collectGarbage() {
        return heap.collectGarbage();
    }

    /**
     * Executes a single instruction at the frame's current PC.
     * Advances the frame's PC by the instruction length before executing the instruction.
     *
     * @param frame The active execution frame.
     * @return The decoded instruction that was executed.
     */
    public Instruction step(Frame frame) {
        return step(frame, null);
    }

    /**
     * Executes a single instruction at the frame's current PC within an optional FrameStack context.
     *
     * @param frame      The active execution frame.
     * @param frameStack The active call stack, or null if executing a standalone frame.
     * @return The decoded instruction that was executed.
     */
    public Instruction step(Frame frame, FrameStack frameStack) {
        Objects.requireNonNull(frame, "Frame cannot be null");

        if (frame.status() != FrameStatus.RUNNING) {
            throw new StackFaultException("Cannot step frame that is not running: " + frame.status());
        }

        CodeAttribute codeAttr = frame.method().code().orElseThrow(() ->
                new StackFaultException("Method has no Code attribute: " + frame.method().name(frame.constantPool()))
        );
        byte[] code = codeAttr.code();
        int pc = frame.pc();

        if (pc >= code.length) {
            throw new StackFaultException(
                    String.format("Cannot step frame: PC %d is at or beyond code length %d", pc, code.length)
            );
        }

        // 1. Fetch & decode instruction
        Instruction ins = decoder.decode(code, pc);

        // 2. Record instruction PC for error/diagnostic purposes
        frame.setLastInstructionPc(pc);

        // 3. Sequential PC advancement (establishes fall-through PC before execution)
        frame.advancePc(ins.length());

        // 4. Dispatch instruction execution
        try {
            executeInstruction(frame, ins, code.length, frameStack);
        } catch (Exception e) {
            frame.setStatus(FrameStatus.FAILED);
            throw e;
        }

        return ins;
    }

    /**
     * Executes instructions in a frame loop until the frame completes normally or faults.
     * Operates by wrapping the frame in a local FrameStack, ensuring identical semantics
     * to call-stack-based execution.
     *
     * @param frame The execution frame to run.
     */
    public void execute(Frame frame) {
        Objects.requireNonNull(frame, "Frame cannot be null");
        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);
        execute(frameStack);
    }

    /**
     * Iteratively executes frames on the provided FrameStack until the stack is empty
     * or a frame completes. Supports inter-method call chains and bounded recursion.
     *
     * @param frameStack The call stack to execute.
     */
    public void execute(FrameStack frameStack) {
        Objects.requireNonNull(frameStack, "FrameStack cannot be null");

        while (!frameStack.isEmpty()) {
            Frame current = frameStack.current();
            if (current.isCompleted()) {
                break;
            }

            int codeLength = current.method().code().map(CodeAttribute::codeLength).orElse(0);
            if (current.pc() >= codeLength) {
                current.setStatus(FrameStatus.COMPLETED_AT_END);
                break;
            }

            step(current, frameStack);
        }
    }

    private void executeInstruction(Frame frame, Instruction ins, int codeLength, FrameStack frameStack) {
        switch (ins.opcode()) {
            case NOP -> {
                // No operation
            }
            case ACONST_NULL -> {
                frame.operandStack().push(Value.nullRef());
            }
            case ICONST_M1 -> frame.operandStack().push(Value.ofInt(-1));
            case ICONST_0 -> frame.operandStack().push(Value.ofInt(0));
            case ICONST_1 -> frame.operandStack().push(Value.ofInt(1));
            case ICONST_2 -> frame.operandStack().push(Value.ofInt(2));
            case ICONST_3 -> frame.operandStack().push(Value.ofInt(3));
            case ICONST_4 -> frame.operandStack().push(Value.ofInt(4));
            case ICONST_5 -> frame.operandStack().push(Value.ofInt(5));
            case BIPUSH -> frame.operandStack().push(Value.ofInt(ins.operand()));
            case SIPUSH -> frame.operandStack().push(Value.ofInt(ins.operand()));
            case LDC, LDC_W -> {
                int cpIndex = ins.constantPoolIndex();
                ConstantPool cp = frame.constantPool();
                if (cpIndex < 1 || cpIndex >= cp.size()) {
                    throw new ClassFormatException(
                            String.format("Invalid constant pool index for %s: %d (pool size %d)",
                                    ins.opcode().mnemonic(), cpIndex, cp.size())
                    );
                }
                ConstantPoolEntry entry = cp.get(cpIndex);
                if (entry instanceof ConstantPoolEntry.IntegerEntry intEntry) {
                    frame.operandStack().push(Value.ofInt(intEntry.value()));
                } else if (entry instanceof ConstantPoolEntry.StringEntry) {
                    // Load string literal as guest reference handle with runtime class java/lang/String
                    frame.operandStack().push(Value.ofReference(cpIndex, "java/lang/String"));
                } else if (entry instanceof ConstantPoolEntry.LongEntry || entry instanceof ConstantPoolEntry.DoubleEntry) {
                    throw new ClassFormatException(
                            String.format("%s cannot load 8-byte constant from constant pool index %d",
                                    ins.opcode().mnemonic(), cpIndex)
                    );
                } else if (entry instanceof ConstantPoolEntry.FloatEntry) {
                    throw new UnsupportedFeatureException("Float constants via ldc not supported in Phase 06");
                } else if (entry instanceof ConstantPoolEntry.ClassEntry) {
                    throw new UnsupportedFeatureException("Class constants via ldc not supported in Phase 06");
                } else {
                    throw new ClassFormatException(
                            String.format("Invalid constant pool entry type %s for %s at index %d",
                                    entry.getClass().getSimpleName(), ins.opcode().mnemonic(), cpIndex)
                    );
                }
            }
            case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> {
                int localIndex = ins.operand();
                int val = frame.locals().getInt(localIndex);
                frame.operandStack().push(Value.ofInt(val));
            }
            case ALOAD, ALOAD_0, ALOAD_1, ALOAD_2, ALOAD_3 -> {
                int localIndex = ins.operand();
                ReferenceValue ref = frame.locals().getReference(localIndex);
                frame.operandStack().push(ref);
            }
            case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3 -> {
                int localIndex = ins.operand();
                int val = frame.operandStack().popInt();
                frame.locals().setInt(localIndex, val);
            }
            case ASTORE, ASTORE_0, ASTORE_1, ASTORE_2, ASTORE_3 -> {
                int localIndex = ins.operand();
                ReferenceValue ref = frame.operandStack().popReference();
                frame.locals().setReference(localIndex, ref);
            }
            case POP -> {
                Value val = frame.operandStack().pop();
                if (val.isCategory2()) {
                    throw new StackFaultException("pop opcode does not support Category-2 values; use pop2");
                }
            }
            case DUP -> {
                Value val = frame.operandStack().pop();
                if (val.isCategory2()) {
                    throw new StackFaultException("dup opcode does not support Category-2 values; use dup2");
                }
                frame.operandStack().push(val);
                frame.operandStack().push(val);
            }
            case IADD -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(val1 + val2));
            }
            case ISUB -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(val1 - val2));
            }
            case IMUL -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(val1 * val2));
            }
            case IDIV -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val2 == 0) {
                    throw new ArithmeticFaultException("/ by zero");
                }
                // JVMS 6.5.idiv: Integer.MIN_VALUE / -1 overflows to Integer.MIN_VALUE
                if (val1 == Integer.MIN_VALUE && val2 == -1) {
                    frame.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
                } else {
                    frame.operandStack().push(Value.ofInt(val1 / val2));
                }
            }
            case IREM -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val2 == 0) {
                    throw new ArithmeticFaultException("/ by zero");
                }
                // JVMS 6.5.irem: Integer.MIN_VALUE % -1 equals 0
                if (val1 == Integer.MIN_VALUE && val2 == -1) {
                    frame.operandStack().push(Value.ofInt(0));
                } else {
                    frame.operandStack().push(Value.ofInt(val1 % val2));
                }
            }
            case INEG -> {
                int val = frame.operandStack().popInt();
                frame.operandStack().push(Value.ofInt(-val));
            }
            case IINC -> {
                int localIndex = ins.operand();
                int constVal = ins.secondaryOperand();
                int cur = frame.locals().getInt(localIndex);
                frame.locals().setInt(localIndex, cur + constVal);
            }
            case IFEQ -> {
                int val = frame.operandStack().popInt();
                if (val == 0) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFNE -> {
                int val = frame.operandStack().popInt();
                if (val != 0) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFLT -> {
                int val = frame.operandStack().popInt();
                if (val < 0) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFGE -> {
                int val = frame.operandStack().popInt();
                if (val >= 0) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFGT -> {
                int val = frame.operandStack().popInt();
                if (val > 0) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFLE -> {
                int val = frame.operandStack().popInt();
                if (val <= 0) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPEQ -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val1 == val2) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPNE -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val1 != val2) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPLT -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val1 < val2) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPGE -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val1 >= val2) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPGT -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val1 > val2) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPLE -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                if (val1 <= val2) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ACMPEQ -> {
                Value val2 = frame.operandStack().pop();
                Value val1 = frame.operandStack().pop();
                if (!val1.isReference() || !val2.isReference()) {
                    throw new StackFaultException("if_acmpeq requires reference values on operand stack");
                }
                if (areReferencesEqual(val1, val2)) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ACMPNE -> {
                Value val2 = frame.operandStack().pop();
                Value val1 = frame.operandStack().pop();
                if (!val1.isReference() || !val2.isReference()) {
                    throw new StackFaultException("if_acmpne requires reference values on operand stack");
                }
                if (!areReferencesEqual(val1, val2)) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFNULL -> {
                Value val = frame.operandStack().pop();
                if (!val.isReference()) {
                    throw new StackFaultException("ifnull requires reference value on operand stack");
                }
                if (val.isNull()) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IFNONNULL -> {
                Value val = frame.operandStack().pop();
                if (!val.isReference()) {
                    throw new StackFaultException("ifnonnull requires reference value on operand stack");
                }
                if (!val.isNull()) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case GOTO -> {
                jumpTo(frame, ins, codeLength);
            }
            case RETURN -> {
                String desc = frame.method().descriptor(frame.constantPool());
                if (!desc.endsWith("V")) {
                    throw new StackFaultException(
                            String.format("return opcode executed in method '%s' with non-void descriptor '%s'",
                                    frame.method().name(frame.constantPool()), desc)
                    );
                }
                frame.operandStack().clear();
                frame.markReturned();
                if (frameStack != null) {
                    if (frameStack.isEmpty() || frameStack.current() != frame) {
                        throw new StackFaultException("FrameStack mismatch on return: active frame is not top of call stack");
                    }
                    frameStack.pop();
                }
            }
            case IRETURN -> {
                int returnVal = frame.operandStack().popInt();
                String desc = frame.method().descriptor(frame.constantPool());
                char retType = (desc != null && !desc.isEmpty()) ? desc.charAt(desc.length() - 1) : '\0';
                if (retType != 'I' && retType != 'Z' && retType != 'B' && retType != 'C' && retType != 'S') {
                    throw new StackFaultException(
                            String.format("ireturn opcode executed in method '%s' with incompatible return descriptor '%s'",
                                    frame.method().name(frame.constantPool()), desc)
                    );
                }
                frame.operandStack().clear();
                frame.markReturned();
                frame.setReturnValue(Value.ofInt(returnVal));
                if (frameStack != null) {
                    if (frameStack.isEmpty() || frameStack.current() != frame) {
                        throw new StackFaultException("FrameStack mismatch on ireturn: active frame is not top of call stack");
                    }
                    frameStack.pop();
                    if (!frameStack.isEmpty()) {
                        frameStack.current().operandStack().push(Value.ofInt(returnVal));
                    }
                }
            }
            case ARETURN -> {
                ReferenceValue returnVal = frame.operandStack().popReference();
                String desc = frame.method().descriptor(frame.constantPool());
                int rParen = (desc != null) ? desc.indexOf(')') : -1;
                String retTypeStr = (rParen >= 0 && rParen < desc.length() - 1) ? desc.substring(rParen + 1) : "";
                if (!retTypeStr.startsWith("L") && !retTypeStr.startsWith("[")) {
                    throw new StackFaultException(
                            String.format("areturn opcode executed in method '%s' with incompatible return descriptor '%s'",
                                    frame.method().name(frame.constantPool()), desc)
                    );
                }
                frame.operandStack().clear();
                frame.markReturned();
                frame.setReturnValue(returnVal);
                if (frameStack != null) {
                    if (frameStack.isEmpty() || frameStack.current() != frame) {
                        throw new StackFaultException("FrameStack mismatch on areturn: active frame is not top of call stack");
                    }
                    frameStack.pop();
                    if (!frameStack.isEmpty()) {
                        frameStack.current().operandStack().push(returnVal);
                    }
                }
            }
            case NEW -> {
                int cpIndex = ins.constantPoolIndex();
                ConstantPool cp = frame.constantPool();
                if (cpIndex < 1 || cpIndex >= cp.size()) {
                    throw new ClassFormatException(
                            String.format("Invalid constant pool index for new: %d (pool size %d)", cpIndex, cp.size())
                    );
                }
                ConstantPoolEntry entry = cp.get(cpIndex);
                if (!(entry instanceof ConstantPoolEntry.ClassEntry)) {
                    throw new ClassFormatException(
                            String.format("Expected ClassEntry at index %d for new, but found %s",
                                    cpIndex, entry.getClass().getSimpleName())
                    );
                }
                String className = cp.getClassName(cpIndex);
                if (className.startsWith("[")) {
                    throw new LinkageException("new instruction cannot instantiate array class: " + className);
                }
                ClassFile targetClass = methodResolver.classRepository().getClass(className);
                if (AccessFlags.isInterface(targetClass.accessFlags())) {
                    throw new LinkageException("Cannot instantiate interface: " + className);
                }
                if (AccessFlags.isAbstract(targetClass.accessFlags())) {
                    throw new LinkageException("Cannot instantiate abstract class: " + className);
                }
                ObjectReference ref = heap.allocate(targetClass);
                frame.operandStack().push(ref);
            }
            case NEWARRAY -> {
                int count = frame.operandStack().pop().asInt();
                if (count < 0) {
                    throw new StackFaultException("Negative array size: " + count);
                }
                int atype = ins.atype();
                ArrayType arrayType = ArrayType.fromAtype(atype);
                ObjectReference ref = heap.allocateArray(arrayType.arrayDescriptor(), count);
                frame.operandStack().push(ref);
            }
            case ANEWARRAY -> {
                int count = frame.operandStack().pop().asInt();
                if (count < 0) {
                    throw new StackFaultException("Negative array size: " + count);
                }
                int cpIndex = ins.constantPoolIndex();
                ConstantPool cp = frame.constantPool();
                if (cpIndex < 1 || cpIndex >= cp.size()) {
                    throw new ClassFormatException(
                            String.format("Invalid constant pool index for anewarray: %d (pool size %d)", cpIndex, cp.size())
                    );
                }
                ConstantPoolEntry entry = cp.get(cpIndex);
                if (!(entry instanceof ConstantPoolEntry.ClassEntry)) {
                    throw new ClassFormatException(
                            String.format("Expected ClassEntry at index %d for anewarray, but found %s",
                                    cpIndex, entry.getClass().getSimpleName())
                    );
                }
                String className = cp.getClassName(cpIndex);
                String arrayDesc = className.startsWith("[") ? "[" + className : "[L" + className + ";";
                ObjectReference ref = heap.allocateArray(arrayDesc, count);
                frame.operandStack().push(ref);
            }
            case MULTIANEWARRAY -> {
                int dimensions = ins.dimensions();
                if (dimensions < 1) {
                    throw new ClassFormatException("multianewarray dimensions must be >= 1, got: " + dimensions);
                }
                int[] counts = new int[dimensions];
                for (int d = dimensions - 1; d >= 0; d--) {
                    counts[d] = frame.operandStack().pop().asInt();
                    if (counts[d] < 0) {
                        throw new StackFaultException("Negative array size: " + counts[d]);
                    }
                }
                int cpIndex = ins.constantPoolIndex();
                ConstantPool cp = frame.constantPool();
                if (cpIndex < 1 || cpIndex >= cp.size()) {
                    throw new ClassFormatException(
                            String.format("Invalid constant pool index for multianewarray: %d (pool size %d)", cpIndex, cp.size())
                    );
                }
                ConstantPoolEntry entry = cp.get(cpIndex);
                if (!(entry instanceof ConstantPoolEntry.ClassEntry)) {
                    throw new ClassFormatException(
                            String.format("Expected ClassEntry at index %d for multianewarray, but found %s",
                                    cpIndex, entry.getClass().getSimpleName())
                    );
                }
                String arrayDesc = cp.getClassName(cpIndex);
                int bracketCount = 0;
                while (bracketCount < arrayDesc.length() && arrayDesc.charAt(bracketCount) == '[') {
                    bracketCount++;
                }
                if (dimensions > bracketCount) {
                    throw new ClassFormatException(
                            String.format("multianewarray dimensions %d exceeds descriptor array dimensions %d for '%s'",
                                    dimensions, bracketCount, arrayDesc)
                    );
                }
                ObjectReference ref = heap.allocateMultiArray(arrayDesc, counts);
                frame.operandStack().push(ref);
            }
            case ARRAYLENGTH -> {
                Value val = frame.operandStack().pop();
                if (!val.isReference()) {
                    throw new StackFaultException("arraylength expected reference, got: " + val.type());
                }
                if (val.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot get arraylength of null");
                }
                ObjectReference ref = (ObjectReference) val;
                GuestArray array = heap.getArray(ref.handle());
                frame.operandStack().push(Value.ofInt(array.length()));
            }
            case ATHROW -> {
                Value val = frame.operandStack().pop();
                if (!val.isReference()) {
                    throw new StackFaultException("athrow requires object reference on operand stack, got: " + val.type());
                }
                if (val.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot throw null");
                }
                ObjectReference exRef = (ObjectReference) val;
                handleException(exRef, frame, frameStack);
            }
            case IALOAD -> {
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("iaload expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot load from null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                Value elem = array.get(index);
                frame.operandStack().push(elem);
            }
            case AALOAD -> {
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("aaload expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot load from null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                Value elem = array.get(index);
                frame.operandStack().push(elem);
            }
            case BALOAD -> {
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("baload expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot load from null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                Value elem = array.get(index);
                byte b = (byte) elem.asInt();
                frame.operandStack().push(Value.ofInt(b));
            }
            case CALOAD -> {
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("caload expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot load from null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                Value elem = array.get(index);
                char c = (char) elem.asInt();
                frame.operandStack().push(Value.ofInt(c));
            }
            case SALOAD -> {
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("saload expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot load from null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                Value elem = array.get(index);
                short s = (short) elem.asInt();
                frame.operandStack().push(Value.ofInt(s));
            }
            case IASTORE -> {
                int value = frame.operandStack().pop().asInt();
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("iastore expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot store into null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                array.set(index, Value.ofInt(value));
            }
            case AASTORE -> {
                Value value = frame.operandStack().pop();
                if (!value.isReference()) {
                    throw new StackFaultException("aastore expected reference value, got: " + value.type());
                }
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("aastore expected reference array, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot store into null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                array.set(index, value);
            }
            case BASTORE -> {
                int value = frame.operandStack().pop().asInt();
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("bastore expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot store into null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                int truncated = array.componentTypeDescriptor().equals("Z") ? (value & 1) : (byte) value;
                array.set(index, Value.ofInt(truncated));
            }
            case CASTORE -> {
                int value = frame.operandStack().pop().asInt();
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("castore expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot store into null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                array.set(index, Value.ofInt((char) value));
            }
            case SASTORE -> {
                int value = frame.operandStack().pop().asInt();
                int index = frame.operandStack().pop().asInt();
                Value arrayVal = frame.operandStack().pop();
                if (!arrayVal.isReference()) {
                    throw new StackFaultException("sastore expected reference, got: " + arrayVal.type());
                }
                if (arrayVal.isNull()) {
                    throw new StackFaultException("Null pointer dereference: cannot store into null array");
                }
                GuestArray array = heap.getArray(((ObjectReference) arrayVal).handle());
                array.set(index, Value.ofInt((short) value));
            }
            case GETFIELD -> {
                int cpIndex = ins.constantPoolIndex();
                FieldResolver.ResolvedField resolved = fieldResolver.resolveField(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.GETFIELD
                );
                Value receiverVal = frame.operandStack().pop();
                if (!receiverVal.isReference()) {
                    throw new StackFaultException(
                            String.format("getfield expected reference receiver, got: %s", receiverVal.type())
                    );
                }
                if (receiverVal.isNull()) {
                    throw new StackFaultException(
                            String.format("Null pointer dereference: cannot getfield '%s' on null receiver", resolved.key())
                    );
                }
                ObjectReference receiver = (ObjectReference) receiverVal;
                GuestObject obj = heap.getObject(receiver.handle());
                Value val = obj.getField(resolved.key());
                frame.operandStack().push(val);
            }
            case PUTFIELD -> {
                int cpIndex = ins.constantPoolIndex();
                FieldResolver.ResolvedField resolved = fieldResolver.resolveField(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.PUTFIELD
                );
                Value val = frame.operandStack().pop();
                String desc = resolved.field().descriptor(resolved.declaringClass().constantPool());
                if (desc.startsWith("L") || desc.startsWith("[")) {
                    if (!val.isReference()) {
                        throw new StackFaultException(
                                String.format("Type mismatch for putfield '%s': expected reference, got %s",
                                        resolved.key(), val.type())
                        );
                    }
                } else if (desc.equals("I") || desc.equals("Z") || desc.equals("B") || desc.equals("C") || desc.equals("S")) {
                    if (val.type() != ValueType.INT) {
                        throw new StackFaultException(
                                String.format("Type mismatch for putfield '%s': expected int, got %s",
                                        resolved.key(), val.type())
                        );
                    }
                }
                Value receiverVal = frame.operandStack().pop();
                if (!receiverVal.isReference()) {
                    throw new StackFaultException(
                            String.format("putfield expected reference receiver, got: %s", receiverVal.type())
                    );
                }
                if (receiverVal.isNull()) {
                    throw new StackFaultException(
                            String.format("Null pointer dereference: cannot putfield '%s' on null receiver", resolved.key())
                    );
                }
                ObjectReference receiver = (ObjectReference) receiverVal;
                GuestObject obj = heap.getObject(receiver.handle());
                obj.setField(resolved.key(), val);
            }
            case GETSTATIC -> {
                int cpIndex = ins.constantPoolIndex();
                FieldResolver.ResolvedField resolved = fieldResolver.resolveField(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.GETSTATIC
                );
                Value val = heap.getStaticField(resolved.key());
                frame.operandStack().push(val);
            }
            case PUTSTATIC -> {
                int cpIndex = ins.constantPoolIndex();
                FieldResolver.ResolvedField resolved = fieldResolver.resolveField(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.PUTSTATIC
                );
                Value val = frame.operandStack().pop();
                String desc = resolved.field().descriptor(resolved.declaringClass().constantPool());
                if (desc.startsWith("L") || desc.startsWith("[")) {
                    if (!val.isReference()) {
                        throw new StackFaultException(
                                String.format("Type mismatch for putstatic '%s': expected reference, got %s",
                                        resolved.key(), val.type())
                        );
                    }
                } else if (desc.equals("I") || desc.equals("Z") || desc.equals("B") || desc.equals("C") || desc.equals("S")) {
                    if (val.type() != ValueType.INT) {
                        throw new StackFaultException(
                                String.format("Type mismatch for putstatic '%s': expected int, got %s",
                                        resolved.key(), val.type())
                        );
                    }
                }
                heap.setStaticField(resolved.key(), val);
            }
            case INVOKESTATIC -> {
                if (frameStack == null) {
                    throw new StackFaultException("Cannot execute invokestatic without an active FrameStack");
                }
                int cpIndex = ins.constantPoolIndex();
                MethodResolver.ResolvedMethod resolved = methodResolver.resolveMethod(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.INVOKESTATIC
                );

                MethodDescriptor desc = resolved.descriptor();
                int paramCount = desc.parameterCount();
                Value[] args = new Value[paramCount];
                for (int i = paramCount - 1; i >= 0; i--) {
                    MethodDescriptor.Parameter param = desc.parameters().get(i);
                    Value val = frame.operandStack().pop();
                    if (param.isReference()) {
                        if (!val.isReference()) {
                            throw new StackFaultException(
                                    String.format("Type mismatch for parameter %d of '%s': expected reference, got %s",
                                            i, resolved.method().name(resolved.classFile().constantPool()), val.type())
                            );
                        }
                    } else if (param.isIntEquivalent()) {
                        if (val.type() != ValueType.INT) {
                            throw new StackFaultException(
                                    String.format("Type mismatch for parameter %d of '%s': expected int, got %s",
                                            i, resolved.method().name(resolved.classFile().constantPool()), val.type())
                            );
                        }
                    }
                    args[i] = val;
                }

                Frame callee = new Frame(resolved.classFile(), resolved.method());
                for (int i = 0; i < paramCount; i++) {
                    callee.locals().set(i, args[i]);
                }
                frameStack.push(callee);
            }
            case INVOKEVIRTUAL -> {
                if (frameStack == null) {
                    throw new StackFaultException("Cannot execute invokevirtual without an active FrameStack");
                }
                int cpIndex = ins.constantPoolIndex();
                // 1. Symbolic method resolution (JVMS §5.4.3.3)
                MethodResolver.ResolvedMethod resolved = methodResolver.resolveMethod(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.INVOKEVIRTUAL
                );

                MethodDescriptor desc = resolved.descriptor();
                int paramCount = desc.parameterCount();
                Value[] args = new Value[paramCount];
                for (int i = paramCount - 1; i >= 0; i--) {
                    MethodDescriptor.Parameter param = desc.parameters().get(i);
                    Value val = frame.operandStack().pop();
                    if (param.isReference()) {
                        if (!val.isReference()) {
                            throw new StackFaultException(
                                    String.format("Type mismatch for parameter %d of '%s': expected reference, got %s",
                                            i, resolved.method().name(resolved.classFile().constantPool()), val.type())
                            );
                        }
                    } else if (param.isIntEquivalent()) {
                        if (val.type() != ValueType.INT) {
                            throw new StackFaultException(
                                    String.format("Type mismatch for parameter %d of '%s': expected int, got %s",
                                            i, resolved.method().name(resolved.classFile().constantPool()), val.type())
                            );
                        }
                    }
                    args[i] = val;
                }

                Value receiver = frame.operandStack().pop();
                if (!receiver.isReference()) {
                    throw new StackFaultException(
                            String.format("Type mismatch for receiver of '%s': expected reference, got %s",
                                    resolved.method().name(resolved.classFile().constantPool()), receiver.type())
                    );
                }
                if (receiver.isNull()) {
                    throw new StackFaultException(
                            String.format("Null pointer dereference: cannot invoke '%s' on null receiver",
                                    resolved.method().name(resolved.classFile().constantPool()))
                    );
                }

                ObjectReference objRef = (ObjectReference) receiver;
                ClassFile receiverClass;
                if (heap.containsObject(objRef.handle())) {
                    receiverClass = heap.getObject(objRef.handle()).runtimeClass();
                } else {
                    receiverClass = methodResolver.classRepository().getClass(objRef.runtimeClassName());
                }

                // 2. Runtime virtual method selection (JVMS §5.4.6 & §6.5)
                MethodSelector.SelectedMethod selected = methodSelector.selectMethod(resolved, receiverClass);

                Frame callee = new Frame(selected.declaringClass(), selected.method());
                callee.locals().set(0, receiver);
                for (int i = 0; i < paramCount; i++) {
                    callee.locals().set(i + 1, args[i]);
                }
                frameStack.push(callee);
            }
            case INVOKESPECIAL -> {
                if (frameStack == null) {
                    throw new StackFaultException("Cannot execute invokespecial without an active FrameStack");
                }
                int cpIndex = ins.constantPoolIndex();
                // Symbolic method resolution (JVMS §5.4.3.3)
                MethodResolver.ResolvedMethod resolved = methodResolver.resolveMethod(
                        frame.constantPool(),
                        frame.classFile().orElse(null),
                        cpIndex,
                        Opcode.INVOKESPECIAL
                );

                MethodDescriptor desc = resolved.descriptor();
                int paramCount = desc.parameterCount();
                Value[] args = new Value[paramCount];
                for (int i = paramCount - 1; i >= 0; i--) {
                    MethodDescriptor.Parameter param = desc.parameters().get(i);
                    Value val = frame.operandStack().pop();
                    if (param.isReference()) {
                        if (!val.isReference()) {
                            throw new StackFaultException(
                                    String.format("Type mismatch for parameter %d of '%s': expected reference, got %s",
                                            i, resolved.method().name(resolved.classFile().constantPool()), val.type())
                            );
                        }
                    } else if (param.isIntEquivalent()) {
                        if (val.type() != ValueType.INT) {
                            throw new StackFaultException(
                                    String.format("Type mismatch for parameter %d of '%s': expected int, got %s",
                                            i, resolved.method().name(resolved.classFile().constantPool()), val.type())
                            );
                        }
                    }
                    args[i] = val;
                }

                Value receiver = frame.operandStack().pop();
                if (!receiver.isReference()) {
                    throw new StackFaultException(
                            String.format("Type mismatch for receiver of '%s': expected reference, got %s",
                                    resolved.method().name(resolved.classFile().constantPool()), receiver.type())
                    );
                }
                if (receiver.isNull()) {
                    throw new StackFaultException(
                            String.format("Null pointer dereference: cannot invoke '%s' on null receiver",
                                    resolved.method().name(resolved.classFile().constantPool()))
                    );
                }

                // invokespecial executes the resolved method directly without virtual selection (JVMS §6.5)
                Frame callee = new Frame(resolved.classFile(), resolved.method());
                callee.locals().set(0, receiver);
                for (int i = 0; i < paramCount; i++) {
                    callee.locals().set(i + 1, args[i]);
                }
                frameStack.push(callee);
            }
        }
    }

    private void jumpTo(Frame frame, Instruction ins, int codeLength) {
        long rawTarget = (long) ins.pc() + ins.branchOffset();
        if (rawTarget < 0 || rawTarget >= codeLength) {
            throw new StackFaultException(
                    String.format("Branch target %d out of bounds (instruction PC %d, offset %+d, code length %d)",
                            rawTarget, ins.pc(), ins.branchOffset(), codeLength)
            );
        }
        frame.setPc((int) rawTarget);
    }

    private boolean areReferencesEqual(Value ref1, Value ref2) {
        if (ref1.isNull() && ref2.isNull()) {
            return true;
        }
        if (ref1.isNull() || ref2.isNull()) {
            return false;
        }
        return ((ObjectReference) ref1).handle() == ((ObjectReference) ref2).handle();
    }

    private void handleException(ObjectReference exRef, Frame throwingFrame, FrameStack frameStack) {
        String exceptionClassName = exRef.runtimeClassName();
        int instructionPc = throwingFrame.lastInstructionPc();

        // 1. Check if the throwing frame contains a matching handler
        Optional<ExceptionTableEntry> localHandlerOpt =
                exceptionTableResolver.findHandler(throwingFrame, exceptionClassName, instructionPc);
        if (localHandlerOpt.isPresent()) {
            ExceptionTableEntry handler = localHandlerOpt.get();
            int codeLength = throwingFrame.method().code().map(CodeAttribute::codeLength).orElse(0);
            if (handler.handlerPc() < 0 || handler.handlerPc() >= codeLength) {
                throw new StackFaultException(
                        String.format("Handler PC %d out of bounds (code length %d)", handler.handlerPc(), codeLength)
                );
            }
            throwingFrame.operandStack().clear();
            throwingFrame.operandStack().push(exRef);
            throwingFrame.setPc(handler.handlerPc());
            return;
        }

        // 2. No handler in throwing frame: mark failed and unwind
        throwingFrame.setStatus(FrameStatus.FAILED);

        if (frameStack == null) {
            throw new GuestExecutionException(
                    exRef,
                    String.format("Uncaught guest exception '%s' in method '%s' at PC %d (opcode %s)",
                            exceptionClassName,
                            throwingFrame.method().name(throwingFrame.constantPool()),
                            instructionPc,
                            Opcode.ATHROW.mnemonic())
            );
        }

        if (!frameStack.isEmpty() && frameStack.current() == throwingFrame) {
            frameStack.pop();
        }

        // 3. Unwind caller frames across frameStack
        while (!frameStack.isEmpty()) {
            Frame caller = frameStack.current();
            int callerPc = caller.lastInstructionPc();
            Optional<ExceptionTableEntry> callerHandlerOpt =
                    exceptionTableResolver.findHandler(caller, exceptionClassName, callerPc);
            if (callerHandlerOpt.isPresent()) {
                ExceptionTableEntry handler = callerHandlerOpt.get();
                int callerCodeLength = caller.method().code().map(CodeAttribute::codeLength).orElse(0);
                if (handler.handlerPc() < 0 || handler.handlerPc() >= callerCodeLength) {
                    throw new StackFaultException(
                            String.format("Handler PC %d out of bounds in caller (code length %d)",
                                    handler.handlerPc(), callerCodeLength)
                    );
                }
                caller.operandStack().clear();
                caller.operandStack().push(exRef);
                caller.setPc(handler.handlerPc());
                return;
            } else {
                caller.setStatus(FrameStatus.FAILED);
                frameStack.pop();
            }
        }

        // 4. Unwound past root frame without matching handler -> uncaught exception fault
        throw new GuestExecutionException(
                exRef,
                String.format("Uncaught guest exception '%s' in method '%s' at PC %d (opcode %s)",
                        exceptionClassName,
                        throwingFrame.method().name(throwingFrame.constantPool()),
                        instructionPc,
                        Opcode.ATHROW.mnemonic())
        );
    }
}
