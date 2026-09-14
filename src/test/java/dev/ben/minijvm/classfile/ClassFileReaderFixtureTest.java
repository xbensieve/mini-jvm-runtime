package dev.ben.minijvm.classfile;

import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileReaderFixtureTest {

    @Test
    @DisplayName("Parses a compiled minimal Java 21 class fixture")
    void testEmptyClassFixture() {
        String src = """
                package fixtures;
                public class EmptyClass {
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.EmptyClass", src);
        ClassFile cf = ClassFileReader.read(bytes);

        assertEquals(65, cf.majorVersion());
        assertEquals("fixtures/EmptyClass", cf.thisClassName());
        assertTrue(cf.superClassName().isPresent());
        assertEquals("java/lang/Object", cf.superClassName().get());
        assertTrue(cf.interfaces().isEmpty());
        assertTrue(cf.fields().isEmpty());

        // Default constructor should exist
        Optional<MethodInfo> initMethod = cf.findMethod("<init>", "()V");
        assertTrue(initMethod.isPresent());
        assertTrue(initMethod.get().code().isPresent());
        CodeAttribute code = initMethod.get().code().get();
        assertTrue(code.maxStack() >= 1);
        assertTrue(code.maxLocals() >= 1);
        assertTrue(code.codeLength() > 0);
    }

    @Test
    @DisplayName("Parses class with primitive fields, long/double constants and methods")
    void testSimpleArithmeticFixture() {
        String src = """
                package fixtures;
                public class SimpleArithmetic {
                    public static final int CONST_INT = 42;
                    public static final double FACTOR = 2.718;
                    private long counter = 100L;

                    public int add(int a, int b) {
                        return a + b;
                    }

                    public long doubleCounter() {
                        return counter * 2L;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.SimpleArithmetic", src);
        ClassFile cf = ClassFileReader.read(bytes);

        assertEquals("fixtures/SimpleArithmetic", cf.thisClassName());
        assertEquals(3, cf.fields().size());

        assertTrue(cf.findField("CONST_INT", "I").isPresent());
        assertTrue(cf.findField("FACTOR", "D").isPresent());
        assertTrue(cf.findField("counter", "J").isPresent());

        Optional<MethodInfo> addMethod = cf.findMethod("add", "(II)I");
        assertTrue(addMethod.isPresent());
        CodeAttribute addCode = addMethod.get().code().orElseThrow();
        assertTrue(addCode.maxStack() >= 2);
        assertTrue(addCode.maxLocals() >= 3); // this, a, b

        Optional<MethodInfo> doubleCounterMethod = cf.findMethod("doubleCounter", "()J");
        assertTrue(doubleCounterMethod.isPresent());
        CodeAttribute dcCode = doubleCounterMethod.get().code().orElseThrow();
        assertTrue(dcCode.maxStack() >= 2);
        assertTrue(dcCode.maxLocals() >= 1);
    }

    @Test
    @DisplayName("Parses class with loop control flow and try-catch exception table")
    void testControlFlowAndExceptionFixture() {
        String src = """
                package fixtures;
                public class ControlFlow {
                    public int loop(int n) {
                        int sum = 0;
                        for (int i = 0; i < n; i++) {
                            sum += i;
                        }
                        return sum;
                    }

                    public int safeDivide(int a, int b) {
                        try {
                            return a / b;
                        } catch (ArithmeticException e) {
                            return -1;
                        }
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.ControlFlow", src);
        ClassFile cf = ClassFileReader.read(bytes);

        Optional<MethodInfo> loopMethod = cf.findMethod("loop", "(I)I");
        assertTrue(loopMethod.isPresent());
        CodeAttribute loopCode = loopMethod.get().code().orElseThrow();
        assertTrue(loopCode.codeLength() > 5);

        Optional<MethodInfo> safeDivideMethod = cf.findMethod("safeDivide", "(II)I");
        assertTrue(safeDivideMethod.isPresent());
        CodeAttribute sdCode = safeDivideMethod.get().code().orElseThrow();

        assertFalse(sdCode.exceptionTable().isEmpty());
        ExceptionTableEntry handler = sdCode.exceptionTable().get(0);
        assertTrue(handler.startPc() >= 0);
        assertTrue(handler.endPc() > handler.startPc());
        assertTrue(handler.handlerPc() >= handler.endPc());
        assertTrue(handler.catchType() > 0);

        String caughtExceptionClass = cf.constantPool().getClassName(handler.catchType());
        assertEquals("java/lang/ArithmeticException", caughtExceptionClass);
    }

    @Test
    @DisplayName("Parses class implementing an interface")
    void testInterfaceImplementationFixture() {
        String interfaceSrc = """
                package fixtures;
                public interface Greeter {
                    void greet();
                }
                """;
        String implSrc = """
                package fixtures;
                public class UserGreeter implements Greeter {
                    @Override
                    public void greet() {
                        int x = 1;
                    }
                }
                """;
        java.util.Map<String, byte[]> compiled = CompilerTestUtils.compileAll(java.util.Map.of(
                "fixtures.Greeter", interfaceSrc,
                "fixtures.UserGreeter", implSrc
        ));

        byte[] ifaceBytes = compiled.get("fixtures.Greeter");
        ClassFile ifaceCf = ClassFileReader.read(ifaceBytes);
        assertTrue(AccessFlags.isInterface(ifaceCf.accessFlags()));
        assertTrue(AccessFlags.isAbstract(ifaceCf.accessFlags()));
        Optional<MethodInfo> greetMethod = ifaceCf.findMethod("greet", "()V");
        assertTrue(greetMethod.isPresent());
        assertTrue(greetMethod.get().code().isEmpty()); // interface method has no Code attribute

        byte[] implBytes = compiled.get("fixtures.UserGreeter");
        ClassFile implCf = ClassFileReader.read(implBytes);
        assertEquals(1, implCf.interfaces().size());
        assertEquals("fixtures/Greeter", implCf.interfaceNames().get(0));
        Optional<MethodInfo> implGreetMethod = implCf.findMethod("greet", "()V");
        assertTrue(implGreetMethod.isPresent());
        assertTrue(implGreetMethod.get().code().isPresent());
    }
}
