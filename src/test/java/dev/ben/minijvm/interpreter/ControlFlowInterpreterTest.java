package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates execution of all Phase 04 control flow and return opcodes:
 * conditional jumps, binary comparisons, unconditional goto, iinc, return, and ireturn.
 */
class ControlFlowInterpreterTest {

    private final Interpreter interpreter = new Interpreter();

    // ==========================================
    // UNARY CONDITIONAL BRANCHES (ifeq .. ifle)
    // ==========================================

    @Test
    @DisplayName("ifeq jumps when top of stack is 0 and falls through when non-zero")
    void testIfeq() {
        // 0: ifeq +4 -> target PC 4; 3: nop; 4: nop
        byte[] code = new byte[]{
                (byte) 0x99, 0x00, 0x04, // 0: ifeq +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };

        // Case 1: val == 0 (jump taken)
        Frame f1 = createFrame(code, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(0));
        interpreter.step(f1);
        assertEquals(4, f1.pc(), "ifeq must jump to PC 4 when operand is 0");
        assertTrue(f1.operandStack().isEmpty());

        // Case 2: val == 1 (fall through)
        Frame f2 = createFrame(code, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(1));
        interpreter.step(f2);
        assertEquals(3, f2.pc(), "ifeq must fall through to sequential PC 3 when operand is 1");

        // Case 3: val == -1 (fall through)
        Frame f3 = createFrame(code, 1, 2, "()V");
        f3.operandStack().push(Value.ofInt(-1));
        interpreter.step(f3);
        assertEquals(3, f3.pc(), "ifeq must fall through to sequential PC 3 when operand is -1");
    }

    @Test
    @DisplayName("ifne jumps when top of stack is non-zero and falls through when zero")
    void testIfne() {
        byte[] code = new byte[]{
                (byte) 0x9A, 0x00, 0x04, // 0: ifne +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };

        // Case 1: val == 42 (jump taken)
        Frame f1 = createFrame(code, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(42));
        interpreter.step(f1);
        assertEquals(4, f1.pc());

        // Case 2: val == 0 (fall through)
        Frame f2 = createFrame(code, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(0));
        interpreter.step(f2);
        assertEquals(3, f2.pc());
    }

    @Test
    @DisplayName("iflt jumps when top of stack is negative (< 0)")
    void testIflt() {
        byte[] code = new byte[]{
                (byte) 0x9B, 0x00, 0x04, // 0: iflt +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };

        // Case 1: val == -5 (jump taken)
        Frame f1 = createFrame(code, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(-5));
        interpreter.step(f1);
        assertEquals(4, f1.pc());

        // Case 2: val == Integer.MIN_VALUE (jump taken)
        Frame f2 = createFrame(code, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(Integer.MIN_VALUE));
        interpreter.step(f2);
        assertEquals(4, f2.pc());

        // Case 3: val == 0 (fall through)
        Frame f3 = createFrame(code, 1, 2, "()V");
        f3.operandStack().push(Value.ofInt(0));
        interpreter.step(f3);
        assertEquals(3, f3.pc());

        // Case 4: val == 10 (fall through)
        Frame f4 = createFrame(code, 1, 2, "()V");
        f4.operandStack().push(Value.ofInt(10));
        interpreter.step(f4);
        assertEquals(3, f4.pc());
    }

    @Test
    @DisplayName("ifge jumps when top of stack is non-negative (>= 0)")
    void testIfge() {
        byte[] code = new byte[]{
                (byte) 0x9C, 0x00, 0x04, // 0: ifge +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };

        // Case 1: val == 0 (jump taken)
        Frame f1 = createFrame(code, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(0));
        interpreter.step(f1);
        assertEquals(4, f1.pc());

        // Case 2: val == Integer.MAX_VALUE (jump taken)
        Frame f2 = createFrame(code, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(Integer.MAX_VALUE));
        interpreter.step(f2);
        assertEquals(4, f2.pc());

        // Case 3: val == -1 (fall through)
        Frame f3 = createFrame(code, 1, 2, "()V");
        f3.operandStack().push(Value.ofInt(-1));
        interpreter.step(f3);
        assertEquals(3, f3.pc());
    }

    @Test
    @DisplayName("ifgt jumps when top of stack is strictly positive (> 0)")
    void testIfgt() {
        byte[] code = new byte[]{
                (byte) 0x9D, 0x00, 0x04, // 0: ifgt +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };

        // Case 1: val == 1 (jump taken)
        Frame f1 = createFrame(code, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(1));
        interpreter.step(f1);
        assertEquals(4, f1.pc());

        // Case 2: val == 0 (fall through)
        Frame f2 = createFrame(code, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(0));
        interpreter.step(f2);
        assertEquals(3, f2.pc());

        // Case 3: val == -100 (fall through)
        Frame f3 = createFrame(code, 1, 2, "()V");
        f3.operandStack().push(Value.ofInt(-100));
        interpreter.step(f3);
        assertEquals(3, f3.pc());
    }

    @Test
    @DisplayName("ifle jumps when top of stack is non-positive (<= 0)")
    void testIfle() {
        byte[] code = new byte[]{
                (byte) 0x9E, 0x00, 0x04, // 0: ifle +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };

        // Case 1: val == 0 (jump taken)
        Frame f1 = createFrame(code, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(0));
        interpreter.step(f1);
        assertEquals(4, f1.pc());

        // Case 2: val == -1 (jump taken)
        Frame f2 = createFrame(code, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(-1));
        interpreter.step(f2);
        assertEquals(4, f2.pc());

        // Case 3: val == 1 (fall through)
        Frame f3 = createFrame(code, 1, 2, "()V");
        f3.operandStack().push(Value.ofInt(1));
        interpreter.step(f3);
        assertEquals(3, f3.pc());
    }

    // ==========================================
    // BINARY COMPARISON BRANCHES (if_icmp*)
    // ==========================================

    @Test
    @DisplayName("if_icmpeq and if_icmpne compare top two integers")
    void testIfIcmpeqAndNe() {
        byte[] eqCode = new byte[]{(byte) 0x9F, 0x00, 0x05, 0x00, 0x00, 0x00};
        byte[] neCode = new byte[]{(byte) 0xA0, 0x00, 0x05, 0x00, 0x00, 0x00};

        // 10 == 10: eq jumps, ne falls through
        Frame f1 = createFrame(eqCode, 1, 2, "()V");
        f1.operandStack().push(Value.ofInt(10));
        f1.operandStack().push(Value.ofInt(10));
        interpreter.step(f1);
        assertEquals(5, f1.pc());

        Frame f2 = createFrame(neCode, 1, 2, "()V");
        f2.operandStack().push(Value.ofInt(10));
        f2.operandStack().push(Value.ofInt(10));
        interpreter.step(f2);
        assertEquals(3, f2.pc());

        // 10 != 20: eq falls through, ne jumps
        Frame f3 = createFrame(eqCode, 1, 2, "()V");
        f3.operandStack().push(Value.ofInt(10));
        f3.operandStack().push(Value.ofInt(20));
        interpreter.step(f3);
        assertEquals(3, f3.pc());

        Frame f4 = createFrame(neCode, 1, 2, "()V");
        f4.operandStack().push(Value.ofInt(10));
        f4.operandStack().push(Value.ofInt(20));
        interpreter.step(f4);
        assertEquals(5, f4.pc());
    }

    @Test
    @DisplayName("if_icmplt, if_icmpge, if_icmpgt, if_icmple compare val1 <op> val2")
    void testIfIcmpRelational() {
        byte[] ltCode = new byte[]{(byte) 0xA1, 0x00, 0x05, 0x00, 0x00, 0x00};
        byte[] geCode = new byte[]{(byte) 0xA2, 0x00, 0x05, 0x00, 0x00, 0x00};
        byte[] gtCode = new byte[]{(byte) 0xA3, 0x00, 0x05, 0x00, 0x00, 0x00};
        byte[] leCode = new byte[]{(byte) 0xA4, 0x00, 0x05, 0x00, 0x00, 0x00};

        // val1 = 5, val2 = 10 -> 5 < 10 is true: lt jumps, ge falls through, gt falls through, le jumps
        Frame fLt = createFrame(ltCode, 1, 2, "()V");
        fLt.operandStack().push(Value.ofInt(5));
        fLt.operandStack().push(Value.ofInt(10));
        interpreter.step(fLt);
        assertEquals(5, fLt.pc());

        Frame fGe = createFrame(geCode, 1, 2, "()V");
        fGe.operandStack().push(Value.ofInt(5));
        fGe.operandStack().push(Value.ofInt(10));
        interpreter.step(fGe);
        assertEquals(3, fGe.pc());

        Frame fGt = createFrame(gtCode, 1, 2, "()V");
        fGt.operandStack().push(Value.ofInt(5));
        fGt.operandStack().push(Value.ofInt(10));
        interpreter.step(fGt);
        assertEquals(3, fGt.pc());

        Frame fLe = createFrame(leCode, 1, 2, "()V");
        fLe.operandStack().push(Value.ofInt(5));
        fLe.operandStack().push(Value.ofInt(10));
        interpreter.step(fLe);
        assertEquals(5, fLe.pc());
    }

    // ==========================================
    // UNCONDITIONAL JUMP (goto) & BOUNDS
    // ==========================================

    @Test
    @DisplayName("goto unconditionally jumps forward and backward")
    void testGoto() {
        // Forward jump: 0: goto +4 -> 4
        byte[] forwardCode = new byte[]{
                (byte) 0xA7, 0x00, 0x04, // 0: goto +4
                0x00,                     // 3: nop
                0x00                      // 4: nop
        };
        Frame f1 = createFrame(forwardCode, 1, 1, "()V");
        interpreter.step(f1);
        assertEquals(4, f1.pc());

        // Backward jump: 3: goto -3 -> 0
        byte[] backwardCode = new byte[]{
                0x00,                     // 0: nop
                0x00,                     // 1: nop
                0x00,                     // 2: nop
                (byte) 0xA7, (byte) 0xFF, (byte) 0xFD // 3: goto -3
        };
        Frame f2 = createFrame(backwardCode, 1, 1, "()V");
        f2.setPc(3);
        interpreter.step(f2);
        assertEquals(0, f2.pc());
    }

    @Test
    @DisplayName("Branch targets outside method code throw StackFaultException")
    void testBranchOutOfBoundsFails() {
        // Jump past end of code: 0: goto +10 (code length 5)
        byte[] outPastEnd = new byte[]{(byte) 0xA7, 0x00, 0x0A, 0x00, 0x00};
        Frame f1 = createFrame(outPastEnd, 1, 1, "()V");
        assertThrows(StackFaultException.class, () -> interpreter.step(f1));

        // Jump before start of code: 0: goto -5
        byte[] outBeforeStart = new byte[]{(byte) 0xA7, (byte) 0xFF, (byte) 0xFB, 0x00, 0x00};
        Frame f2 = createFrame(outBeforeStart, 1, 1, "()V");
        assertThrows(StackFaultException.class, () -> interpreter.step(f2));

        // Jump exactly to codeLength (boundary): 0: goto +3 in code of length 3
        byte[] toCodeLength = new byte[]{(byte) 0xA7, 0x00, 0x03};
        Frame f3 = createFrame(toCodeLength, 1, 1, "()V");
        assertThrows(StackFaultException.class, () -> interpreter.step(f3));
    }

    // ==========================================
    // LOCAL INCREMENT (iinc)
    // ==========================================

    @Test
    @DisplayName("iinc increments local variable with positive, negative, and wraparound values")
    void testIinc() {
        byte[] code = new byte[]{
                (byte) 0x84, 0x00, 0x01,        // 0: iinc 0 by 1
                (byte) 0x84, 0x00, (byte) 0xFE, // 3: iinc 0 by -2
                (byte) 0x84, 0x01, 0x01         // 6: iinc 1 by 1 (wraparound)
        };
        Frame frame = createFrame(code, 2, 1, "()V");
        frame.locals().setInt(0, 10);
        frame.locals().setInt(1, Integer.MAX_VALUE);

        interpreter.step(frame); // iinc 0 by 1 -> 11
        assertEquals(3, frame.pc());
        assertEquals(11, frame.locals().getInt(0));

        interpreter.step(frame); // iinc 0 by -2 -> 9
        assertEquals(6, frame.pc());
        assertEquals(9, frame.locals().getInt(0));

        interpreter.step(frame); // iinc 1 by 1 -> MAX_VALUE + 1 = MIN_VALUE
        assertEquals(9, frame.pc());
        assertEquals(Integer.MIN_VALUE, frame.locals().getInt(1));
    }

    @Test
    @DisplayName("iinc throws StackFaultException on uninitialized local or type mismatch")
    void testIincInvalidLocalFails() {
        byte[] code = new byte[]{(byte) 0x84, 0x00, 0x01}; // iinc 0 by 1

        // Uninitialized
        Frame f1 = createFrame(code, 1, 1, "()V");
        assertThrows(StackFaultException.class, () -> interpreter.step(f1));

        // Type mismatch (float stored in local 0)
        Frame f2 = createFrame(code, 1, 1, "()V");
        f2.locals().setFloat(0, 3.14f);
        assertThrows(StackFaultException.class, () -> interpreter.step(f2));
    }

    // ==========================================
    // RETURNS & METHOD COMPLETION
    // ==========================================

    @Test
    @DisplayName("return completes void method and stops sequential execution")
    void testReturnOpcode() {
        // 0: nop; 1: return; 2: nop (unreached)
        byte[] code = new byte[]{0x00, (byte) 0xB1, 0x00};
        Frame frame = createFrame(code, 1, 1, "()V");

        interpreter.execute(frame);

        assertTrue(frame.isCompleted());
        assertEquals(2, frame.pc());
        assertEquals(1, frame.lastInstructionPc());

        // Stepping a completed frame throws StackFaultException
        assertThrows(StackFaultException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("return throws StackFaultException when method has non-void return descriptor")
    void testReturnInNonVoidMethodThrows() {
        byte[] code = new byte[]{(byte) 0xB1}; // return
        Frame frame = createFrame(code, 1, 1, "()I"); // method returns int!

        assertThrows(StackFaultException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("ireturn returns int value and sets returnValue on Frame")
    void testIreturnOpcode() {
        // 0: bipush 42; 2: ireturn; 3: nop (unreached)
        byte[] code = new byte[]{0x10, 42, (byte) 0xAC, 0x00};
        Frame frame = createFrame(code, 1, 2, "()I");

        interpreter.execute(frame);

        assertTrue(frame.isCompleted());
        assertEquals(3, frame.pc());
        assertTrue(frame.returnValue().isPresent());
        assertEquals(42, frame.returnValue().get().asInt());
        assertTrue(frame.operandStack().isEmpty());
    }

    @Test
    @DisplayName("ireturn throws on empty operand stack or non-int type")
    void testIreturnUnderflowAndTypeMismatch() {
        byte[] code = new byte[]{(byte) 0xAC}; // ireturn

        // Underflow: empty stack
        Frame f1 = createFrame(code, 1, 1, "()I");
        assertThrows(StackFaultException.class, () -> interpreter.step(f1));

        // Type mismatch: null reference on stack
        Frame f2 = createFrame(code, 1, 1, "()I");
        f2.operandStack().push(Value.nullRef());
        assertThrows(StackFaultException.class, () -> interpreter.step(f2));

        // Method descriptor mismatch: method returns void but calls ireturn
        Frame f3 = createFrame(code, 1, 1, "()V");
        f3.operandStack().push(Value.ofInt(1));
        assertThrows(StackFaultException.class, () -> interpreter.step(f3));
    }

    // ==========================================
    // FRAMESTACK INTEGRATION
    // ==========================================

    @Test
    @DisplayName("ireturn pops callee frame and pushes return value to caller operand stack")
    void testFrameStackReturnLifecycle() {
        FrameStack frameStack = new FrameStack();

        // Caller method: ()V
        byte[] callerCode = new byte[]{0x00, 0x00}; // nop, nop
        Frame callerFrame = createFrame(callerCode, 1, 2, "()V");
        frameStack.push(callerFrame);

        // Callee method: ()I
        byte[] calleeCode = new byte[]{0x10, 99, (byte) 0xAC}; // bipush 99, ireturn
        Frame calleeFrame = createFrame(calleeCode, 1, 2, "()I");
        frameStack.push(calleeFrame);

        assertEquals(2, frameStack.depth());
        assertSame(calleeFrame, frameStack.current());

        // Step callee: bipush 99
        interpreter.step(calleeFrame, frameStack);
        assertEquals(2, calleeFrame.pc());

        // Step callee: ireturn
        interpreter.step(calleeFrame, frameStack);

        // Callee should be popped from frameStack
        assertEquals(1, frameStack.depth());
        assertSame(callerFrame, frameStack.current());
        assertTrue(calleeFrame.isCompleted());

        // Caller operand stack now holds the returned value 99
        assertEquals(1, callerFrame.operandStack().slots());
        assertEquals(99, callerFrame.operandStack().popInt());
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
