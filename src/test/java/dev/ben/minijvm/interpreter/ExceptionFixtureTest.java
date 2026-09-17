package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.GuestExecutionException;
import dev.ben.minijvm.runtime.ClassRepository;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Heap;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionFixtureTest {

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

    private void loadClasses(Map<String, byte[]> byteMap) {
        for (Map.Entry<String, byte[]> entry : byteMap.entrySet()) {
            ClassFile cf = ClassFileReader.read(entry.getValue());
            repository.register(cf);
        }
    }

    private MethodInfo findMethod(ClassFile cf, String name) {
        return cf.methods().stream()
                .filter(m -> m.name(cf.constantPool()).equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Method not found: " + name));
    }

    @Test
    @DisplayName("Simple try-catch block catching RuntimeException")
    void testSimpleTryCatch() {
        String src = """
                package fixtures.exceptions;
                public class SimpleCatch {
                    public static int test() {
                        try {
                            throw new RuntimeException();
                        } catch (RuntimeException e) {
                            return 42;
                        }
                    }
                }
                """;

        Map<String, byte[]> bytes = CompilerTestUtils.compileAll(Map.of("fixtures.exceptions.SimpleCatch", src));
        loadClasses(bytes);

        ClassFile cf = repository.getClass("fixtures/exceptions/SimpleCatch");
        MethodInfo method = findMethod(cf, "test");
        Frame frame = new Frame(cf, method);

        interpreter.execute(frame);

        assertTrue(frame.returnValue().isPresent());
        assertEquals(42, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Polymorphic catch: custom exception caught as Exception base class")
    void testPolymorphicCatch() {
        String customExSrc = """
                package fixtures.exceptions;
                public class CustomException extends RuntimeException {
                }
                """;

        String testSrc = """
                package fixtures.exceptions;
                public class PolymorphicTest {
                    public static int test() {
                        try {
                            throw new CustomException();
                        } catch (Exception e) {
                            return 100;
                        }
                    }
                }
                """;

        Map<String, byte[]> bytes = CompilerTestUtils.compileAll(Map.of(
                "fixtures.exceptions.CustomException", customExSrc,
                "fixtures.exceptions.PolymorphicTest", testSrc
        ));
        loadClasses(bytes);

        ClassFile cf = repository.getClass("fixtures/exceptions/PolymorphicTest");
        MethodInfo method = findMethod(cf, "test");
        Frame frame = new Frame(cf, method);

        interpreter.execute(frame);

        assertTrue(frame.returnValue().isPresent());
        assertEquals(100, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Multiple catch blocks select the matching handler by type")
    void testMultipleCatchBlocks() {
        String src = """
                package fixtures.exceptions;
                public class MultiCatch {
                    public static int test(int mode) {
                        try {
                            if (mode == 1) {
                                throw new IllegalStateException();
                            } else {
                                throw new IllegalArgumentException();
                            }
                        } catch (IllegalStateException e) {
                            return 10;
                        } catch (IllegalArgumentException e) {
                            return 20;
                        }
                    }
                }
                """;

        Map<String, byte[]> bytes = CompilerTestUtils.compileAll(Map.of("fixtures.exceptions.MultiCatch", src));
        loadClasses(bytes);

        ClassFile cf = repository.getClass("fixtures/exceptions/MultiCatch");
        MethodInfo method = findMethod(cf, "test");

        // Test mode 1 -> IllegalStateException handler (10)
        Frame frame1 = new Frame(cf, method);
        frame1.locals().setInt(0, 1);
        interpreter.execute(frame1);
        assertTrue(frame1.returnValue().isPresent());
        assertEquals(10, frame1.returnValue().get().asInt());

        // Test mode 2 -> IllegalArgumentException handler (20)
        Frame frame2 = new Frame(cf, method);
        frame2.locals().setInt(0, 2);
        interpreter.execute(frame2);
        assertTrue(frame2.returnValue().isPresent());
        assertEquals(20, frame2.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Try-finally block executes finally code on normal completion")
    void testTryFinallyNormal() {
        String src = """
                package fixtures.exceptions;
                public class TryFinally {
                    public static int test() {
                        int x = 1;
                        try {
                            x = 10;
                        } finally {
                            x = x + 5;
                        }
                        return x;
                    }
                }
                """;

        Map<String, byte[]> bytes = CompilerTestUtils.compileAll(Map.of("fixtures.exceptions.TryFinally", src));
        loadClasses(bytes);

        ClassFile cf = repository.getClass("fixtures/exceptions/TryFinally");
        MethodInfo method = findMethod(cf, "test");
        Frame frame = new Frame(cf, method);

        interpreter.execute(frame);

        assertTrue(frame.returnValue().isPresent());
        assertEquals(15, frame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Multi-level stack unwinding across methods: level1 -> level2 -> level3 (throws) -> caught at level1")
    void testInterMethodStackUnwinding() {
        String src = """
                package fixtures.exceptions;
                public class CallStackUnwind {
                    public static int level1() {
                        try {
                            return level2();
                        } catch (RuntimeException e) {
                            return 777;
                        }
                    }
                    public static int level2() {
                        return level3() + 10;
                    }
                    public static int level3() {
                        throw new RuntimeException();
                    }
                }
                """;

        Map<String, byte[]> bytes = CompilerTestUtils.compileAll(Map.of("fixtures.exceptions.CallStackUnwind", src));
        loadClasses(bytes);

        ClassFile cf = repository.getClass("fixtures/exceptions/CallStackUnwind");
        MethodInfo rootMethod = findMethod(cf, "level1");

        Frame rootFrame = new Frame(cf, rootMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(rootFrame);

        interpreter.execute(frameStack);

        assertTrue(rootFrame.returnValue().isPresent());
        assertEquals(777, rootFrame.returnValue().get().asInt());
    }

    @Test
    @DisplayName("Uncaught exception in fixture unwinds past root frame and throws GuestExecutionException")
    void testUncaughtFixtureThrows() {
        String src = """
                package fixtures.exceptions;
                public class UncaughtFixture {
                    public static void test() {
                        throw new RuntimeException();
                    }
                }
                """;

        Map<String, byte[]> bytes = CompilerTestUtils.compileAll(Map.of("fixtures.exceptions.UncaughtFixture", src));
        loadClasses(bytes);

        ClassFile cf = repository.getClass("fixtures/exceptions/UncaughtFixture");
        MethodInfo method = findMethod(cf, "test");
        Frame frame = new Frame(cf, method);

        GuestExecutionException ex = assertThrows(
                GuestExecutionException.class,
                () -> interpreter.execute(frame)
        );

        assertNotNull(ex.guestException());
        assertEquals("java/lang/RuntimeException", ex.exceptionClassName());
    }
}
