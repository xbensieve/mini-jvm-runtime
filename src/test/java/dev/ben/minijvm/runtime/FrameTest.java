package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameTest {

    @Test
    @DisplayName("Frame initializes correctly from parsed ClassFile and MethodInfo")
    void testFrameFromClassFile() {
        String src = """
                package fixtures;
                public class Sample {
                    public int compute(int a, int b) {
                        return a + b;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.Sample", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("compute", "(II)I").orElseThrow();

        Frame frame = new Frame(cf, method);

        assertSame(method, frame.method());
        assertSame(cf.constantPool(), frame.constantPool());
        assertEquals(0, frame.pc());
        assertTrue(frame.maxLocals() >= 3); // this, a, b
        assertTrue(frame.maxStack() >= 2);
        assertTrue(frame.operandStack().isEmpty());
    }

    @Test
    @DisplayName("Frame rejects invalid construction arguments")
    void testInvalidConstruction() {
        List<ConstantPoolEntry> entries = List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.Utf8Entry("test")
        );
        ConstantPool cp = new ConstantPool(entries);
        MethodInfo dummyMethod = new MethodInfo(0, 1, 1, List.of(), null);

        assertThrows(StackFaultException.class, () -> new Frame(null, cp, 2, 2));
        assertThrows(StackFaultException.class, () -> new Frame(dummyMethod, null, 2, 2));
        assertThrows(StackFaultException.class, () -> new Frame(dummyMethod, cp, -1, 2));
        assertThrows(StackFaultException.class, () -> new Frame(dummyMethod, cp, 2, -1));
    }

    @Test
    @DisplayName("Frame construction from ClassFile fails if method has no Code attribute (e.g. abstract method)")
    void testAbstractMethodFrameFails() {
        String src = """
                package fixtures;
                public interface InterfaceSample {
                    void abstractMethod();
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.InterfaceSample", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("abstractMethod", "()V").orElseThrow();

        StackFaultException ex = assertThrows(StackFaultException.class, () -> new Frame(cf, method));
        assertTrue(ex.getMessage().contains("without Code attribute"));
    }

    @Test
    @DisplayName("Program counter manipulation enforces bounds")
    void testProgramCounterBounds() {
        String src = """
                package fixtures;
                public class PcSample {
                    public void test() {}
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.PcSample", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("test", "()V").orElseThrow();

        Frame frame = new Frame(cf, method);
        assertEquals(0, frame.pc());

        int codeLength = method.code().orElseThrow().codeLength();

        frame.setPc(codeLength);
        assertEquals(codeLength, frame.pc());

        // Negative PC rejected
        assertThrows(StackFaultException.class, () -> frame.setPc(-1));

        // PC exceeding code length rejected
        assertThrows(StackFaultException.class, () -> frame.setPc(codeLength + 1));

        // Advance PC
        frame.setPc(0);
        frame.advancePc(1);
        assertEquals(1, frame.pc());
    }

    @Test
    @DisplayName("Frame locals and operand stack interact cleanly")
    void testLocalsAndStackIntegration() {
        String src = """
                package fixtures;
                public class IntegrSample {
                    public int run() {
                        return 0;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.IntegrSample", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("run", "()I").orElseThrow();

        Frame frame = new Frame(cf, method);

        // Put a value into locals
        frame.locals().setInt(0, 1234);
        assertEquals(1234, frame.locals().getInt(0));

        // Transfer to operand stack
        frame.operandStack().push(frame.locals().get(0));
        assertEquals(1, frame.operandStack().slots());
        assertEquals(1234, frame.operandStack().popInt());
        assertEquals(0, frame.operandStack().slots());
    }
}
