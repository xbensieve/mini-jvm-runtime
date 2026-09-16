package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VirtualDispatchTest {

    private ClassRepository repository;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        interpreter = new Interpreter(new BytecodeDecoder(), new MethodResolver(repository), new MethodSelector(repository));
    }

    private ClassFile buildClass(String className, String superClassName, List<MethodInfo> methods, ConstantPool cp) {
        int superIdx = 0;
        if (superClassName != null) {
            for (int i = 1; i <= cp.size(); i++) {
                if (cp.get(i) instanceof ConstantPoolEntry.ClassEntry ce) {
                    if (superClassName.equals(cp.getUtf8(ce.nameIndex()))) {
                        superIdx = i;
                        break;
                    }
                }
            }
        }
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1, // thisClassIndex points to #1 ClassEntry
                superIdx,
                List.of(),
                List.of(),
                methods,
                List.of()
        );
    }

    @Test
    @DisplayName("1. Base receiver executes Base implementation")
    void testBaseReceiverExecutesBaseImplementation() {
        // Base class with int getVal() { return 10; }
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Base
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                      // #2
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));                          // #3 Object
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));          // #4
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));                 // #5 getVal:()I
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("getVal"));                    // #6
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #7
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.getVal:()I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        // bipush 10, ireturn
        byte[] baseCode = new byte[]{(byte) 0x10, 0x0A, (byte) 0xAC};
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, baseCode, List.of(), List.of()));
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Caller executing: 0: invokevirtual #8; 3: ireturn
        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0xAC              // 3: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        callerFrame.operandStack().push(Value.ofReference(101L, "Base"));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);

        interpreter.execute(stack);

        assertTrue(callerFrame.isReturned());
        assertEquals(10, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("2. Derived receiver executes overridden Derived implementation")
    void testDerivedReceiverExecutesOverriddenDerived() {
        // Base class: getVal() returns 10
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Base
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                      // #2
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));                          // #3 Object
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));          // #4
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));                 // #5 getVal:()I
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("getVal"));                    // #6
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #7
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.getVal:()I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        byte[] baseCode = new byte[]{(byte) 0x10, 0x0A, (byte) 0xAC}; // return 10
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, baseCode, List.of(), List.of()));
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Derived class: getVal() returns 20
        List<ConstantPoolEntry> derivedCpEntries = new ArrayList<>();
        derivedCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(2));                       // #1 Derived
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));                // #2
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(4));                       // #3 Base
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                   // #4
        derivedCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));              // #5 getVal:()I
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("getVal"));                 // #6
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                    // #7
        ConstantPool derivedCp = new ConstantPool(derivedCpEntries);

        byte[] derivedCode = new byte[]{(byte) 0x10, 0x14, (byte) 0xAC}; // return 20
        MethodInfo derivedMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, derivedCode, List.of(), List.of()));
        ClassFile derivedClass = buildClass("Derived", "Base", List.of(derivedMethod), derivedCp);
        repository.register(derivedClass);

        // Caller compiled against Base (symbolic MethodRef #8: Base.getVal:()I)
        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0xAC              // 3: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        // Receiver runtime class is "Derived"
        callerFrame.operandStack().push(Value.ofReference(202L, "Derived"));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);

        interpreter.execute(stack);

        assertTrue(callerFrame.isReturned());
        // Must be 20 from Derived, NOT 10 from Base
        assertEquals(20, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("3. Multi-level hierarchy: Base -> Mid -> Derived")
    void testMultiLevelHierarchyDispatch() {
        // Base: calc() -> 1
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("calc"));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.calc:()I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, new byte[]{(byte) 0x04, (byte) 0xAC}, List.of(), List.of())); // iconst_1, ireturn
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Mid extends Base: overrides calc() -> 2
        List<ConstantPoolEntry> midCpEntries = new ArrayList<>();
        midCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        midCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        midCpEntries.add(new ConstantPoolEntry.Utf8Entry("Mid"));
        midCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        midCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        midCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        midCpEntries.add(new ConstantPoolEntry.Utf8Entry("calc"));
        midCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));
        ConstantPool midCp = new ConstantPool(midCpEntries);

        MethodInfo midMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, new byte[]{(byte) 0x05, (byte) 0xAC}, List.of(), List.of())); // iconst_2, ireturn
        ClassFile midClass = buildClass("Mid", "Base", List.of(midMethod), midCp);
        repository.register(midClass);

        // Derived extends Mid: does NOT override calc()
        List<ConstantPoolEntry> derivedCpEntries = new ArrayList<>();
        derivedCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Mid"));
        ConstantPool derivedCp = new ConstantPool(derivedCpEntries);

        ClassFile derivedClass = buildClass("Derived", "Mid", List.of(), derivedCp);
        repository.register(derivedClass);

        // GrandChild extends Derived: overrides calc() -> 3
        List<ConstantPoolEntry> grandCpEntries = new ArrayList<>();
        grandCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        grandCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        grandCpEntries.add(new ConstantPoolEntry.Utf8Entry("GrandChild"));
        grandCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        grandCpEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));
        grandCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        grandCpEntries.add(new ConstantPoolEntry.Utf8Entry("calc"));
        grandCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));
        ConstantPool grandCp = new ConstantPool(grandCpEntries);

        MethodInfo grandMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, new byte[]{(byte) 0x06, (byte) 0xAC}, List.of(), List.of())); // iconst_3, ireturn
        ClassFile grandClass = buildClass("GrandChild", "Derived", List.of(grandMethod), grandCp);
        repository.register(grandClass);

        // Caller calling Base.calc:()I
        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0xAC              // 3: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        // Test 3a: Receiver is Mid -> returns 2
        Frame frameMid = new Frame(baseClass, callerMethod);
        frameMid.operandStack().push(Value.ofReference(1L, "Mid"));
        FrameStack stackMid = new FrameStack();
        stackMid.push(frameMid);
        interpreter.execute(stackMid);
        assertEquals(2, frameMid.returnValue().get().asInt());

        // Test 3b: Receiver is Derived (inherits calc() from Mid) -> returns 2
        Frame frameDerived = new Frame(baseClass, callerMethod);
        frameDerived.operandStack().push(Value.ofReference(2L, "Derived"));
        FrameStack stackDerived = new FrameStack();
        stackDerived.push(frameDerived);
        interpreter.execute(stackDerived);
        assertEquals(2, frameDerived.returnValue().get().asInt());

        // Test 3c: Receiver is GrandChild -> returns 3
        Frame frameGrand = new Frame(baseClass, callerMethod);
        frameGrand.operandStack().push(Value.ofReference(3L, "GrandChild"));
        FrameStack stackGrand = new FrameStack();
        stackGrand.push(frameGrand);
        interpreter.execute(stackGrand);
        assertEquals(3, frameGrand.returnValue().get().asInt());
    }

    @Test
    @DisplayName("4. Derived with inherited non-overridden method")
    void testDerivedInheritingMethod() {
        // Base: foo() -> 42
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("foo"));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.foo:()I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, new byte[]{(byte) 0x10, 0x2A, (byte) 0xAC}, List.of(), List.of())); // bipush 42, ireturn
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Derived extends Base: no methods
        List<ConstantPoolEntry> derivedCpEntries = new ArrayList<>();
        derivedCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        ConstantPool derivedCp = new ConstantPool(derivedCpEntries);

        ClassFile derivedClass = buildClass("Derived", "Base", List.of(), derivedCp);
        repository.register(derivedClass);

        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0xAC              // 3: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        callerFrame.operandStack().push(Value.ofReference(501L, "Derived"));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);
        interpreter.execute(stack);

        assertTrue(callerFrame.isReturned());
        assertEquals(42, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("5. Different receivers using the same symbolic Methodref")
    void testDifferentReceiversSameCallsite() {
        // Base: compute() -> 100
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("compute"));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.compute:()I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, new byte[]{(byte) 0x10, 0x64, (byte) 0xAC}, List.of(), List.of())); // bipush 100, ireturn
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Derived: compute() -> 200
        List<ConstantPoolEntry> derivedCpEntries = new ArrayList<>();
        derivedCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        derivedCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("compute"));
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));
        ConstantPool derivedCp = new ConstantPool(derivedCpEntries);

        MethodInfo derivedMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, new byte[]{(byte) 0x11, 0x00, (byte) 0xC8, (byte) 0xAC}, List.of(), List.of())); // sipush 200, ireturn
        ClassFile derivedClass = buildClass("Derived", "Base", List.of(derivedMethod), derivedCp);
        repository.register(derivedClass);

        // Caller executes:
        // 0: invokevirtual #8 (pops Base ref -> 100)
        // 3: istore_0
        // 4: invokevirtual #8 (pops Derived ref -> 200)
        // 7: iload_0 (100)
        // 8: iadd (100 + 200 = 300)
        // 9: ireturn
        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0x3B,             // 3: istore_0
                (byte) 0xB6, 0x00, 0x08, // 4: invokevirtual #8
                (byte) 0x1A,             // 7: iload_0
                (byte) 0x60,             // 8: iadd
                (byte) 0xAC              // 9: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(4, 2, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        // Push Derived first (for second invokevirtual), then Base (for first invokevirtual)
        callerFrame.operandStack().push(Value.ofReference(2L, "Derived"));
        callerFrame.operandStack().push(Value.ofReference(1L, "Base"));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);
        interpreter.execute(stack);

        assertTrue(callerFrame.isReturned());
        assertEquals(300, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("6 & 7. Caller PC progression and return value propagation")
    void testCallerPcAndReturnValuePropagation() {
        // Base: mult2(int x) -> x * 2
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("mult2"));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("(I)I"));
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.mult2:(I)I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        // iload_1, iconst_2, imul, ireturn
        byte[] baseCode = new byte[]{(byte) 0x1B, (byte) 0x05, (byte) 0x68, (byte) 0xAC};
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(3, 2, baseCode, List.of(), List.of()));
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Caller:
        // 0: invokevirtual #8 (length 3: next PC is 3)
        // 3: iconst_3
        // 4: iadd (result + 3 = 10 + 3 = 13)
        // 5: ireturn
        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0x06,             // 3: iconst_3
                (byte) 0x60,             // 4: iadd
                (byte) 0xAC              // 5: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(4, 2, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        // Push receiver then argument 5 onto caller operand stack
        callerFrame.operandStack().push(Value.ofReference(77L, "Base"));
        callerFrame.operandStack().push(Value.ofInt(5));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);

        assertEquals(0, callerFrame.pc());

        // Step 0: invokevirtual #8
        interpreter.step(callerFrame, stack);
        // Caller PC must have advanced to 3 (after 3-byte opcode)
        assertEquals(3, callerFrame.pc());
        // Callee frame is now on top
        Frame calleeFrame = stack.current();
        assertNotSame(callerFrame, calleeFrame);
        assertEquals(0, calleeFrame.pc());
        assertEquals(77L, ((ObjectReference) calleeFrame.locals().get(0)).handle());
        assertEquals(5, calleeFrame.locals().getInt(1));

        // Step callee to completion
        interpreter.step(calleeFrame, stack); // iload_1
        interpreter.step(calleeFrame, stack); // iconst_2
        interpreter.step(calleeFrame, stack); // imul
        interpreter.step(calleeFrame, stack); // ireturn
        // Callee is popped, caller frame is restored on top
        assertSame(callerFrame, stack.current());
        assertEquals(3, callerFrame.pc());
        assertEquals(10, callerFrame.operandStack().peek().asInt());

        // Step 3: iconst_3
        interpreter.step(callerFrame, stack);
        assertEquals(4, callerFrame.pc());

        // Step 4: iadd
        interpreter.step(callerFrame, stack);
        assertEquals(5, callerFrame.pc());
        assertEquals(13, callerFrame.operandStack().peek().asInt());

        // Step 5: ireturn
        interpreter.step(callerFrame, stack);
        assertTrue(callerFrame.isReturned());
        assertEquals(13, callerFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Null receiver on invokevirtual throws StackFaultException")
    void testNullReceiverThrows() {
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("f"));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.f:()V
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(1, 1, new byte[]{(byte) 0xB1}, List.of(), List.of()));
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        byte[] callerCode = new byte[]{
                (byte) 0x01,             // 0: aconst_null
                (byte) 0xB6, 0x00, 0x08, // 1: invokevirtual #8
                (byte) 0xB1              // 4: return
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        FrameStack stack = new FrameStack();
        stack.push(callerFrame);

        interpreter.step(callerFrame, stack); // aconst_null
        StackFaultException ex = assertThrows(StackFaultException.class, () -> interpreter.step(callerFrame, stack));
        assertTrue(ex.getMessage().toLowerCase().contains("null receiver"));
    }

    @Test
    @DisplayName("Incompatible receiver runtime class throws LinkageException")
    void testIncompatibleReceiverThrows() {
        // Base class
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("f"));
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.f:()V
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(1, 1, new byte[]{(byte) 0xB1}, List.of(), List.of()));
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Unrelated class
        List<ConstantPoolEntry> unCpEntries = new ArrayList<>();
        unCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        unCpEntries.add(new ConstantPoolEntry.ClassEntry(2));
        unCpEntries.add(new ConstantPoolEntry.Utf8Entry("Unrelated"));
        unCpEntries.add(new ConstantPoolEntry.ClassEntry(4));
        unCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        ConstantPool unCp = new ConstantPool(unCpEntries);
        ClassFile unClass = buildClass("Unrelated", "java/lang/Object", List.of(), unCp);
        repository.register(unClass);

        byte[] callerCode = new byte[]{
                (byte) 0xB6, 0x00, 0x08, // 0: invokevirtual #8
                (byte) 0xB1              // 3: return
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        callerFrame.operandStack().push(Value.ofReference(88L, "Unrelated"));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);

        LinkageException ex = assertThrows(LinkageException.class, () -> interpreter.execute(stack));
        assertTrue(ex.getMessage().contains("not a subtype"));
    }

    @Test
    @DisplayName("invokespecial bypasses virtual dispatch even on Derived receiver")
    void testInvokeSpecialBypassesVirtualSelection() {
        // Base: getVal() returns 10
        List<ConstantPoolEntry> baseCpEntries = new ArrayList<>();
        baseCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Base
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                      // #2
        baseCpEntries.add(new ConstantPoolEntry.ClassEntry(4));                          // #3 Object
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));          // #4
        baseCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));                 // #5 getVal:()I
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("getVal"));                    // #6
        baseCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #7
        baseCpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));                   // #8 Base.getVal:()I
        ConstantPool baseCp = new ConstantPool(baseCpEntries);

        byte[] baseCode = new byte[]{(byte) 0x10, 0x0A, (byte) 0xAC}; // return 10
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, baseCode, List.of(), List.of()));
        ClassFile baseClass = buildClass("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Derived: getVal() returns 20
        List<ConstantPoolEntry> derivedCpEntries = new ArrayList<>();
        derivedCpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(2));                       // #1 Derived
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));                // #2
        derivedCpEntries.add(new ConstantPoolEntry.ClassEntry(4));                       // #3 Base
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                   // #4
        derivedCpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));              // #5 getVal:()I
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("getVal"));                 // #6
        derivedCpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                    // #7
        ConstantPool derivedCp = new ConstantPool(derivedCpEntries);

        byte[] derivedCode = new byte[]{(byte) 0x10, 0x14, (byte) 0xAC}; // return 20
        MethodInfo derivedMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, derivedCode, List.of(), List.of()));
        ClassFile derivedClass = buildClass("Derived", "Base", List.of(derivedMethod), derivedCp);
        repository.register(derivedClass);

        // Caller executing invokespecial #8 (Base.getVal:()I)
        byte[] callerCode = new byte[]{
                (byte) 0xB7, 0x00, 0x08, // 0: invokespecial #8 (0xB7)
                (byte) 0xAC              // 3: ireturn
        };
        MethodInfo callerMethod = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 6, 7, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of()));

        Frame callerFrame = new Frame(baseClass, callerMethod);
        // Receiver runtime class is "Derived"
        callerFrame.operandStack().push(Value.ofReference(303L, "Derived"));

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);

        interpreter.execute(stack);

        assertTrue(callerFrame.isReturned());
        // invokespecial directly invokes Base.getVal (returns 10), NOT Derived.getVal (returns 20)
        assertEquals(10, callerFrame.returnValue().get().asInt());
    }
}
