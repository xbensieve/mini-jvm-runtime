package dev.ben.minijvm.debug;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.interpreter.BytecodeDecoder;
import dev.ben.minijvm.interpreter.Interpreter;
import dev.ben.minijvm.runtime.ClassRepository;
import dev.ben.minijvm.runtime.ExceptionTableResolver;
import dev.ben.minijvm.runtime.FieldResolver;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Heap;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicTraceTest {

    private Interpreter createInterpreter(ClassRepository repo, Heap heap) {
        return new Interpreter(
                new BytecodeDecoder(),
                new MethodResolver(repo),
                new MethodSelector(repo),
                new FieldResolver(repo),
                heap,
                new ExceptionTableResolver(repo)
        );
    }

    @Test
    @DisplayName("Captures deterministic instruction trace for arithmetic fixture")
    void testArithmeticInstructionTrace() {
        String src = """
                package dev.ben.test;
                public class ArithmeticFixture {
                    public static int calculate() {
                        int a = 10;
                        int b = 20;
                        return a + b;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("dev.ben.test.ArithmeticFixture", src);
        ClassFile cf = ClassFileReader.read(bytes);
        ClassRepository repo = new ClassRepository();
        repo.register(cf);
        Heap heap = new Heap(repo);
        Interpreter interpreter = createInterpreter(repo, heap);

        MethodInfo method = cf.methods().stream()
                .filter(m -> m.name(cf.constantPool()).equals("calculate"))
                .findFirst().orElseThrow();

        Frame frame = new Frame(cf, method);
        FrameStack stack = new FrameStack();
        stack.push(frame);

        TraceLogger logger = new TraceLogger(TraceLevel.INSTRUCTIONS);
        interpreter.setListener(logger);
        interpreter.execute(stack);

        String trace = logger.toTraceString();
        String expected = """
                0: bipush 10
                2: istore_0
                3: bipush 20
                5: istore_1
                6: iload_0
                7: iload_1
                8: iadd
                9: ireturn
                """;

        // Normalize line endings
        assertEquals(expected.replace("\r\n", "\n"), trace.replace("\r\n", "\n"));
        assertEquals(Value.ofInt(30), frame.returnValue().orElseThrow());
    }

    @Test
    @DisplayName("Captures deterministic loop execution trace across repeated runs")
    void testLoopInstructionTraceDeterminism() {
        String src = """
                package dev.ben.test;
                public class LoopFixture {
                    public static int sum() {
                        int total = 0;
                        for (int i = 0; i < 3; i++) {
                            total += i;
                        }
                        return total;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("dev.ben.test.LoopFixture", src);
        ClassFile cf = ClassFileReader.read(bytes);

        String firstRunTrace = null;

        for (int run = 0; run < 5; run++) {
            ClassRepository repo = new ClassRepository();
            repo.register(cf);
            Heap heap = new Heap(repo);
            Interpreter interpreter = createInterpreter(repo, heap);

            MethodInfo method = cf.methods().stream()
                    .filter(m -> m.name(cf.constantPool()).equals("sum"))
                    .findFirst().orElseThrow();

            Frame frame = new Frame(cf, method);
            FrameStack stack = new FrameStack();
            stack.push(frame);

            TraceLogger logger = new TraceLogger(TraceLevel.INSTRUCTIONS);
            interpreter.setListener(logger);
            interpreter.execute(stack);

            String trace = logger.toTraceString();
            if (firstRunTrace == null) {
                firstRunTrace = trace;
            } else {
                assertEquals(firstRunTrace, trace, "Trace output must be bit-for-bit identical across runs");
            }
            assertEquals(Value.ofInt(3), frame.returnValue().orElseThrow());
        }

        String expectedLoopGoldenMaster = """
                0: iconst_0
                1: istore_0
                2: iconst_0
                3: istore_1
                4: iload_1
                5: iconst_3
                6: if_icmpge +13 -> 19
                9: iload_0
                10: iload_1
                11: iadd
                12: istore_0
                13: iinc 1 by 1
                16: goto -12 -> 4
                4: iload_1
                5: iconst_3
                6: if_icmpge +13 -> 19
                9: iload_0
                10: iload_1
                11: iadd
                12: istore_0
                13: iinc 1 by 1
                16: goto -12 -> 4
                4: iload_1
                5: iconst_3
                6: if_icmpge +13 -> 19
                9: iload_0
                10: iload_1
                11: iadd
                12: istore_0
                13: iinc 1 by 1
                16: goto -12 -> 4
                4: iload_1
                5: iconst_3
                6: if_icmpge +13 -> 19
                19: iload_0
                20: ireturn
                """;

        assertEquals(expectedLoopGoldenMaster.replace("\r\n", "\n"), firstRunTrace.replace("\r\n", "\n"));
    }

    @Test
    @DisplayName("Captures detailed trace with state snapshots across call stack transitions")
    void testDetailedTraceWithCallStack() {
        String src = """
                package dev.ben.test;
                public class CallChainFixture {
                    public static int start() {
                        return doubleIt(21);
                    }
                    public static int doubleIt(int x) {
                        return x * 2;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("dev.ben.test.CallChainFixture", src);
        ClassFile cf = ClassFileReader.read(bytes);
        ClassRepository repo = new ClassRepository();
        repo.register(cf);
        Heap heap = new Heap(repo);
        Interpreter interpreter = createInterpreter(repo, heap);

        MethodInfo startMethod = cf.methods().stream()
                .filter(m -> m.name(cf.constantPool()).equals("start"))
                .findFirst().orElseThrow();

        Frame frame = new Frame(cf, startMethod);
        FrameStack stack = new FrameStack();
        stack.push(frame);

        TraceLogger logger = new TraceLogger(TraceLevel.DETAILED);
        interpreter.setListener(logger);
        interpreter.execute(stack);

        String trace = logger.toTraceString();

        // Must capture call stack push and pop events
        assertTrue(trace.contains("[Call Stack] PUSH -> doubleIt (depth 2)"));
        assertTrue(trace.contains("[Call Stack] POP <- doubleIt (depth 1)"));

        // Must capture frame state dumps
        assertTrue(trace.contains("FrameStack depth: 1"));
        assertTrue(trace.contains("FrameStack depth: 2"));
        assertTrue(trace.contains("Method: dev/ben/test/CallChainFixture.doubleIt(I)I"));
        assertTrue(trace.contains("OperandStack (1/"));
        assertTrue(trace.contains("IntValue[42]"));

        assertEquals(Value.ofInt(42), frame.returnValue().orElseThrow());
    }

    @Test
    @DisplayName("Captures execution fault in trace upon arithmetic exception")
    void testTraceOnFault() {
        String src = """
                package dev.ben.test;
                public class FaultFixture {
                    public static int divideByZero() {
                        int a = 10;
                        int b = 0;
                        return a / b;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("dev.ben.test.FaultFixture", src);
        ClassFile cf = ClassFileReader.read(bytes);
        ClassRepository repo = new ClassRepository();
        repo.register(cf);
        Heap heap = new Heap(repo);
        Interpreter interpreter = createInterpreter(repo, heap);

        MethodInfo method = cf.methods().stream()
                .filter(m -> m.name(cf.constantPool()).equals("divideByZero"))
                .findFirst().orElseThrow();

        Frame frame = new Frame(cf, method);
        FrameStack stack = new FrameStack();
        stack.push(frame);

        TraceLogger logger = new TraceLogger(TraceLevel.INSTRUCTIONS);
        interpreter.setListener(logger);

        boolean faulted = false;
        try {
            interpreter.execute(stack);
        } catch (Exception e) {
            faulted = true;
        }

        assertTrue(faulted);
        String trace = logger.toTraceString();
        assertTrue(trace.contains("idiv"));
        assertTrue(trace.contains("[Execution Fault]"));
        assertTrue(trace.contains("ArithmeticFaultException: / by zero"));
    }
}
