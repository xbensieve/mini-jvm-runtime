package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.ExceptionTableEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.GuestExecutionException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.ClassRepository;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.FrameStatus;
import dev.ben.minijvm.runtime.GuestObject;
import dev.ben.minijvm.runtime.Heap;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.ObjectReference;
import dev.ben.minijvm.runtime.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionInterpreterTest {

    private ClassRepository repository;
    private Interpreter interpreter;
    private Heap heap;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        heap = new Heap(repository);
        interpreter = new Interpreter(
                new BytecodeDecoder(),
                new MethodResolver(repository),
                new MethodSelector(repository),
                new dev.ben.minijvm.runtime.FieldResolver(repository),
                heap
        );
    }

    private ConstantPool createConstantPool(String... classNames) {
        List<ConstantPoolEntry> entries = new java.util.ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("Slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("testMethod")); // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));        // #2

        int index = 3;
        for (String className : classNames) {
            entries.add(new ConstantPoolEntry.Utf8Entry(className)); // #index
            entries.add(new ConstantPoolEntry.ClassEntry(index));     // #(index+1)
            index += 2;
        }

        return new ConstantPool(entries);
    }

    private Frame createFrame(byte[] code, List<ExceptionTableEntry> exTable, ConstantPool cp) {
        CodeAttribute codeAttr = new CodeAttribute(8, 8, code, exTable, List.of());
        MethodInfo method = new MethodInfo(0x0001, 1, 2, List.of(), codeAttr);
        return new Frame(method, cp, 8, 8);
    }

    @Test
    @DisplayName("athrow with local catch handler clears stack, pushes exception, and jumps to handlerPc")
    void testAthrowLocalCatch() {
        ConstantPool cp = createConstantPool("com/example/MyException"); // ClassEntry at #4
        // Bytecode:
        // 0: athrow (0xBF)
        // 1: nop
        // 2: return (0xB1) - handlerPc
        byte[] code = new byte[]{(byte) 0xBF, 0x00, (byte) 0xB1};
        ExceptionTableEntry handler = new ExceptionTableEntry(0, 1, 2, 4);
        Frame frame = createFrame(code, List.of(handler), cp);

        // Put an exception reference and a dummy value on stack
        ObjectReference exRef = Value.ofReference(42L, "com/example/MyException");
        frame.operandStack().push(Value.ofInt(99)); // extra value that should be cleared
        frame.operandStack().push(exRef);

        // Step through athrow
        interpreter.step(frame);

        // Frame should have jumped to PC 2 (handlerPc)
        assertEquals(2, frame.pc());
        // Frame operand stack should contain exactly the caught exception reference
        assertEquals(1, frame.operandStack().valueCount());
        assertEquals(exRef, frame.operandStack().peek());
        assertEquals(FrameStatus.RUNNING, frame.status());
    }

    @Test
    @DisplayName("athrow with finally handler (catchType 0) catches any exception")
    void testAthrowFinallyCatch() {
        ConstantPool cp = createConstantPool("com/example/AnyException");
        // Bytecode:
        // 0: athrow (0xBF)
        // 1: return (0xB1) - handlerPc
        byte[] code = new byte[]{(byte) 0xBF, (byte) 0xB1};
        ExceptionTableEntry finallyHandler = new ExceptionTableEntry(0, 1, 1, 0);
        Frame frame = createFrame(code, List.of(finallyHandler), cp);

        ObjectReference exRef = Value.ofReference(101L, "com/example/UnregisteredException");
        frame.operandStack().push(exRef);

        interpreter.step(frame);

        assertEquals(1, frame.pc());
        assertEquals(1, frame.operandStack().valueCount());
        assertEquals(exRef, frame.operandStack().peek());
    }

    @Test
    @DisplayName("athrow without local handler unwinds call stack to caller handler")
    void testAthrowUnwindsToCaller() {
        ConstantPool cp = createConstantPool("com/example/MyException"); // ClassEntry at #4

        // Caller method bytecode:
        // 0: invokestatic #method (3 bytes)
        // 3: return (1 byte)
        // 4: return (1 byte) - handlerPc
        byte[] callerCode = new byte[]{(byte) 0xB8, 0x00, 0x01, (byte) 0xB1, (byte) 0xB1};
        ExceptionTableEntry callerCatch = new ExceptionTableEntry(0, 3, 4, 4);
        Frame caller = createFrame(callerCode, List.of(callerCatch), cp);

        // Callee method bytecode:
        // 0: athrow (0xBF)
        byte[] calleeCode = new byte[]{(byte) 0xBF};
        Frame callee = createFrame(calleeCode, List.of(), cp); // no handler in callee

        FrameStack frameStack = new FrameStack();
        frameStack.push(caller);
        frameStack.push(callee);

        // Set caller PC to 3 and lastInstructionPc to 0 (as if invokestatic was executed)
        caller.setPc(3);
        caller.setLastInstructionPc(0);

        ObjectReference exRef = Value.ofReference(77L, "com/example/MyException");
        callee.operandStack().push(exRef);

        // Step callee (athrow)
        interpreter.step(callee, frameStack);

        // Callee should be popped, caller should now be current
        assertEquals(1, frameStack.depth());
        assertSame(caller, frameStack.current());
        // Caller should be at handlerPc 4
        assertEquals(4, caller.pc());
        // Caller stack should contain the exception
        assertEquals(1, caller.operandStack().valueCount());
        assertEquals(exRef, caller.operandStack().peek());
        assertEquals(FrameStatus.RUNNING, caller.status());
    }

    @Test
    @DisplayName("athrow without any handler across call stack throws GuestExecutionException")
    void testUncaughtExceptionThrowsGuestExecutionException() {
        ConstantPool cp = createConstantPool("com/example/FatalError");
        byte[] code = new byte[]{(byte) 0xBF}; // athrow
        Frame frame = createFrame(code, List.of(), cp); // no handler

        ObjectReference exRef = Value.ofReference(999L, "com/example/FatalError");
        frame.operandStack().push(exRef);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        GuestExecutionException ex = assertThrows(
                GuestExecutionException.class,
                () -> interpreter.execute(frameStack)
        );

        assertSame(exRef, ex.guestException());
        assertEquals("com/example/FatalError", ex.exceptionClassName());
        assertTrue(ex.getMessage().contains("FatalError"));
        assertTrue(ex.getMessage().contains("athrow"));
    }

    @Test
    @DisplayName("athrow with null reference throws StackFaultException")
    void testAthrowNullThrows() {
        ConstantPool cp = createConstantPool();
        byte[] code = new byte[]{(byte) 0xBF}; // athrow
        Frame frame = createFrame(code, List.of(), cp);

        frame.operandStack().push(Value.nullRef());

        StackFaultException ex = assertThrows(
                StackFaultException.class,
                () -> interpreter.step(frame)
        );
        assertTrue(ex.getMessage().contains("Null pointer"));
    }

    @Test
    @DisplayName("athrow with primitive int on stack throws StackFaultException")
    void testAthrowPrimitiveThrows() {
        ConstantPool cp = createConstantPool();
        byte[] code = new byte[]{(byte) 0xBF}; // athrow
        Frame frame = createFrame(code, List.of(), cp);

        frame.operandStack().push(Value.ofInt(123));

        StackFaultException ex = assertThrows(
                StackFaultException.class,
                () -> interpreter.step(frame)
        );
        assertTrue(ex.getMessage().contains("requires object reference"));
    }

    @Test
    @DisplayName("Conditional reference branch ifnull / ifnonnull")
    void testIfNullAndIfNonNull() {
        ConstantPool cp = createConstantPool();
        // 0: ifnull +4 (branch to 4) -> 0xC6, 0x00, 0x04
        // 3: nop
        // 4: return
        byte[] codeNull = new byte[]{(byte) 0xC6, 0x00, 0x04, 0x00, (byte) 0xB1};
        Frame frameNull = createFrame(codeNull, List.of(), cp);
        frameNull.operandStack().push(Value.nullRef());
        interpreter.step(frameNull);
        assertEquals(4, frameNull.pc(), "ifnull on null reference should branch to 4");

        // ifnull on non-null should fall through to 3
        Frame frameNotNull = createFrame(codeNull, List.of(), cp);
        frameNotNull.operandStack().push(Value.ofReference(1L));
        interpreter.step(frameNotNull);
        assertEquals(3, frameNotNull.pc(), "ifnull on non-null reference should fall through to 3");

        // 0: ifnonnull +4 (branch to 4) -> 0xC7, 0x00, 0x04
        // 3: nop
        // 4: return
        byte[] codeNonNull = new byte[]{(byte) 0xC7, 0x00, 0x04, 0x00, (byte) 0xB1};
        Frame frameNonNull = createFrame(codeNonNull, List.of(), cp);
        frameNonNull.operandStack().push(Value.ofReference(1L));
        interpreter.step(frameNonNull);
        assertEquals(4, frameNonNull.pc(), "ifnonnull on non-null reference should branch to 4");

        // ifnonnull on null should fall through to 3
        Frame frameNonNullNull = createFrame(codeNonNull, List.of(), cp);
        frameNonNullNull.operandStack().push(Value.nullRef());
        interpreter.step(frameNonNullNull);
        assertEquals(3, frameNonNullNull.pc(), "ifnonnull on null reference should fall through to 3");
    }

    @Test
    @DisplayName("Conditional reference branch if_acmpeq / if_acmpne")
    void testIfAcmpEqAndIfAcmpNe() {
        ConstantPool cp = createConstantPool();
        ObjectReference ref1 = Value.ofReference(10L);
        ObjectReference ref2 = Value.ofReference(20L);

        // 0: if_acmpeq +4 (branch to 4) -> 0xA5, 0x00, 0x04
        // 3: nop
        // 4: return
        byte[] codeEq = new byte[]{(byte) 0xA5, 0x00, 0x04, 0x00, (byte) 0xB1};
        Frame frameEq = createFrame(codeEq, List.of(), cp);
        frameEq.operandStack().push(ref1);
        frameEq.operandStack().push(ref1);
        interpreter.step(frameEq);
        assertEquals(4, frameEq.pc(), "if_acmpeq on identical references should branch to 4");

        Frame frameEqDiff = createFrame(codeEq, List.of(), cp);
        frameEqDiff.operandStack().push(ref1);
        frameEqDiff.operandStack().push(ref2);
        interpreter.step(frameEqDiff);
        assertEquals(3, frameEqDiff.pc(), "if_acmpeq on different references should fall through to 3");

        // 0: if_acmpne +4 (branch to 4) -> 0xA6, 0x00, 0x04
        byte[] codeNe = new byte[]{(byte) 0xA6, 0x00, 0x04, 0x00, (byte) 0xB1};
        Frame frameNe = createFrame(codeNe, List.of(), cp);
        frameNe.operandStack().push(ref1);
        frameNe.operandStack().push(ref2);
        interpreter.step(frameNe);
        assertEquals(4, frameNe.pc(), "if_acmpne on different references should branch to 4");

        Frame frameNeSame = createFrame(codeNe, List.of(), cp);
        frameNeSame.operandStack().push(ref1);
        frameNeSame.operandStack().push(ref1);
        interpreter.step(frameNeSame);
        assertEquals(3, frameNeSame.pc(), "if_acmpne on identical references should fall through to 3");
    }
}
