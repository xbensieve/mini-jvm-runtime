package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStatus;
import dev.ben.minijvm.runtime.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-style invariant checks for control flow instructions (Section 17):
 * - Target calculation determinism across repeated executions
 * - Sequential PC invariant (instructionPc + length)
 * - Branch PC invariant (instructionPc + signed offset)
 * - Operand stack stability (no unexpected growth across repeated branches)
 * - Loop execution iteratively bounds host call stack (no host recursion)
 */
class ControlFlowPropertyTest {

    private final Interpreter interpreter = new Interpreter();

    @Test
    @DisplayName("Invariant 1: Branch target calculation is deterministic across repeated executions")
    void testBranchTargetDeterminism() {
        byte[] code = new byte[]{
                (byte) Opcode.IFEQ.code(), 0x00, 0x06, // 0: ifeq +6 -> 6
                0x00,                                  // 3: nop
                0x00,                                  // 4: nop
                0x00,                                  // 5: nop
                (byte) Opcode.RETURN.code()            // 6: return
        };

        for (int i = 0; i < 20; i++) {
            Frame frame = createFrame(code, 1, 2, "()V");
            frame.operandStack().push(Value.ofInt(0));

            Instruction ins = interpreter.step(frame);

            assertEquals(Opcode.IFEQ, ins.opcode());
            assertEquals(0, ins.pc());
            assertEquals(6, frame.pc(), "ifeq on 0 must deterministically jump to PC 6 on iteration " + i);
            assertEquals(0, frame.lastInstructionPc());
        }
    }

    @Test
    @DisplayName("Invariant 2: Sequential PC strictly equals instructionPc + length on fall-through")
    void testSequentialPcInvariant() {
        // ifeq when operand != 0 falls through
        byte[] code = new byte[]{
                (byte) Opcode.IFEQ.code(), 0x00, 0x06, // 0: ifeq +6 (len 3)
                (byte) Opcode.NOP.code(),              // 3: nop (len 1)
                (byte) Opcode.RETURN.code()            // 4: return (len 1)
        };
        Frame frame = createFrame(code, 1, 2, "()V");
        frame.operandStack().push(Value.ofInt(99)); // non-zero -> fall-through

        Instruction ins = interpreter.step(frame);

        assertEquals(0, ins.pc());
        assertEquals(3, ins.length());
        assertEquals(ins.pc() + ins.length(), frame.pc(), "Sequential PC must strictly equal instructionPc + length");
    }

    @Test
    @DisplayName("Invariant 3: Branch PC strictly equals instructionPc + signed offset on branch taken")
    void testBranchPcInvariant() {
        // ifne when operand != 0 branches
        short offset = -10;
        byte b1 = (byte) ((offset >> 8) & 0xFF);
        byte b2 = (byte) (offset & 0xFF);

        // Place instruction at PC 10: target = 10 + (-10) = 0
        byte[] code = new byte[15];
        code[10] = (byte) Opcode.GOTO.code();
        code[11] = b1;
        code[12] = b2;

        Frame frame = createFrame(code, 1, 1, "()V");
        frame.setPc(10);

        Instruction ins = interpreter.step(frame);

        assertEquals(10, ins.pc());
        assertEquals(offset, ins.branchOffset());
        assertEquals(ins.pc() + ins.branchOffset(), frame.pc(), "Branch target must strictly equal instructionPc + signed offset");
        assertEquals(0, frame.pc());
    }

    @ParameterizedTest(name = "Loop iteration count {0} does not leak operand stack slots")
    @ValueSource(ints = {1, 5, 20, 100})
    @DisplayName("Invariant 4: Repeated branching does not leak operand stack slots")
    void testNoStackLeakAcrossBranches(int iterations) {
        // Loop decrementing local 0 from N down to 0:
        // 0: iload_0 (len 1)
        // 1: ifeq +9 -> 10 (len 3)
        // 4: iinc 0 by -1 (len 3)
        // 7: goto -7 -> 0 (len 3)
        // 10: return (len 1)
        byte[] code = new byte[]{
                0x1A,                                   // 0: iload_0
                (byte) 0x99, 0x00, 0x09,                // 1: ifeq +9 -> 10
                (byte) 0x84, 0x00, (byte) 0xFF,         // 4: iinc 0 by -1
                (byte) 0xA7, (byte) 0xFF, (byte) 0xF9,  // 7: goto -7 -> 0
                (byte) 0xB1                             // 10: return
        };
        Frame frame = createFrame(code, 1, 2, "()V");
        frame.locals().setInt(0, iterations);

        interpreter.execute(frame);

        assertTrue(frame.isCompleted());
        assertEquals(FrameStatus.RETURNED, frame.status());
        assertEquals(0, frame.operandStack().slots(), "Operand stack must have 0 slots remaining upon return");
        assertEquals(0, frame.locals().getInt(0), "Local 0 must be decremented exactly to 0");
    }

    @Test
    @DisplayName("Invariant 5: Large loop execution executes iteratively without host call stack growth")
    void testLargeLoopExecutesIterativelyWithoutStackOverflow() {
        // Run 50,000 loop iterations. If interpreter used host recursion, this would cause StackOverflowError.
        byte[] code = new byte[]{
                0x1A,                                   // 0: iload_0
                (byte) 0x99, 0x00, 0x09,                // 1: ifeq +9 -> 10
                (byte) 0x84, 0x00, (byte) 0xFF,         // 4: iinc 0 by -1
                (byte) 0xA7, (byte) 0xFF, (byte) 0xF9,  // 7: goto -7 -> 0
                (byte) 0xB1                             // 10: return
        };
        Frame frame = createFrame(code, 1, 2, "()V");
        frame.locals().setInt(0, 50_000);

        assertDoesNotThrow(() -> interpreter.execute(frame));

        assertTrue(frame.isCompleted());
        assertEquals(0, frame.locals().getInt(0));
    }

    private Frame createFrame(byte[] code, int maxLocals, int maxStack, String descriptor) {
        CodeAttribute codeAttr = new CodeAttribute(maxStack, maxLocals, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(0, 1, 2, List.of(), codeAttr);
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.Utf8Entry("testMethod"),
                new ConstantPoolEntry.Utf8Entry(descriptor)
        ));
        return new Frame(method, cp, maxLocals, maxStack);
    }
}
