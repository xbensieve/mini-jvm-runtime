package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.ArithmeticFaultException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterpreterTest {

    private final Interpreter interpreter = new Interpreter();

    @Test
    @DisplayName("nop advances PC without altering stack or locals")
    void testNop() {
        Frame frame = createFrame(new byte[]{0x00}, 1, 1);
        assertEquals(0, frame.pc());

        interpreter.step(frame);

        assertEquals(1, frame.pc());
        assertEquals(0, frame.operandStack().slots());
    }

    @Test
    @DisplayName("aconst_null pushes a null reference onto the operand stack")
    void testAconstNull() {
        Frame frame = createFrame(new byte[]{0x01}, 1, 1);
        interpreter.step(frame);

        assertEquals(1, frame.operandStack().slots());
        Value val = frame.operandStack().pop();
        assertTrue(val.isNull());
        assertSame(Value.nullRef(), val);
    }

    @Test
    @DisplayName("iconst_m1 through iconst_5 push expected signed integer constants")
    void testIconstFamily() {
        byte[] code = new byte[]{0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        Frame frame = createFrame(code, 1, 7);

        interpreter.execute(frame);

        assertEquals(7, frame.operandStack().slots());
        assertEquals(5, frame.operandStack().popInt());
        assertEquals(4, frame.operandStack().popInt());
        assertEquals(3, frame.operandStack().popInt());
        assertEquals(2, frame.operandStack().popInt());
        assertEquals(1, frame.operandStack().popInt());
        assertEquals(0, frame.operandStack().popInt());
        assertEquals(-1, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("bipush pushes 8-bit signed immediate values with sign extension")
    void testBipushBoundaries() {
        byte[] code = new byte[]{
                0x10, (byte) 127,  // bipush 127
                0x10, (byte) -128, // bipush -128
                0x10, (byte) -1    // bipush -1
        };
        Frame frame = createFrame(code, 1, 3);
        interpreter.execute(frame);

        assertEquals(-1, frame.operandStack().popInt());
        assertEquals(-128, frame.operandStack().popInt());
        assertEquals(127, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("sipush pushes 16-bit signed immediate values with big-endian decoding")
    void testSipushBoundaries() {
        byte[] code = new byte[]{
                0x11, 0x7F, (byte) 0xFF, // sipush 32767
                0x11, (byte) 0x80, 0x00, // sipush -32768
                0x11, (byte) 0xFF, (byte) 0xFE  // sipush -2
        };
        Frame frame = createFrame(code, 1, 3);
        interpreter.execute(frame);

        assertEquals(-2, frame.operandStack().popInt());
        assertEquals(-32768, frame.operandStack().popInt());
        assertEquals(32767, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("istore and iload move values between operand stack and local variables")
    void testLoadAndStore() {
        // bipush 42 -> istore 5 -> iload 5
        byte[] code = new byte[]{
                0x10, 42,
                0x36, 0x05,
                0x15, 0x05
        };
        Frame frame = createFrame(code, 6, 2);

        interpreter.step(frame); // bipush 42
        assertEquals(1, frame.operandStack().slots());

        interpreter.step(frame); // istore 5
        assertEquals(0, frame.operandStack().slots());
        assertEquals(42, frame.locals().getInt(5));

        interpreter.step(frame); // iload 5
        assertEquals(1, frame.operandStack().slots());
        assertEquals(42, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("istore_0..3 and iload_0..3 operate on indices 0 through 3")
    void testShortLoadAndStore() {
        // iconst_1 -> istore_0, iconst_2 -> istore_1, iconst_3 -> istore_2, iconst_4 -> istore_3
        // iload_0, iload_1, iload_2, iload_3
        byte[] code = new byte[]{
                0x04, 0x3B, // iconst_1, istore_0
                0x05, 0x3C, // iconst_2, istore_1
                0x06, 0x3D, // iconst_3, istore_2
                0x07, 0x3E, // iconst_4, istore_3
                0x1A, 0x1B, 0x1C, 0x1D // iload_0, iload_1, iload_2, iload_3
        };
        Frame frame = createFrame(code, 4, 4);
        interpreter.execute(frame);

        assertEquals(4, frame.operandStack().slots());
        assertEquals(4, frame.operandStack().popInt());
        assertEquals(3, frame.operandStack().popInt());
        assertEquals(2, frame.operandStack().popInt());
        assertEquals(1, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("iload fails if local variable is uninitialized")
    void testLoadUninitializedFails() {
        byte[] code = new byte[]{0x1A}; // iload_0
        Frame frame = createFrame(code, 1, 1);

        assertThrows(StackFaultException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("iload fails if local variable contains non-int type")
    void testLoadTypeMismatchFails() {
        byte[] code = new byte[]{0x1A}; // iload_0
        Frame frame = createFrame(code, 1, 1);
        frame.locals().setFloat(0, 3.14f);

        assertThrows(StackFaultException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("istore fails if operand on stack is non-int type")
    void testStoreTypeMismatchFails() {
        byte[] code = new byte[]{0x3B}; // istore_0
        Frame frame = createFrame(code, 1, 1);
        frame.operandStack().push(Value.nullRef());

        assertThrows(StackFaultException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("iadd computes addition and respects 32-bit two's complement wraparound")
    void testIadd() {
        // normal addition: 10 + 20 = 30
        byte[] normalCode = new byte[]{0x10, 10, 0x10, 20, 0x60};
        Frame f1 = createFrame(normalCode, 1, 2);
        interpreter.execute(f1);
        assertEquals(30, f1.operandStack().popInt());

        // overflow: Integer.MAX_VALUE + 1 = Integer.MIN_VALUE
        byte[] overflowCode = new byte[]{0x60};
        Frame f2 = createFrame(overflowCode, 1, 2);
        f2.operandStack().push(Value.ofInt(Integer.MAX_VALUE));
        f2.operandStack().push(Value.ofInt(1));
        interpreter.execute(f2);
        assertEquals(Integer.MIN_VALUE, f2.operandStack().popInt());
    }

    @Test
    @DisplayName("isub computes subtraction and respects wraparound")
    void testIsub() {
        // 30 - 10 = 20
        byte[] code = new byte[]{0x10, 30, 0x10, 10, 0x64};
        Frame f1 = createFrame(code, 1, 2);
        interpreter.execute(f1);
        assertEquals(20, f1.operandStack().popInt());

        // underflow: Integer.MIN_VALUE - 1 = Integer.MAX_VALUE
        Frame f2 = createFrame(new byte[]{0x64}, 1, 2);
        f2.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
        f2.operandStack().push(Value.ofInt(1));
        interpreter.execute(f2);
        assertEquals(Integer.MAX_VALUE, f2.operandStack().popInt());
    }

    @Test
    @DisplayName("imul computes multiplication and respects wraparound")
    void testImul() {
        // 6 * 7 = 42
        byte[] code = new byte[]{0x10, 6, 0x10, 7, 0x68};
        Frame f1 = createFrame(code, 1, 2);
        interpreter.execute(f1);
        assertEquals(42, f1.operandStack().popInt());

        // overflow
        Frame f2 = createFrame(new byte[]{0x68}, 1, 2);
        f2.operandStack().push(Value.ofInt(Integer.MAX_VALUE));
        f2.operandStack().push(Value.ofInt(2));
        interpreter.execute(f2);
        assertEquals(-2, f2.operandStack().popInt());
    }

    @Test
    @DisplayName("idiv computes division and settles JVMS corner cases")
    void testIdiv() {
        // 100 / 3 = 33
        Frame f1 = createFrame(new byte[]{0x6C}, 1, 2);
        f1.operandStack().push(Value.ofInt(100));
        f1.operandStack().push(Value.ofInt(3));
        interpreter.execute(f1);
        assertEquals(33, f1.operandStack().popInt());

        // -100 / 3 = -33
        Frame f2 = createFrame(new byte[]{0x6C}, 1, 2);
        f2.operandStack().push(Value.ofInt(-100));
        f2.operandStack().push(Value.ofInt(3));
        interpreter.execute(f2);
        assertEquals(-33, f2.operandStack().popInt());

        // Division by zero throws ArithmeticFaultException
        Frame f3 = createFrame(new byte[]{0x6C}, 1, 2);
        f3.operandStack().push(Value.ofInt(10));
        f3.operandStack().push(Value.ofInt(0));
        ArithmeticFaultException ex = assertThrows(ArithmeticFaultException.class, () -> interpreter.execute(f3));
        assertEquals("/ by zero", ex.getMessage());

        // JVMS 6.5.idiv: Integer.MIN_VALUE / -1 overflows to Integer.MIN_VALUE without exception
        Frame f4 = createFrame(new byte[]{0x6C}, 1, 2);
        f4.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
        f4.operandStack().push(Value.ofInt(-1));
        interpreter.execute(f4);
        assertEquals(Integer.MIN_VALUE, f4.operandStack().popInt());
    }

    @Test
    @DisplayName("irem computes remainder and settles JVMS corner cases")
    void testIrem() {
        // 100 % 3 = 1
        Frame f1 = createFrame(new byte[]{0x70}, 1, 2);
        f1.operandStack().push(Value.ofInt(100));
        f1.operandStack().push(Value.ofInt(3));
        interpreter.execute(f1);
        assertEquals(1, f1.operandStack().popInt());

        // -100 % 3 = -1
        Frame f2 = createFrame(new byte[]{0x70}, 1, 2);
        f2.operandStack().push(Value.ofInt(-100));
        f2.operandStack().push(Value.ofInt(3));
        interpreter.execute(f2);
        assertEquals(-1, f2.operandStack().popInt());

        // Remainder by zero throws ArithmeticFaultException
        Frame f3 = createFrame(new byte[]{0x70}, 1, 2);
        f3.operandStack().push(Value.ofInt(10));
        f3.operandStack().push(Value.ofInt(0));
        ArithmeticFaultException ex = assertThrows(ArithmeticFaultException.class, () -> interpreter.execute(f3));
        assertEquals("/ by zero", ex.getMessage());

        // JVMS 6.5.irem: Integer.MIN_VALUE % -1 equals 0
        Frame f4 = createFrame(new byte[]{0x70}, 1, 2);
        f4.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
        f4.operandStack().push(Value.ofInt(-1));
        interpreter.execute(f4);
        assertEquals(0, f4.operandStack().popInt());
    }

    @Test
    @DisplayName("ineg negates integer value")
    void testIneg() {
        Frame f1 = createFrame(new byte[]{0x74}, 1, 1);
        f1.operandStack().push(Value.ofInt(42));
        interpreter.execute(f1);
        assertEquals(-42, f1.operandStack().popInt());

        Frame f2 = createFrame(new byte[]{0x74}, 1, 1);
        f2.operandStack().push(Value.ofInt(-42));
        interpreter.execute(f2);
        assertEquals(42, f2.operandStack().popInt());

        // -0 = 0
        Frame f3 = createFrame(new byte[]{0x74}, 1, 1);
        f3.operandStack().push(Value.ofInt(0));
        interpreter.execute(f3);
        assertEquals(0, f3.operandStack().popInt());

        // -Integer.MIN_VALUE = Integer.MIN_VALUE
        Frame f4 = createFrame(new byte[]{0x74}, 1, 1);
        f4.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
        interpreter.execute(f4);
        assertEquals(Integer.MIN_VALUE, f4.operandStack().popInt());
    }

    @Test
    @DisplayName("Arithmetic operations fail with StackFaultException on operand stack underflow")
    void testArithmeticUnderflow() {
        // iadd requires 2 operands; empty stack throws
        Frame f1 = createFrame(new byte[]{0x60}, 1, 2);
        assertThrows(StackFaultException.class, () -> interpreter.step(f1));

        // iadd with only 1 operand throws
        Frame f2 = createFrame(new byte[]{0x60}, 1, 2);
        f2.operandStack().push(Value.ofInt(1));
        assertThrows(StackFaultException.class, () -> interpreter.step(f2));

        // ineg with empty stack throws
        Frame f3 = createFrame(new byte[]{0x74}, 1, 1);
        assertThrows(StackFaultException.class, () -> interpreter.step(f3));
    }

    private Frame createFrame(byte[] code, int maxLocals, int maxStack) {
        CodeAttribute codeAttr = new CodeAttribute(maxStack, maxLocals, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(0, 1, 2, List.of(), codeAttr);
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.Utf8Entry("testMethod"),
                new ConstantPoolEntry.Utf8Entry("()V")
        ));
        return new Frame(method, cp, maxLocals, maxStack);
    }
}
