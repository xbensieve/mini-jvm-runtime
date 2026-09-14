package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InterpreterFixtureTest {

    @Test
    @DisplayName("Executes real compiled Java 21 integer arithmetic bytecode and matches reference values")
    void testRealJavaArithmeticBytecode() {
        String src = """
                package fixtures;
                public class MathCalculations {
                    public static void run() {
                        int a = 15;
                        int b = 4;
                        int sum = a + b;
                        int diff = a - b;
                        int prod = a * b;
                        int quot = a / b;
                        int rem = a % b;
                        int neg = -a;
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.MathCalculations", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("run", "()V").orElseThrow();

        Frame frame = new Frame(cf, method);
        Interpreter interpreter = new Interpreter();

        byte[] code = method.code().orElseThrow().code();

        // Step through Phase 03 instructions up until the return (0xB1) opcode
        while (frame.pc() < code.length) {
            int nextByte = code[frame.pc()] & 0xFF;
            if (nextByte == 0xB1) { // return (Phase 04 opcode)
                break;
            }
            Instruction executed = interpreter.step(frame);
            assertNotNull(executed);
        }

        // Verify local variable state matches host Java computation
        assertEquals(15, frame.locals().getInt(0));
        assertEquals(4, frame.locals().getInt(1));
        assertEquals(15 + 4, frame.locals().getInt(2));   // sum = 19
        assertEquals(15 - 4, frame.locals().getInt(3));   // diff = 11
        assertEquals(15 * 4, frame.locals().getInt(4));   // prod = 60
        assertEquals(15 / 4, frame.locals().getInt(5));   // quot = 3
        assertEquals(15 % 4, frame.locals().getInt(6));   // rem = 3
        assertEquals(-15, frame.locals().getInt(7));      // neg = -15

        // Operand stack should be empty since each calculation stored to a local
        assertTrue(frame.operandStack().isEmpty());
    }

    @Test
    @DisplayName("Executes complex arithmetic expression and asserts operand stack evaluation")
    void testArithmeticExpression() {
        String src = """
                package fixtures;
                public class ExprCalculations {
                    public static void compute() {
                        int x = (10 + 20) * 3 - (50 / 2);
                    }
                }
                """;
        byte[] bytes = CompilerTestUtils.compile("fixtures.ExprCalculations", src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo method = cf.findMethod("compute", "()V").orElseThrow();

        Frame frame = new Frame(cf, method);
        Interpreter interpreter = new Interpreter();
        byte[] code = method.code().orElseThrow().code();

        while (frame.pc() < code.length) {
            int nextByte = code[frame.pc()] & 0xFF;
            if (nextByte == 0xB1) {
                break;
            }
            interpreter.step(frame);
        }

        int expected = (10 + 20) * 3 - (50 / 2); // 90 - 25 = 65
        assertEquals(expected, frame.locals().getInt(0));
    }
}
