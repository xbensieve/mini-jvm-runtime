package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePrimitivesIntegrationTest {

    @Test
    @DisplayName("Simulate caller and callee execution lifecycle over FrameStack with Category-1 values")
    void testInvocationAndReturnLifecycle() {
        String src = """
                package fixtures;
                public class ExecutionChain {
                    public static int multiply(int a, int b) {
                        return a * b;
                    }
                    public static int caller(int x) {
                        int temp = multiply(x, 2);
                        return temp + 1;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.ExecutionChain", src);
        ClassFile cf = ClassFileReader.read(bytes);

        MethodInfo callerMethod = cf.findMethod("caller", "(I)I").orElseThrow();
        MethodInfo multiplyMethod = cf.findMethod("multiply", "(II)I").orElseThrow();

        FrameStack callStack = new FrameStack();

        // 1. Enter caller(7)
        Frame callerFrame = new Frame(cf, callerMethod);
        callerFrame.locals().setInt(0, 7); // parameter x
        callStack.push(callerFrame);

        assertEquals(1, callStack.depth());
        assertSame(callerFrame, callStack.current());

        // 2. Caller prepares call to multiply(x, 2): pushes args to operand stack
        callerFrame.operandStack().push(callerFrame.locals().get(0)); // 7
        callerFrame.operandStack().push(Value.ofInt(2));              // 2
        assertEquals(2, callerFrame.operandStack().slots());

        // Caller advances PC to invoke instruction
        callerFrame.advancePc(2);

        // 3. Invocation boundary: pop args from caller, transfer to callee locals
        Value argB = callerFrame.operandStack().pop();
        Value argA = callerFrame.operandStack().pop();
        assertEquals(0, callerFrame.operandStack().slots());

        Frame calleeFrame = new Frame(cf, multiplyMethod);
        calleeFrame.locals().set(0, argA);
        calleeFrame.locals().set(1, argB);
        callStack.push(calleeFrame);

        assertEquals(2, callStack.depth());
        assertSame(calleeFrame, callStack.current());

        // 4. Callee executes: push local 0, push local 1, multiply, push result
        calleeFrame.operandStack().push(calleeFrame.locals().get(0));
        calleeFrame.operandStack().push(calleeFrame.locals().get(1));
        int op2 = calleeFrame.operandStack().popInt();
        int op1 = calleeFrame.operandStack().popInt();
        int mulResult = op1 * op2;
        calleeFrame.operandStack().push(Value.ofInt(mulResult));
        calleeFrame.advancePc(3);

        // 5. Return boundary: pop callee result, pop callee frame, push result to caller
        Value returnVal = calleeFrame.operandStack().pop();
        assertEquals(mulResult, returnVal.asInt());
        assertSame(calleeFrame, callStack.pop());

        assertEquals(1, callStack.depth());
        assertSame(callerFrame, callStack.current());

        callerFrame.operandStack().push(returnVal); // temp = 14

        // 6. Caller finishes: temp + 1
        callerFrame.operandStack().push(Value.ofInt(1));
        int addend = callerFrame.operandStack().popInt();
        int base = callerFrame.operandStack().popInt();
        int finalResult = base + addend;
        callerFrame.operandStack().push(Value.ofInt(finalResult));
        callerFrame.advancePc(2);

        Value callerReturnVal = callerFrame.operandStack().pop();
        assertEquals(15, callerReturnVal.asInt());

        assertSame(callerFrame, callStack.pop());
        assertTrue(callStack.isEmpty());
    }

    @Test
    @DisplayName("Simulate 64-bit Category-2 values (long) across locals and operand stack")
    void testCategory2LongChainLifecycle() {
        String src = """
                package fixtures;
                public class LongChain {
                    public static long addLongs(long a, long b) {
                        return a + b;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.LongChain", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("addLongs", "(JJ)J").orElseThrow();

        Frame frame = new Frame(cf, method);

        // Long a at slot 0 (occupies 0 and 1)
        frame.locals().setLong(0, 100000000000L);
        // Long b at slot 2 (occupies 2 and 3)
        frame.locals().setLong(2, 200000000000L);

        // Secondary slots are phantom
        assertTrue(frame.locals().isPhantom(1));
        assertTrue(frame.locals().isPhantom(3));

        // Push both onto operand stack
        frame.operandStack().push(frame.locals().get(0)); // +2 slots
        assertEquals(2, frame.operandStack().slots());

        frame.operandStack().push(frame.locals().get(2)); // +2 slots
        assertEquals(4, frame.operandStack().slots());

        // Pop in LIFO
        long valB = frame.operandStack().popLong();
        assertEquals(200000000000L, valB);
        assertEquals(2, frame.operandStack().slots());

        long valA = frame.operandStack().popLong();
        assertEquals(100000000000L, valA);
        assertEquals(0, frame.operandStack().slots());

        // Push result
        long sum = valA + valB;
        frame.operandStack().push(Value.ofLong(sum));
        assertEquals(2, frame.operandStack().slots());
        assertEquals(300000000000L, frame.operandStack().popLong());
        assertEquals(0, frame.operandStack().slots());
    }
}
