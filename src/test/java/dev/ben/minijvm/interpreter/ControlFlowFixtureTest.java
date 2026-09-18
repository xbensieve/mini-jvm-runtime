package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates control flow execution semantics using genuine Java 21 bytecodes
 * compiled by javac, featuring branching, loops, iinc, and method returns.
 */
class ControlFlowFixtureTest {

    private final Interpreter interpreter = new Interpreter();

    @Test
    @DisplayName("Executes javac-compiled if/else branching (max of two numbers)")
    void testIfElseBranchingFixture() {
        String src = """
                package fixtures;
                public class IfElseBranch {
                    public static int max(int a, int b) {
                        if (a > b) {
                            return a;
                        } else {
                            return b;
                        }
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.IfElseBranch", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("max", "(II)I").orElseThrow();

        // Test branch 1: a > b (10 > 5 -> 10)
        Frame f1 = new Frame(cf, method);
        f1.locals().setInt(0, 10);
        f1.locals().setInt(1, 5);
        interpreter.execute(f1);

        assertTrue(f1.isCompleted());
        assertTrue(f1.returnValue().isPresent());
        assertEquals(10, f1.returnValue().get().asInt());

        // Test branch 2: a <= b (3 <= 8 -> 8)
        Frame f2 = new Frame(cf, method);
        f2.locals().setInt(0, 3);
        f2.locals().setInt(1, 8);
        interpreter.execute(f2);

        assertTrue(f2.isCompleted());
        assertTrue(f2.returnValue().isPresent());
        assertEquals(8, f2.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Executes javac-compiled while loop with accumulator and iinc")
    void testWhileLoopAccumulatorFixture() {
        String src = """
                package fixtures;
                public class LoopSum {
                    public static int sumUpTo(int n) {
                        int sum = 0;
                        int i = 1;
                        while (i <= n) {
                            sum = sum + i;
                            i++;
                        }
                        return sum;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.LoopSum", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("sumUpTo", "(I)I").orElseThrow();

        // sum(1..5) = 1 + 2 + 3 + 4 + 5 = 15
        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, 5);
        interpreter.execute(frame);

        assertTrue(frame.isCompleted());
        assertTrue(frame.returnValue().isPresent());
        assertEquals(15, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Executes javac-compiled for loop factorial calculation")
    void testFactorialLoopFixture() {
        String src = """
                package fixtures;
                public class Factorial {
                    public static int compute(int n) {
                        int res = 1;
                        for (int i = 2; i <= n; i++) {
                            res = res * i;
                        }
                        return res;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.Factorial", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("compute", "(I)I").orElseThrow();

        // 5! = 120
        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, 5);
        interpreter.execute(frame);

        assertTrue(frame.isCompleted());
        assertTrue(frame.returnValue().isPresent());
        assertEquals(120, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Executes javac-compiled countdown while loop with decrements")
    void testCountdownLoopFixture() {
        String src = """
                package fixtures;
                public class Countdown {
                    public static int countdown(int start) {
                        int count = 0;
                        while (start > 0) {
                            count++;
                            start--;
                        }
                        return count;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.Countdown", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("countdown", "(I)I").orElseThrow();

        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, 10);
        interpreter.execute(frame);

        assertTrue(frame.isCompleted());
        assertTrue(frame.returnValue().isPresent());
        assertEquals(10, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Executes multi-frame caller/callee execution on FrameStack with javac compiled methods")
    void testCallerCalleeOnFrameStackFixture() {
        String src = """
                package fixtures;
                public class ChainExecution {
                    public static int square(int x) {
                        return x * x;
                    }
                    public static int calculate(int n) {
                        int sq = square(n);
                        return sq + 10;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.ChainExecution", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo squareMethod = cf.findMethod("square", "(I)I").orElseThrow();
        MethodInfo calcMethod = cf.findMethod("calculate", "(I)I").orElseThrow();

        FrameStack frameStack = new FrameStack();

        // 1. Caller frame: calculate(5)
        Frame callerFrame = new Frame(cf, calcMethod);
        callerFrame.locals().setInt(0, 5);
        frameStack.push(callerFrame);

        // 2. Step caller up to invokestatic (simulate the invocation boundary)
        // Callee frame: square(5)
        Frame calleeFrame = new Frame(cf, squareMethod);
        calleeFrame.locals().setInt(0, 5);
        frameStack.push(calleeFrame);

        // 3. Execute callee to completion on frameStack
        while (!calleeFrame.isCompleted()) {
            interpreter.step(calleeFrame, frameStack);
        }

        // Callee popped, return value (25) transferred to caller's operand stack
        assertEquals(1, frameStack.depth());
        assertSame(callerFrame, frameStack.current());
        assertEquals(1, callerFrame.operandStack().slots());
        assertEquals(25, callerFrame.operandStack().peek().asInt());

        // 4. Store result into caller local 1 (sq = 25), push 10, add, ireturn
        callerFrame.locals().setInt(1, callerFrame.operandStack().popInt());
        callerFrame.operandStack().push(Value.ofInt(callerFrame.locals().getInt(1)));
        callerFrame.operandStack().push(Value.ofInt(10));
        callerFrame.operandStack().push(Value.ofInt(
                callerFrame.operandStack().popInt() + callerFrame.operandStack().popInt()
        ));
        // Caller returns 35
        calleeFrame.setReturnValue(callerFrame.operandStack().pop());
        callerFrame.markCompleted();
        frameStack.pop();

        assertTrue(frameStack.isEmpty());
        assertEquals(35, calleeFrame.returnValue().get().asInt());
    }
}
