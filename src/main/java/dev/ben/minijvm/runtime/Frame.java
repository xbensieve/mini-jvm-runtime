package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.StackFaultException;

import java.util.Objects;
import java.util.Optional;

/**
 * Execution frame for a single guest method invocation (JVMS 2.6).
 * Owns local variables, operand stack, program counter, and method/constant pool context.
 */
public final class Frame {
    private final ClassFile classFile;
    private final MethodInfo method;
    private final ConstantPool constantPool;
    private final LocalVariables locals;
    private final OperandStack operandStack;
    private int pc;
    private int lastInstructionPc = -1;
    private FrameStatus status = FrameStatus.RUNNING;
    private Value returnValue = null;

    public Frame(ClassFile classFile, MethodInfo method) {
        Objects.requireNonNull(classFile, "classFile cannot be null");
        Objects.requireNonNull(method, "method cannot be null");

        CodeAttribute code = method.code().orElseThrow(() ->
                new StackFaultException("Cannot create execution frame for method without Code attribute: "
                        + method.name(classFile.constantPool()))
        );

        this.classFile = classFile;
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

        this.classFile = null;
        this.method = method;
        this.constantPool = constantPool;
        this.locals = new LocalVariables(maxLocals);
        this.operandStack = new OperandStack(maxStack);
        this.pc = 0;
    }

    public Optional<ClassFile> classFile() {
        return Optional.ofNullable(classFile);
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

    public int lastInstructionPc() {
        return lastInstructionPc;
    }

    public void setLastInstructionPc(int lastInstructionPc) {
        if (lastInstructionPc < 0) {
            throw new StackFaultException("lastInstructionPc cannot be negative: " + lastInstructionPc);
        }
        this.lastInstructionPc = lastInstructionPc;
    }

    public FrameStatus status() {
        return status;
    }

    public boolean isRunning() {
        return status == FrameStatus.RUNNING;
    }

    public boolean isReturned() {
        return status == FrameStatus.RETURNED;
    }

    public boolean hasReachedEndOfCode() {
        return status == FrameStatus.COMPLETED_AT_END;
    }

    public boolean hasFailed() {
        return status == FrameStatus.FAILED;
    }

    public boolean isCompleted() {
        return status == FrameStatus.RETURNED || status == FrameStatus.COMPLETED_AT_END;
    }

    public void markReturned() {
        this.status = FrameStatus.RETURNED;
    }

    public void markCompletedAtEnd() {
        this.status = FrameStatus.COMPLETED_AT_END;
    }

    public void markFailed() {
        this.status = FrameStatus.FAILED;
    }

    public void markCompleted() {
        markReturned();
    }

    public Optional<Value> returnValue() {
        return Optional.ofNullable(returnValue);
    }

    public void setReturnValue(Value returnValue) {
        this.returnValue = returnValue;
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
