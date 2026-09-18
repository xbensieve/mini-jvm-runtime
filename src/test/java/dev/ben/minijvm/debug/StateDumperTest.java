package dev.ben.minijvm.debug;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.FrameStatus;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateDumperTest {

    private Frame createTestFrame() {
        String src = """
                package dev.ben.test;
                public class Fixture {
                    public static int compute(int a, int b) {
                        int c = a + b;
                        return c;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("dev.ben.test.Fixture", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.methods().stream()
                .filter(m -> m.name(cf.constantPool()).equals("compute"))
                .findFirst()
                .orElseThrow();
        return new Frame(cf, method);
    }

    @Test
    @DisplayName("Dumps initial empty frame state")
    void testDumpInitialState() {
        Frame frame = createTestFrame();
        String dump = StateDumper.dump(frame);

        assertTrue(dump.contains("FrameStack depth: 1"));
        assertTrue(dump.contains("Frame status: RUNNING"));
        assertTrue(dump.contains("Method: dev/ben/test/Fixture.compute(II)I [pc=0]"));
        assertTrue(dump.contains("OperandStack (0/"));
        assertTrue(dump.contains("LocalVariables (0/"));
        assertTrue(dump.contains("<empty>"));
    }

    @Test
    @DisplayName("Dumps populated stack and local variables")
    void testDumpPopulatedState() {
        Frame frame = createTestFrame();
        frame.locals().setInt(0, 10);
        frame.locals().setInt(1, 20);
        frame.locals().setReference(2, Value.ofReference(1L, "dev/ben/test/Fixture"));

        frame.operandStack().push(Value.ofInt(10));
        frame.operandStack().push(Value.ofInt(20));
        frame.setPc(4);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        String dump = StateDumper.dump(frame, frameStack);

        assertTrue(dump.contains("FrameStack depth: 1"));
        assertTrue(dump.contains("Frame status: RUNNING"));
        assertTrue(dump.contains("[pc=4]"));
        assertTrue(dump.contains("OperandStack (2/"));
        assertTrue(dump.contains("[IntValue[10], IntValue[20]]"));
        assertTrue(dump.contains("LocalVariables (3/"));
        assertTrue(dump.contains("0=IntValue[10]"));
        assertTrue(dump.contains("1=IntValue[20]"));
        assertTrue(dump.contains("2=ObjectReference[@1, class=dev/ben/test/Fixture]"));
    }

    @Test
    @DisplayName("Dumps state with return value")
    void testDumpWithReturnValue() {
        Frame frame = createTestFrame();
        frame.markReturned();
        frame.setReturnValue(Value.ofInt(42));

        String dump = StateDumper.dump(frame);

        assertTrue(dump.contains("Frame status: RETURNED"));
        assertTrue(dump.contains("ReturnValue: IntValue[42]"));
    }

    @Test
    @DisplayName("Compact dump formatting produces clean single-line representation")
    void testDumpCompact() {
        Frame frame = createTestFrame();
        frame.locals().setInt(0, 5);
        frame.operandStack().push(Value.ofInt(99));
        frame.setPc(2);

        ExecutionSnapshot snapshot = ExecutionSnapshot.capture(frame, null);
        String compact = StateDumper.dumpCompact(snapshot);

        assertEquals("[depth=1, status=RUNNING, pc=2, stack=[IntValue[99]], locals=[0=IntValue[5]]]", compact);
    }

    @Test
    @DisplayName("Dumps multi-frame call stack depth accurately")
    void testMultiFrameStackDepth() {
        Frame frame1 = createTestFrame();
        Frame frame2 = createTestFrame();

        FrameStack stack = new FrameStack();
        stack.push(frame1);
        stack.push(frame2);

        String dump = StateDumper.dump(frame2, stack);
        assertTrue(dump.contains("FrameStack depth: 2"));
    }

    @Test
    @DisplayName("Dumping state is bit-for-bit deterministic across multiple invocations")
    void testDeterministicDumping() {
        Frame frame = createTestFrame();
        frame.locals().setInt(0, 100);
        frame.locals().setReference(1, Value.ofReference(5L, "java/lang/String"));
        frame.operandStack().push(Value.ofInt(1));
        frame.operandStack().push(Value.nullRef());

        String dump1 = StateDumper.dump(frame);
        String dump2 = StateDumper.dump(frame);

        assertEquals(dump1, dump2);
    }
}
