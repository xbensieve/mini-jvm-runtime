package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvocationInterpreterTest {

    private ClassRepository repository;
    private MethodResolver resolver;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        resolver = new MethodResolver(repository);
        interpreter = new Interpreter(new BytecodeDecoder(), resolver);
    }

    private ClassFile buildClassFile(String className, List<MethodInfo> methods, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1,
                0,
                List.of(),
                List.of(),
                methods,
                List.of()
        );
    }

    @Test
    @DisplayName("invokestatic void no-arg method: ()V")
    void testInvokeStaticNoArgVoid() {
        // Class A:
        // caller(): 0: invokestatic #callee; 3: return
        // callee(): 0: return
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "A"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("A"));                          // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 NameAndType callee:()V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("callee"));                     // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));                        // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef A.callee:()V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("caller"));                     // #7
        ConstantPool cp = new ConstantPool(cpEntries);

        byte[] calleeCode = new byte[]{(byte) 0xB1}; // return
        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, calleeCode, List.of(), List.of())
        );

        byte[] callerCode = new byte[]{
                (byte) 0xB8, 0x00, 0x06, // 0: invokestatic #6
                (byte) 0xB1              // 3: return
        };
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                7, 5, List.of(),
                new CodeAttribute(2, 2, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("A", List.of(callerMethod, calleeMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        // Step 1: caller executes invokestatic #6
        assertEquals(1, frameStack.depth());
        assertEquals(0, callerFrame.pc());

        interpreter.step(callerFrame, frameStack);

        // Invariant: caller PC was advanced to 3 and remains at 3
        assertEquals(3, callerFrame.pc());
        assertEquals(0, callerFrame.lastInstructionPc());

        // Invariant: callee frame was pushed, callee PC starts at 0
        assertEquals(2, frameStack.depth());
        Frame calleeFrame = frameStack.current();
        assertNotSame(callerFrame, calleeFrame);
        assertEquals(0, calleeFrame.pc());
        assertEquals("callee", calleeFrame.method().name(cp));

        // Step 2: callee executes return
        interpreter.step(calleeFrame, frameStack);

        // Invariant: callee returned and was popped from frameStack
        assertTrue(calleeFrame.isReturned());
        assertEquals(1, frameStack.depth());
        assertSame(callerFrame, frameStack.current());

        // Invariant: caller PC has NOT advanced a second time!
        assertEquals(3, callerFrame.pc());

        // Step 3: caller executes return
        interpreter.step(callerFrame, frameStack);
        assertTrue(callerFrame.isReturned());
        assertEquals(0, frameStack.depth());
    }

    @Test
    @DisplayName("invokestatic with multiple arguments: verify descriptor order in callee locals and return value propagation")
    void testInvokeStaticMultiArgsAndReturn() {
        // caller(): bipush 10; bipush 20; invokestatic #add; istore_0; return
        // add(int a, int b): iload_0; iload_1; iadd; ireturn
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Calc"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Calc"));                       // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 NameAndType add:(II)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("add"));                        // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("(II)I"));                      // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef Calc.add:(II)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("main"));                       // #7
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));                        // #8
        ConstantPool cp = new ConstantPool(cpEntries);

        byte[] addCode = new byte[]{
                (byte) 0x1A,       // 0: iload_0
                (byte) 0x1B,       // 1: iload_1
                (byte) 0x60,       // 2: iadd
                (byte) 0xAC        // 3: ireturn
        };
        MethodInfo addMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(4, 4, addCode, List.of(), List.of())
        );

        byte[] mainCode = new byte[]{
                0x10, 10,                 // 0: bipush 10
                0x10, 20,                 // 2: bipush 20
                (byte) 0xB8, 0x00, 0x06, // 4: invokestatic #6
                (byte) 0x3B,             // 7: istore_0
                (byte) 0xB1              // 8: return
        };
        MethodInfo mainMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                7, 8, List.of(),
                new CodeAttribute(4, 4, mainCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("Calc", List.of(mainMethod, addMethod), cp);
        repository.register(cf);

        Frame mainFrame = new Frame(cf, mainMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(mainFrame);

        // Execute full program
        interpreter.execute(frameStack);

        assertTrue(mainFrame.isReturned());
        // Verify result 10 + 20 = 30 stored in main's local 0
        assertEquals(30, mainFrame.locals().getInt(0));
    }

    @Test
    @DisplayName("Caller PC invariant: caller PC remains at sequentially advanced location during and after callee return")
    void testCallerPcProgressionInvariant() {
        // caller():
        //   0: bipush 7
        //   2: invokestatic #doubleIt
        //   5: bipush 3
        //   7: iadd
        //   8: ireturn
        // doubleIt(int x):
        //   0: iload_0
        //   1: iconst_2
        //   2: imul
        //   3: ireturn
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Math"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Math"));                       // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 doubleIt:(I)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("doubleIt"));                   // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("(I)I"));                       // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef Math.doubleIt:(I)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("caller"));                     // #7
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                        // #8
        ConstantPool cp = new ConstantPool(cpEntries);

        byte[] doubleItCode = new byte[]{
                (byte) 0x1A, // 0: iload_0
                0x05,        // 1: iconst_2
                0x68,        // 2: imul
                (byte) 0xAC  // 3: ireturn
        };
        MethodInfo doubleItMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(4, 4, doubleItCode, List.of(), List.of())
        );

        byte[] callerCode = new byte[]{
                0x10, 7,                  // 0: bipush 7
                (byte) 0xB8, 0x00, 0x06, // 2: invokestatic #6 (length 3, next PC = 5)
                0x10, 3,                  // 5: bipush 3
                0x60,                     // 7: iadd
                (byte) 0xAC              // 8: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                7, 8, List.of(),
                new CodeAttribute(4, 4, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("Math", List.of(callerMethod, doubleItMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        // Step 1: bipush 7
        interpreter.step(callerFrame, frameStack);
        assertEquals(2, callerFrame.pc());

        // Step 2: invokestatic #6
        interpreter.step(callerFrame, frameStack);
        assertEquals(5, callerFrame.pc(), "Caller PC must be advanced past invokestatic (2 + 3 = 5)");
        assertEquals(2, callerFrame.lastInstructionPc());

        Frame calleeFrame = frameStack.current();
        assertEquals(0, calleeFrame.pc());
        assertEquals(7, calleeFrame.locals().getInt(0));

        // Step callee to completion:
        interpreter.step(calleeFrame, frameStack); // iload_0
        interpreter.step(calleeFrame, frameStack); // iconst_2
        interpreter.step(calleeFrame, frameStack); // imul
        interpreter.step(calleeFrame, frameStack); // ireturn (returns 14)

        assertTrue(calleeFrame.isReturned());
        assertEquals(1, frameStack.depth());
        assertSame(callerFrame, frameStack.current());

        // CRITICAL INVARIANT: Caller PC remains at 5!
        assertEquals(5, callerFrame.pc(), "Caller PC must NOT advance again on return");
        assertEquals(1, callerFrame.operandStack().slots());
        assertEquals(14, callerFrame.operandStack().peek().asInt());

        // Continue stepping caller:
        interpreter.step(callerFrame, frameStack); // 5: bipush 3
        assertEquals(7, callerFrame.pc());

        interpreter.step(callerFrame, frameStack); // 7: iadd
        assertEquals(8, callerFrame.pc());
        assertEquals(17, callerFrame.operandStack().peek().asInt());

        interpreter.step(callerFrame, frameStack); // 8: ireturn
        assertTrue(callerFrame.isReturned());
        assertEquals(17, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("invokevirtual: receiver passed in local[0], parameters in local[1..N]")
    void testInvokeVirtualParameterPlacement() {
        // instanceMethod(int x): local[0] is receiver, local[1] is x
        // returns local[1] * 2
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Service"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Service"));                    // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 doWork:(I)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("doWork"));                     // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("(I)I"));                       // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef Service.doWork:(I)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("caller"));                     // #7
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                        // #8
        ConstantPool cp = new ConstantPool(cpEntries);

        byte[] calleeCode = new byte[]{
                (byte) 0x1B, // 0: iload_1 (parameter x, NOT receiver!)
                0x05,        // 1: iconst_2
                0x68,        // 2: imul
                (byte) 0xAC  // 3: ireturn
        };
        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC, // NOT static
                4, 5, List.of(),
                new CodeAttribute(4, 4, calleeCode, List.of(), List.of())
        );

        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x06, // 0: invokevirtual #6
                (byte) 0xAC              // 3: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                7, 8, List.of(),
                new CodeAttribute(4, 4, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("Service", List.of(callerMethod, calleeMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        // Push receiver then argument x onto caller stack
        callerFrame.operandStack().push(Value.ofReference(42, "Service")); // receiver handle @42 of class Service
        callerFrame.operandStack().push(Value.ofInt(15));        // arg x = 15

        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        // Step invokevirtual
        interpreter.step(callerFrame, frameStack);

        Frame calleeFrame = frameStack.current();
        assertNotSame(callerFrame, calleeFrame);
        // Verify local[0] is receiver, local[1] is 15
        assertTrue(calleeFrame.locals().get(0).isReference());
        assertEquals(42, ((ObjectReference) calleeFrame.locals().get(0)).handle());
        assertEquals(15, calleeFrame.locals().getInt(1));

        // Finish execution
        interpreter.execute(frameStack);
        assertTrue(callerFrame.isReturned());
        assertEquals(30, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("invokevirtual on null receiver throws StackFaultException")
    void testInvokeVirtualOnNullReceiverThrows() {
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Service"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Service"));                    // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 doWork:()V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("doWork"));                     // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));                        // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef Service.doWork:()V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("caller"));                     // #7
        ConstantPool cp = new ConstantPool(cpEntries);

        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, new byte[]{(byte) 0xB1}, List.of(), List.of())
        );

        byte[] callerCode = new byte[]{(byte) 0xB6, 0x00, 0x06}; // invokevirtual #6
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                7, 5, List.of(),
                new CodeAttribute(2, 2, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("Service", List.of(callerMethod, calleeMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        callerFrame.operandStack().push(Value.nullRef()); // null receiver!

        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        assertThrows(StackFaultException.class, () -> interpreter.step(callerFrame, frameStack));
    }

    @Test
    @DisplayName("invokestatic without FrameStack context throws StackFaultException")
    void testInvokeStaticWithoutFrameStackThrows() {
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "A"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("A"));                          // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 foo:()V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("foo"));                        // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));                        // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef A.foo:()V
        ConstantPool cp = new ConstantPool(cpEntries);

        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, new byte[]{(byte) 0xB1}, List.of(), List.of())
        );

        byte[] callerCode = new byte[]{(byte) 0xB8, 0x00, 0x06}; // invokestatic #6
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("A", List.of(callerMethod, calleeMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);

        // step(frame) passes null frameStack
        assertThrows(StackFaultException.class, () -> interpreter.step(callerFrame));
    }

    @Test
    @DisplayName("Stack underflow when popping invocation arguments throws StackFaultException")
    void testInvocationStackUnderflowThrows() {
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "A"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("A"));                          // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 add:(II)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("add"));                        // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("(II)I"));                      // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef A.add:(II)I
        ConstantPool cp = new ConstantPool(cpEntries);

        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, new byte[]{(byte) 0xAC}, List.of(), List.of())
        );

        // Caller has only 1 value on stack, but add expects 2!
        byte[] callerCode = new byte[]{0x10, 42, (byte) 0xB8, 0x00, 0x06};
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("A", List.of(callerMethod, calleeMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        interpreter.step(callerFrame, frameStack); // bipush 42
        // invokestatic should underflow:
        assertThrows(StackFaultException.class, () -> interpreter.step(callerFrame, frameStack));
    }

    @Test
    @DisplayName("Argument type mismatch on invocation throws StackFaultException")
    void testInvocationArgumentTypeMismatchThrows() {
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "A"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("A"));                          // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 takesInt:(I)V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("takesInt"));                   // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("(I)V"));                       // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 MethodRef A.takesInt:(I)V
        ConstantPool cp = new ConstantPool(cpEntries);

        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, new byte[]{(byte) 0xB1}, List.of(), List.of())
        );

        // Caller pushes null reference instead of int
        byte[] callerCode = new byte[]{0x01, (byte) 0xB8, 0x00, 0x06};
        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, callerCode, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("A", List.of(callerMethod, calleeMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        interpreter.step(callerFrame, frameStack); // aconst_null
        // invokestatic expects int, got null reference!
        assertThrows(StackFaultException.class, () -> interpreter.step(callerFrame, frameStack));
    }
}
