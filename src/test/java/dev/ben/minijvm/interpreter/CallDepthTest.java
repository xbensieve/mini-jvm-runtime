package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CallDepthTest {

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
    @DisplayName("Single call depth: caller -> callee (depth 2)")
    void testSingleCallDepth() {
        testChainedCalls(1);
    }

    @Test
    @DisplayName("5-level chained call depth: A0 -> A1 -> A2 -> A3 -> A4 -> A5 (depth 6)")
    void testFiveCallDepth() {
        testChainedCalls(5);
    }

    @Test
    @DisplayName("20-level chained call depth: A0 -> ... -> A20 (depth 21)")
    void testTwentyCallDepth() {
        testChainedCalls(20);
    }

    private void testChainedCalls(int chainLength) {
        // Build a class with chainLength + 1 methods: m0, m1, ..., mN
        // m_i calls m_{i+1}, receives result, adds 1, returns.
        // mN returns 42.
        // Result of m0 should be 42 + chainLength.

        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Chain"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Chain"));                      // #2
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                        // #3 descriptor ()I

        // Add method names and MethodRefs
        // For each i in 0..chainLength:
        // Utf8 name "m" + i
        // NameAndType "m" + i, ()I
        // MethodRef #1, NameAndType
        int[] methodRefIndices = new int[chainLength + 1];
        for (int i = 0; i <= chainLength; i++) {
            int nameIdx = cpEntries.size();
            cpEntries.add(new ConstantPoolEntry.Utf8Entry("m" + i));
            int natIdx = cpEntries.size();
            cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(nameIdx, 3));
            int mrefIdx = cpEntries.size();
            cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, natIdx));
            methodRefIndices[i] = mrefIdx;
        }
        ConstantPool cp = new ConstantPool(cpEntries);

        List<MethodInfo> methods = new ArrayList<>();
        for (int i = 0; i <= chainLength; i++) {
            byte[] code;
            if (i < chainLength) {
                // invokestatic m_{i+1}; iconst_1; iadd; ireturn
                int nextRef = methodRefIndices[i + 1];
                code = new byte[]{
                        (byte) 0xB8, (byte) ((nextRef >> 8) & 0xFF), (byte) (nextRef & 0xFF),
                        0x04, // iconst_1
                        0x60, // iadd
                        (byte) 0xAC // ireturn
                };
            } else {
                // leaf method: bipush 42; ireturn
                code = new byte[]{
                        0x10, 42,
                        (byte) 0xAC
                };
            }
            int nameIdx = 4 + (i * 3);
            MethodInfo method = new MethodInfo(
                    AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                    nameIdx, 3, List.of(),
                    new CodeAttribute(4, 4, code, List.of(), List.of())
            );
            methods.add(method);
        }

        ClassFile cf = buildClassFile("Chain", methods, cp);
        repository.register(cf);

        Frame rootFrame = new Frame(cf, methods.get(0));
        FrameStack frameStack = new FrameStack(100);
        frameStack.push(rootFrame);

        interpreter.execute(frameStack);

        assertTrue(rootFrame.isReturned());
        int expectedResult = 42 + chainLength;
        assertEquals(expectedResult, rootFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Recursive countdown: countdown(20) returns 20 using guest FrameStack")
    void testRecursiveCountdown() {
        // static int count(int n):
        // 0: iload_0
        // 1: ifle +11 (to pc 12)
        // 4: iload_0
        // 5: iconst_1
        // 6: isub
        // 7: invokestatic #count
        // 10: iconst_1
        // 11: iadd
        // 12: ireturn
        // (when n <= 0, returns 0; otherwise 1 + count(n - 1))

        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Recursion"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Recursion"));                  // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 count:(I)I
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("count"));                      // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("(I)I"));                       // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 Recursion.count:(I)I
        ConstantPool cp = new ConstantPool(cpEntries);

        byte[] code = new byte[]{
                (byte) 0x1A,             // 0: iload_0
                (byte) 0x9E, 0x00, 0x0C, // 1: ifle +12 -> PC 13
                (byte) 0x1A,             // 4: iload_0
                (byte) 0x1A,             // 5: iload_0
                0x04,                    // 6: iconst_1
                0x64,                    // 7: isub
                (byte) 0xB8, 0x00, 0x06, // 8: invokestatic #6 (Recursion.count)
                0x60,                    // 11: iadd
                (byte) 0xAC,             // 12: ireturn
                0x03,                    // 13: iconst_0
                (byte) 0xAC              // 14: ireturn
        };

        MethodInfo countMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(4, 4, code, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("Recursion", List.of(countMethod), cp);
        repository.register(cf);

        Frame rootFrame = new Frame(cf, countMethod);
        rootFrame.locals().setInt(0, 20); // n = 20

        FrameStack frameStack = new FrameStack(100);
        frameStack.push(rootFrame);

        interpreter.execute(frameStack);

        assertTrue(rootFrame.isReturned());
        // sum(20) = 20 * 21 / 2 = 210
        assertEquals(210, rootFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Exceeding FrameStack maxDepth throws StackFaultException with Call stack overflow")
    void testFrameStackOverflow() {
        // recursive infinite loop: inf() -> invokestatic inf
        List<ConstantPoolEntry> cpEntries = new ArrayList<>();
        cpEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        cpEntries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1 Class "Loop"
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("Loop"));                       // #2
        cpEntries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));                  // #3 inf:()V
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("inf"));                        // #4
        cpEntries.add(new ConstantPoolEntry.Utf8Entry("()V"));                        // #5
        cpEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));                    // #6 Loop.inf:()V
        ConstantPool cp = new ConstantPool(cpEntries);

        byte[] code = new byte[]{
                (byte) 0xB8, 0x00, 0x06, // 0: invokestatic #6
                (byte) 0xB1              // 3: return
        };

        MethodInfo infMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC,
                4, 5, List.of(),
                new CodeAttribute(2, 2, code, List.of(), List.of())
        );

        ClassFile cf = buildClassFile("Loop", List.of(infMethod), cp);
        repository.register(cf);

        Frame rootFrame = new Frame(cf, infMethod);
        FrameStack frameStack = new FrameStack(5); // max depth 5
        frameStack.push(rootFrame);

        StackFaultException ex = assertThrows(StackFaultException.class, () -> interpreter.execute(frameStack));
        assertTrue(ex.getMessage().contains("Call stack overflow"), "Expected call stack overflow message but got: " + ex.getMessage());
    }
}
