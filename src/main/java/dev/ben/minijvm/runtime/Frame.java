package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.StackFaultException;

import java.util.Objects;

/**
 * Execution frame for a single guest method invocation (JVMS 2.6).
 * Owns local variables, operand stack, program counter, and method/constant pool context.
 */
public final class Frame {
    private final MethodInfo method;
    private final ConstantPool constantPool;
    private final LocalVariables locals;
    private final OperandStack operandStack;
    private int pc;

    public Frame(ClassFile classFile, MethodInfo method) {
        Objects.requireNonNull(classFile, "classFile cannot be null");
        Objects.requireNonNull(method, "method cannot be null");

        CodeAttribute code = method.code().orElseThrow(() ->
                new StackFaultException("Cannot create execution frame for method without Code attribute: "
                        + method.name(classFile.constantPool()))
        );

        this.method = method;
        this.constantPool = classFile.constantPool();
        this.locals = new LocalVariables(code.maxLocals());
        this.operandStack = new OperandStack(code.maxStack());
        this.pc = 0;
    }

    public Frame(MethodInfo method, ConstantPool constantPool, int maxLocals, int maxStack) {
        if (method == null) {
            throw new StackFaultException("Frame method metadata cannot be null");
        }
        if (constantPool == null) {
            throw new StackFaultException("Frame constant pool cannot be null");
        }
        if (maxLocals < 0) {
            throw new StackFaultException("Frame maxLocals cannot be negative: " + maxLocals);
        }
        if (maxStack < 0) {
            throw new StackFaultException("Frame maxStack cannot be negative: " + maxStack);
        }

        this.method = method;
        this.constantPool = constantPool;
        this.locals = new LocalVariables(maxLocals);
        this.operandStack = new OperandStack(maxStack);
        this.pc = 0;
    }

    public MethodInfo method() {
        return method;
    }

    public ConstantPool constantPool() {
        return constantPool;
    }

    public LocalVariables locals() {
        return locals;
    }

    public OperandStack operandStack() {
        return operandStack;
    }

    public int pc() {
        return pc;
    }

    public void setPc(int newPc) {
        if (newPc < 0) {
            throw new StackFaultException("Program counter cannot be negative: " + newPc);
        }
        if (method.code().isPresent()) {
            int codeLength = method.code().get().codeLength();
            if (newPc > codeLength) {
                throw new StackFaultException(
                        String.format("Program counter %d out of bounds (code length %d)", newPc, codeLength)
                );
            }
        }
        this.pc = newPc;
    }

    public void advancePc(int delta) {
        setPc(this.pc + delta);
    }

    public int maxLocals() {
        return locals.capacity();
    }

    public int maxStack() {
        return operandStack.maxSlots();
    }

    @Override
    public String toString() {
        String methodName = method.name(constantPool);
        String descriptor = method.descriptor(constantPool);
        return String.format("Frame[%s%s, pc=%d, stackSlots=%d/%d, locals=%d]",
                methodName, descriptor, pc, operandStack.slots(), operandStack.maxSlots(), locals.capacity());
    }
}
