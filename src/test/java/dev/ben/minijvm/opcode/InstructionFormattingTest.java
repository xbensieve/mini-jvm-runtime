package dev.ben.minijvm.opcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstructionFormattingTest {

    @Test
    @DisplayName("Formats instructions without operands")
    void testNoOperandInstructions() {
        assertEquals("0: nop", new Instruction(Opcode.NOP, 0, 1, 0).toString());
        assertEquals("5: aconst_null", new Instruction(Opcode.ACONST_NULL, 5, 1, 0).toString());
        assertEquals("10: iconst_0", new Instruction(Opcode.ICONST_0, 10, 1, 0).toString());
        assertEquals("12: iadd", new Instruction(Opcode.IADD, 12, 1, 0).toString());
        assertEquals("13: ireturn", new Instruction(Opcode.IRETURN, 13, 1, 0).toString());
        assertEquals("20: arraylength", new Instruction(Opcode.ARRAYLENGTH, 20, 1, 0).toString());
    }

    @Test
    @DisplayName("Formats immediate push instructions")
    void testImmediatePushInstructions() {
        assertEquals("0: bipush 42", new Instruction(Opcode.BIPUSH, 0, 2, 42).toString());
        assertEquals("3: bipush -5", new Instruction(Opcode.BIPUSH, 3, 2, -5).toString());
        assertEquals("10: sipush 1000", new Instruction(Opcode.SIPUSH, 10, 3, 1000).toString());
    }

    @Test
    @DisplayName("Formats local variable load and store instructions")
    void testLocalVariableInstructions() {
        assertEquals("0: iload 3", new Instruction(Opcode.ILOAD, 0, 2, 3).toString());
        assertEquals("2: istore 2", new Instruction(Opcode.ISTORE, 2, 2, 2).toString());
        assertEquals("4: aload 1", new Instruction(Opcode.ALOAD, 4, 2, 1).toString());
        assertEquals("6: astore 0", new Instruction(Opcode.ASTORE, 6, 2, 0).toString());
    }

    @Test
    @DisplayName("Formats iinc instruction with local index and increment")
    void testIincInstruction() {
        assertEquals("8: iinc 1 by 2", new Instruction(Opcode.IINC, 8, 3, 1, 2).toString());
        assertEquals("12: iinc 2 by -1", new Instruction(Opcode.IINC, 12, 3, 2, -1).toString());
    }

    @Test
    @DisplayName("Formats constant pool reference instructions")
    void testConstantPoolInstructions() {
        assertEquals("0: ldc #5", new Instruction(Opcode.LDC, 0, 2, 5).toString());
        assertEquals("2: ldc_w #300", new Instruction(Opcode.LDC_W, 2, 3, 300).toString());
        assertEquals("5: getstatic #10", new Instruction(Opcode.GETSTATIC, 5, 3, 10).toString());
        assertEquals("8: putstatic #11", new Instruction(Opcode.PUTSTATIC, 8, 3, 11).toString());
        assertEquals("11: getfield #12", new Instruction(Opcode.GETFIELD, 11, 3, 12).toString());
        assertEquals("14: putfield #13", new Instruction(Opcode.PUTFIELD, 14, 3, 13).toString());
        assertEquals("17: invokevirtual #20", new Instruction(Opcode.INVOKEVIRTUAL, 17, 3, 20).toString());
        assertEquals("20: invokespecial #21", new Instruction(Opcode.INVOKESPECIAL, 20, 3, 21).toString());
        assertEquals("23: invokestatic #22", new Instruction(Opcode.INVOKESTATIC, 23, 3, 22).toString());
        assertEquals("26: new #4", new Instruction(Opcode.NEW, 26, 3, 4).toString());
        assertEquals("29: anewarray #6", new Instruction(Opcode.ANEWARRAY, 29, 3, 6).toString());
    }

    @Test
    @DisplayName("Formats branch instructions with offset and computed absolute target")
    void testBranchInstructions() {
        assertEquals("5: ifeq +10 -> 15", new Instruction(Opcode.IFEQ, 5, 3, 10).toString());
        assertEquals("10: goto -4 -> 6", new Instruction(Opcode.GOTO, 10, 3, -4).toString());
        assertEquals("15: if_icmpeq +7 -> 22", new Instruction(Opcode.IF_ICMPEQ, 15, 3, 7).toString());
        assertEquals("20: if_acmpeq +8 -> 28", new Instruction(Opcode.IF_ACMPEQ, 20, 3, 8).toString());
        assertEquals("25: ifnull +5 -> 30", new Instruction(Opcode.IFNULL, 25, 3, 5).toString());
        assertEquals("30: ifnonnull -10 -> 20", new Instruction(Opcode.IFNONNULL, 30, 3, -10).toString());
    }

    @Test
    @DisplayName("Formats array allocation instructions")
    void testArrayAllocationInstructions() {
        assertEquals("0: newarray atype=10", new Instruction(Opcode.NEWARRAY, 0, 2, 10).toString());
        assertEquals("4: multianewarray #7 dim=3", new Instruction(Opcode.MULTIANEWARRAY, 4, 4, 7, 3).toString());
    }
}
