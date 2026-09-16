package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.runtime.ClassRepository;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests using real javac-compiled Java 21 bytecode fixtures.
 * Cross-checks method invocation, argument passing, return propagation,
 * and call chains directly against host JVM execution.
 */
class InvocationFixtureTest {

    private ClassRepository repository;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        MethodResolver resolver = new MethodResolver(repository);
        interpreter = new Interpreter(new BytecodeDecoder(), resolver);
    }

    @Test
    @DisplayName("Static helper invocation: run(x, y) -> add(x, y)")
    void testStaticHelperFixture() {
        String src = """
                package fixtures;
                public class StaticHelper {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                    public static int run(int x, int y) {
                        return add(x, y);
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.StaticHelper", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo runMethod = cf.findMethod("run", "(II)I").orElseThrow();

        int[][] testCases = {
                {0, 0},
                {10, 20},
                {-5, 15},
                {Integer.MAX_VALUE - 10, 10}
        };

        for (int[] tc : testCases) {
            int x = tc[0];
            int y = tc[1];

            Frame frame = new Frame(cf, runMethod);
            frame.locals().setInt(0, x);
            frame.locals().setInt(1, y);

            FrameStack frameStack = new FrameStack();
            frameStack.push(frame);
            interpreter.execute(frameStack);

            assertTrue(frame.isReturned());
            int hostResult = x + y;
            assertEquals(hostResult, frame.returnValue().get().asInt(),
                    String.format("Mini JVM result for add(%d, %d) must match host JVM result", x, y));
        }
    }

    @Test
    @DisplayName("Nested static call: doubleThenTriple(n) -> triple(d)")
    void testNestedStaticCallFixture() {
        String src = """
                package fixtures;
                public class NestedCalls {
                    public static int triple(int n) {
                        return n * 3;
                    }
                    public static int doubleThenTriple(int n) {
                        int d = n * 2;
                        return triple(d);
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.NestedCalls", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo method = cf.findMethod("doubleThenTriple", "(I)I").orElseThrow();

        for (int input : new int[]{1, 5, 10, -4}) {
            Frame frame = new Frame(cf, method);
            frame.locals().setInt(0, input);

            FrameStack frameStack = new FrameStack();
            frameStack.push(frame);
            interpreter.execute(frameStack);

            assertTrue(frame.isReturned());
            int expected = (input * 2) * 3;
            assertEquals(expected, frame.returnValue().get().asInt(),
                    String.format("Expected %d * 2 * 3 = %d", input, expected));
        }
    }

    @Test
    @DisplayName("Method returning void called by method returning int")
    void testVoidMethodInvocation() {
        String src = """
                package fixtures;
                public class VoidHelper {
                    public static void dummy(int a) {
                        int temp = a + 1;
                        return;
                    }
                    public static int compute(int x) {
                        dummy(x);
                        return x * 10;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.VoidHelper", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo method = cf.findMethod("compute", "(I)I").orElseThrow();

        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, 7);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);
        interpreter.execute(frameStack);

        assertTrue(frame.isReturned());
        assertEquals(70, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Multiple-argument static method with 4 arguments: combine(a, b, c, d)")
    void testMultiArgumentStaticMethod() {
        String src = """
                package fixtures;
                public class MultiArgs {
                    public static int combine(int a, int b, int c, int d) {
                        return a - b + c - d;
                    }
                    public static int run(int a, int b, int c, int d) {
                        return combine(a, b, c, d);
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.MultiArgs", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo method = cf.findMethod("run", "(IIII)I").orElseThrow();

        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, 100);
        frame.locals().setInt(1, 20);
        frame.locals().setInt(2, 30);
        frame.locals().setInt(3, 5);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);
        interpreter.execute(frameStack);

        assertTrue(frame.isReturned());
        int hostResult = 100 - 20 + 30 - 5;
        assertEquals(hostResult, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Multiple call chain: f(x) -> g(x) -> h(x)")
    void testMultipleCallChain() {
        String src = """
                package fixtures;
                public class CallChain {
                    public static int f(int x) {
                        return x + 1;
                    }
                    public static int g(int x) {
                        return f(x) * 2;
                    }
                    public static int h(int x) {
                        return g(x) - 3;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.CallChain", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo method = cf.findMethod("h", "(I)I").orElseThrow();

        for (int x : new int[]{0, 5, 10, -2}) {
            Frame frame = new Frame(cf, method);
            frame.locals().setInt(0, x);

            FrameStack frameStack = new FrameStack();
            frameStack.push(frame);
            interpreter.execute(frameStack);

            assertTrue(frame.isReturned());
            int hostResult = ((x + 1) * 2) - 3;
            assertEquals(hostResult, frame.returnValue().get().asInt());
        }
    }

    @Test
    @DisplayName("Cross-class invocation: CallerClass calls CalleeClass.multiply(a, b)")
    void testCrossClassInvocation() {
        String callerSrc = """
                package fixtures;
                public class CallerClass {
                    public static int compute(int a, int b) {
                        return CalleeClass.multiply(a, b);
                    }
                }
                """;
        String calleeSrc = """
                package fixtures;
                public class CalleeClass {
                    public static int multiply(int x, int y) {
                        return x * y;
                    }
                }
                """;
        Map<String, byte[]> compiled = CompilerTestUtils.compileAll(Map.of(
                "fixtures.CallerClass", callerSrc,
                "fixtures.CalleeClass", calleeSrc
        ));

        ClassFile callerCf = ClassFileReader.read(compiled.get("fixtures.CallerClass"));
        ClassFile calleeCf = ClassFileReader.read(compiled.get("fixtures.CalleeClass"));
        repository.register(callerCf);
        repository.register(calleeCf);

        MethodInfo computeMethod = callerCf.findMethod("compute", "(II)I").orElseThrow();

        Frame frame = new Frame(callerCf, computeMethod);
        frame.locals().setInt(0, 6);
        frame.locals().setInt(1, 7);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);
        interpreter.execute(frameStack);

        assertTrue(frame.isReturned());
        assertEquals(42, frame.returnValue().get().asInt());
    }

    @ParameterizedTest(name = "Recursive factorial({0})")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    @DisplayName("Recursive factorial calculation cross-checked with host JVM")
    void testRecursiveFactorial(int n) {
        String src = """
                package fixtures;
                public class Factorial {
                    public static int factorial(int n) {
                        if (n <= 1) {
                            return 1;
                        }
                        return n * factorial(n - 1);
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.Factorial", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo method = cf.findMethod("factorial", "(I)I").orElseThrow();

        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, n);

        FrameStack frameStack = new FrameStack(100);
        frameStack.push(frame);
        interpreter.execute(frameStack);

        assertTrue(frame.isReturned());

        // Host JVM reference calculation
        int hostResult = 1;
        for (int i = 2; i <= n; i++) {
            hostResult *= i;
        }

        assertEquals(hostResult, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Loop with method invocation: sum of squares")
    void testLoopWithInvocation() {
        String src = """
                package fixtures;
                public class SumOfSquares {
                    public static int square(int n) {
                        return n * n;
                    }
                    public static int sumSquares(int count) {
                        int sum = 0;
                        for (int i = 1; i <= count; i++) {
                            sum += square(i);
                        }
                        return sum;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.SumOfSquares", src);
        ClassFile cf = ClassFileReader.read(bytes);
        repository.register(cf);

        MethodInfo method = cf.findMethod("sumSquares", "(I)I").orElseThrow();

        Frame frame = new Frame(cf, method);
        frame.locals().setInt(0, 5);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);
        interpreter.execute(frameStack);

        assertTrue(frame.isReturned());
        // 1 + 4 + 9 + 16 + 25 = 55
        assertEquals(55, frame.returnValue().get().asInt());
    }
}
