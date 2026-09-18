package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.ArithmeticFaultException;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates program counter (PC) progression, faulting PC identification,
 * and abrupt execution invariants.
 */
class PcFailureAndProgressionTest {

    private final Interpreter interpreter = new Interpreter();

    @Test
    @DisplayName("Scenario 1: Successful one-byte instruction advances PC by 1")
    void testSuccessfulOneByteInstructionAdvancesPcBy1() {
        Frame frame = createFrame(new byte[]{0x03}, 1, 1); // iconst_0
        assertEquals(0, frame.pc());
        assertEquals(-1, frame.lastInstructionPc());

        interpreter.step(frame);

        assertEquals(1, frame.pc());
        assertEquals(0, frame.lastInstructionPc());
        assertEquals(1, frame.operandStack().slots());
        assertEquals(0, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("Scenario 2: bipush advances PC by 2")
    void testBipushAdvancesPcBy2() {
        Frame frame = createFrame(new byte[]{0x10, 42}, 1, 1); // bipush 42
        assertEquals(0, frame.pc());
        assertEquals(-1, frame.lastInstructionPc());

        interpreter.step(frame);

        assertEquals(2, frame.pc());
        assertEquals(0, frame.lastInstructionPc());
        assertEquals(1, frame.operandStack().slots());
        assertEquals(42, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("Scenario 3: sipush advances PC by 3")
    void testSipushAdvancesPcBy3() {
        Frame frame = createFrame(new byte[]{0x11, 0x03, (byte) 0xE8}, 1, 1); // sipush 1000
        assertEquals(0, frame.pc());
        assertEquals(-1, frame.lastInstructionPc());

        interpreter.step(frame);

        assertEquals(3, frame.pc());
        assertEquals(0, frame.lastInstructionPc());
        assertEquals(1, frame.operandStack().slots());
        assertEquals(1000, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("Scenario 4: iload and istore advance PC by 2")
    void testIloadAndIstoreAdvancePcBy2() {
        // iconst_5 (1 byte at 0), istore 2 (2 bytes at 1), iload 2 (2 bytes at 3)
        byte[] code = new byte[]{
                0x08,       // 0: iconst_5
                0x36, 0x02, // 1: istore 2
                0x15, 0x02  // 3: iload 2
        };
        Frame frame = createFrame(code, 3, 2);

        interpreter.step(frame); // iconst_5
        assertEquals(1, frame.pc());
        assertEquals(0, frame.lastInstructionPc());

        interpreter.step(frame); // istore 2
        assertEquals(3, frame.pc());
        assertEquals(1, frame.lastInstructionPc());
        assertEquals(5, frame.locals().getInt(2));
        assertTrue(frame.operandStack().isEmpty());

        interpreter.step(frame); // iload 2
        assertEquals(5, frame.pc());
        assertEquals(3, frame.lastInstructionPc());
        assertEquals(5, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("Scenario 5: Multi-instruction sequence advances PC correctly across mixed lengths")
    void testMultiInstructionSequenceAdvancesCorrectly() {
        // iconst_1 (0), iconst_2 (1), iadd (2), istore 0 (3..4), bipush 10 (5..6), iload 0 (7..8), imul (9)
        byte[] code = new byte[]{
                0x04,       // 0: iconst_1 (len 1)
                0x05,       // 1: iconst_2 (len 1)
                0x60,       // 2: iadd (len 1)
                0x36, 0x00, // 3: istore 0 (len 2)
                0x10, 0x0A, // 5: bipush 10 (len 2)
                0x15, 0x00, // 7: iload 0 (len 2)
                0x68        // 9: imul (len 1)
        };
        Frame frame = createFrame(code, 2, 3);

        interpreter.step(frame); // iconst_1
        assertEquals(1, frame.pc());
        assertEquals(0, frame.lastInstructionPc());

        interpreter.step(frame); // iconst_2
        assertEquals(2, frame.pc());
        assertEquals(1, frame.lastInstructionPc());

        interpreter.step(frame); // iadd
        assertEquals(3, frame.pc());
        assertEquals(2, frame.lastInstructionPc());

        interpreter.step(frame); // istore 0
        assertEquals(5, frame.pc());
        assertEquals(3, frame.lastInstructionPc());
        assertEquals(3, frame.locals().getInt(0));

        interpreter.step(frame); // bipush 10
        assertEquals(7, frame.pc());
        assertEquals(5, frame.lastInstructionPc());

        interpreter.step(frame); // iload 0
        assertEquals(9, frame.pc());
        assertEquals(7, frame.lastInstructionPc());

        interpreter.step(frame); // imul
        assertEquals(10, frame.pc());
        assertEquals(9, frame.lastInstructionPc());
        assertEquals(30, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("Scenario 6: idiv by zero preserves identifiable faulting instruction PC and next sequential PC")
    void testIdivByZeroPreservesIdentifiableFaultingInstructionPc() {
        // bipush 10 (0..1), iconst_0 (2), idiv (3)
        byte[] code = new byte[]{
                0x10, 0x0A, // 0: bipush 10
                0x03,       // 2: iconst_0
                0x6C        // 3: idiv
        };
        Frame frame = createFrame(code, 1, 2);

        interpreter.step(frame); // bipush 10
        assertEquals(2, frame.pc());
        assertEquals(0, frame.lastInstructionPc());

        interpreter.step(frame); // iconst_0
        assertEquals(3, frame.pc());
        assertEquals(2, frame.lastInstructionPc());

        // idiv execution faults: divisor is zero
        ArithmeticFaultException ex = assertThrows(ArithmeticFaultException.class, () -> interpreter.step(frame));
        assertEquals("/ by zero", ex.getMessage());

        // State verification:
        // Faulting instruction PC is unambiguously identifiable via frame.lastInstructionPc()
        assertEquals(3, frame.lastInstructionPc(), "Faulting instruction PC must be 3");
        // Next sequential PC after advancement is preserved
        assertEquals(4, frame.pc(), "Sequential PC must be advanced to 4");
    }

    @Test
    @DisplayName("Scenario 7: irem by zero preserves identifiable faulting instruction PC and next sequential PC")
    void testIremByZeroPreservesIdentifiableFaultingInstructionPc() {
        // sipush 500 (0..2), iconst_0 (3), irem (4)
        byte[] code = new byte[]{
                0x11, 0x01, (byte) 0xF4, // 0: sipush 500
                0x03,                     // 3: iconst_0
                0x70                      // 4: irem
        };
        Frame frame = createFrame(code, 1, 2);

        interpreter.step(frame); // sipush 500
        assertEquals(3, frame.pc());

        interpreter.step(frame); // iconst_0
        assertEquals(4, frame.pc());

        // irem execution faults: divisor is zero
        ArithmeticFaultException ex = assertThrows(ArithmeticFaultException.class, () -> interpreter.step(frame));
        assertEquals("/ by zero", ex.getMessage());

        // State verification:
        assertEquals(4, frame.lastInstructionPc(), "Faulting instruction PC must be 4");
        assertEquals(5, frame.pc(), "Sequential PC must be advanced to 5");
    }

    @Test
    @DisplayName("Scenario 8: Stack fault preserves identifiable faulting instruction PC and next sequential PC")
    void testStackFaultPreservesIdentifiableFaultingInstructionPc() {
        // iconst_1 (0), iadd (1) -> iadd needs 2 operands, stack only has 1
        byte[] code = new byte[]{
                0x04, // 0: iconst_1
                0x60  // 1: iadd
        };
        Frame frame = createFrame(code, 1, 2);

        interpreter.step(frame); // iconst_1
        assertEquals(1, frame.pc());
        assertEquals(0, frame.lastInstructionPc());

        // iadd underflows operand stack
        StackFaultException ex = assertThrows(StackFaultException.class, () -> interpreter.step(frame));
        assertNotNull(ex.getMessage());

        // State verification:
        assertEquals(1, frame.lastInstructionPc(), "Faulting instruction PC must be 1");
        assertEquals(2, frame.pc(), "Sequential PC must be advanced to 2");
    }

    @Test
    @DisplayName("Scenario 9: Decode failure does not perform partial instruction execution")
    void testDecodeFailureDoesNotPerformPartialInstructionExecution() {
        // 0: iconst_5 (len 1), 1: truncated bipush (opcode 0x10 with no operand byte)
        byte[] code = new byte[]{
                0x08, // 0: iconst_5
                0x10  // 1: bipush (missing operand byte!)
        };
        Frame frame = createFrame(code, 2, 2);
        frame.locals().setInt(0, 42);

        interpreter.step(frame); // iconst_5
        assertEquals(1, frame.pc());
        assertEquals(0, frame.lastInstructionPc());
        assertEquals(1, frame.operandStack().slots());
        assertEquals(5, frame.operandStack().peek().asInt());

        // Attempting to step at PC 1 causes decode failure due to truncated instruction
        ClassFormatException ex = assertThrows(ClassFormatException.class, () -> interpreter.step(frame));
        assertTrue(ex.getMessage().contains("Truncated instruction"));

        // Verify state is completely untouched by the failed decode:
        assertEquals(1, frame.pc(), "PC must not advance on decode failure");
        assertEquals(0, frame.lastInstructionPc(), "lastInstructionPc must remain that of previous instruction");
        assertEquals(1, frame.operandStack().slots(), "Operand stack must not be modified");
        assertEquals(5, frame.operandStack().peek().asInt(), "Stack top must remain 5");
        assertEquals(42, frame.locals().getInt(0), "Local variables must not be modified");
    }

    @Test
    @DisplayName("Scenario 10: Stepping at end of code behaves according to documented contract")
    void testSteppingAtEndOfCodeContract() {
        // Code of length 1: nop (0x00)
        byte[] code = new byte[]{0x00};
        Frame frame = createFrame(code, 1, 1);

        interpreter.step(frame);
        assertEquals(1, frame.pc());
        assertEquals(0, frame.lastInstructionPc());

        // Stepping when frame.pc() >= code.length must throw StackFaultException and leave PC unchanged
        StackFaultException ex = assertThrows(StackFaultException.class, () -> interpreter.step(frame));
        assertTrue(ex.getMessage().contains("at or beyond code length"));

        assertEquals(1, frame.pc(), "PC must remain 1 at end of code");
        assertEquals(0, frame.lastInstructionPc(), "lastInstructionPc must remain 0");
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
