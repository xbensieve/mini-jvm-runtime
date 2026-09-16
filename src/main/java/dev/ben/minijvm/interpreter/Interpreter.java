package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodDescriptor;
import dev.ben.minijvm.exception.ArithmeticFaultException;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.ObjectReference;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.runtime.ValueType;

import java.util.Objects;

/**
 * Execution engine for JVM bytecode.
 * Coordinates instruction fetch, decoder invocation, deterministic PC advancement,
 * branch target calculation, return handling, and opcode dispatch onto frame state.
 */
public final class Interpreter {

    private final BytecodeDecoder decoder;
    private final MethodResolver methodResolver;
    private final MethodSelector methodSelector;

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
        this.decoder = Objects.requireNonNull(decoder, "decoder cannot be null");
        this.methodResolver = Objects.requireNonNull(methodResolver, "methodResolver cannot be null");
        this.methodSelector = Objects.requireNonNull(methodSelector, "methodSelector cannot be null");
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
        Objects.requireNonNull(frame, "frame cannot be null");

        if (frame.hasFailed()) {
            throw new StackFaultException("Cannot step frame: frame execution has already failed");
        }

        if (frame.isCompleted()) {
            throw new StackFaultException("Cannot step frame: frame has already completed execution");
        }

        CodeAttribute codeAttr = frame.method().code().orElseThrow(() ->
                new StackFaultException("Cannot execute frame for method without Code attribute")
        );
        byte[] code = codeAttr.code();

        if (frame.pc() >= code.length) {
            throw new StackFaultException(
                    String.format("Cannot step frame: PC %d is at or beyond code length %d", frame.pc(), code.length)
            );
        }

        // 1. Fetch & decode instruction at current PC
        Instruction ins = decoder.decode(code, frame.pc());

        // 2. Record instruction start PC and advance PC to next sequential instruction
        frame.setLastInstructionPc(ins.pc());
        frame.advancePc(ins.length());

        // 3. Dispatch opcode execution
        try {
            executeInstruction(ins, frame, frameStack, code.length);
        } catch (RuntimeException e) {
            frame.markFailed();
            throw e;
        }

        return ins;
    }

    /**
     * Runs the frame sequentially until its PC reaches the end of the method's code array
     * or the frame completes.
     */
    public void execute(Frame frame) {
        Objects.requireNonNull(frame, "frame cannot be null");
        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);
        execute(frameStack);
    }

    /**
     * Runs execution across a FrameStack until all frames have returned and the call stack is empty.
     */
    public void execute(FrameStack frameStack) {
        Objects.requireNonNull(frameStack, "frameStack cannot be null");
        while (!frameStack.isEmpty()) {
            Frame current = frameStack.current();
            if (current.isCompleted()) {
                frameStack.pop();
                continue;
            }
            int codeLength = current.method().code().map(CodeAttribute::codeLength).orElse(0);
            if (current.isRunning() && current.pc() >= codeLength) {
                current.markCompletedAtEnd();
                frameStack.pop();
                continue;
            }
            step(current, frameStack);
        }
    }

    private void executeInstruction(Instruction ins, Frame frame, FrameStack frameStack, int codeLength) {
        switch (ins.opcode()) {
            case NOP -> {
                // Do nothing
            }
            case ACONST_NULL -> {
                frame.operandStack().push(Value.nullRef());
            }
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> {
                frame.operandStack().push(Value.ofInt(ins.operand()));
            }
            case BIPUSH, SIPUSH -> {
                frame.operandStack().push(Value.ofInt(ins.operand()));
            }
            case LDC, LDC_W -> {
                int cpIndex = ins.constantPoolIndex();
                ConstantPool cp = frame.constantPool();
                ConstantPoolEntry entry = cp.get(cpIndex);
                if (entry instanceof ConstantPoolEntry.IntegerEntry intEntry) {
                    frame.operandStack().push(Value.ofInt(intEntry.value()));
                } else if (entry instanceof ConstantPoolEntry.StringEntry) {
                    frame.operandStack().push(Value.ofReference(cpIndex, "java/lang/String"));
                } else if (entry instanceof ConstantPoolEntry.LongEntry || entry instanceof ConstantPoolEntry.DoubleEntry) {
                    throw new ClassFormatException(
                            String.format("%s cannot load 8-byte constant from constant pool index %d",
                                    ins.opcode().mnemonic(), cpIndex)
                    );
                } else if (entry instanceof ConstantPoolEntry.FloatEntry) {
                    throw new UnsupportedFeatureException("Float constants via ldc not supported in Phase 05");
                } else if (entry instanceof ConstantPoolEntry.ClassEntry) {
                    throw new UnsupportedFeatureException("Class constants via ldc not supported in Phase 05");
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
            case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3 -> {
                int localIndex = ins.operand();
                int val = frame.operandStack().popInt();
                frame.locals().setInt(localIndex, val);
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
                int localIndex = ins.localIndex();
                int constVal = ins.incrementConst();
                int currentVal = frame.locals().getInt(localIndex);
                frame.locals().setInt(localIndex, currentVal + constVal);
            }
            case IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                int val = frame.operandStack().popInt();
                boolean condition = switch (ins.opcode()) {
                    case IFEQ -> val == 0;
                    case IFNE -> val != 0;
                    case IFLT -> val < 0;
                    case IFGE -> val >= 0;
                    case IFGT -> val > 0;
                    case IFLE -> val <= 0;
                    default -> throw new AssertionError();
                };
                if (condition) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
                int val2 = frame.operandStack().popInt();
                int val1 = frame.operandStack().popInt();
                boolean condition = switch (ins.opcode()) {
                    case IF_ICMPEQ -> val1 == val2;
                    case IF_ICMPNE -> val1 != val2;
                    case IF_ICMPLT -> val1 < val2;
                    case IF_ICMPGE -> val1 >= val2;
                    case IF_ICMPGT -> val1 > val2;
                    case IF_ICMPLE -> val1 <= val2;
                    default -> throw new AssertionError();
                };
                if (condition) {
                    jumpTo(frame, ins, codeLength);
                }
            }
            case GOTO -> {
                jumpTo(frame, ins, codeLength);
            }
            case RETURN -> {
                String desc = frame.method().descriptor(frame.constantPool());
                if (desc == null || !desc.endsWith("V")) {
                    throw new StackFaultException(
                            String.format("return opcode executed in method '%s' with non-void return descriptor '%s'",
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
                ClassFile receiverClass = methodResolver.classRepository().getClass(objRef.runtimeClassName());

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
}
