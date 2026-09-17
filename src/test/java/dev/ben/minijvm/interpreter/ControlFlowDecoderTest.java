package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates decoding of all Phase 04 control flow and return instructions:
 * signed 16-bit branch offsets, iinc operands, truncated bytecodes, and lengths.
 */
class ControlFlowDecoderTest {

    private final BytecodeDecoder decoder = new BytecodeDecoder();

    @Test
    @DisplayName("Opcode registry contains 85 concrete opcodes after Phase 07")
    void testOpcodeCount() {
        assertEquals(85, Opcode.values().length, "Opcode registry must contain exactly 85 opcodes (27 Phase 03 + 16 Phase 04 + 5 Phase 05 + 32 Phase 06 + 5 Phase 07)");
    }

    @ParameterizedTest(name = "Decode branch opcode {0} with signed positive offset")
    @EnumSource(value = Opcode.class, names = {
            "IFEQ", "IFNE", "IFLT", "IFGE", "IFGT", "IFLE",
            "IF_ICMPEQ", "IF_ICMPNE", "IF_ICMPLT", "IF_ICMPGE", "IF_ICMPGT", "IF_ICMPLE",
            "IF_ACMPEQ", "IF_ACMPNE", "IFNULL", "IFNONNULL",
            "GOTO"
    })
    @DisplayName("Branch opcodes decode 16-bit big-endian positive offsets")
    void testBranchOpcodePositiveOffset(Opcode opcode) {
        // offset = +1234 (0x04D2)
        byte[] code = new byte[]{(byte) opcode.code(), 0x04, (byte) 0xD2};
        Instruction ins = decoder.decode(code, 0);

        assertEquals(opcode, ins.opcode());
        assertEquals(0, ins.pc());
        assertEquals(3, ins.length());
        assertEquals(1234, ins.branchOffset());
        assertEquals(1234, ins.operand());
        assertTrue(ins.opcode().isBranch());
    }

    @ParameterizedTest(name = "Decode branch opcode {0} with signed negative offset")
    @EnumSource(value = Opcode.class, names = {
            "IFEQ", "IFNE", "IFLT", "IFGE", "IFGT", "IFLE",
            "IF_ICMPEQ", "IF_ICMPNE", "IF_ICMPLT", "IF_ICMPGE", "IF_ICMPGT", "IF_ICMPLE",
            "IF_ACMPEQ", "IF_ACMPNE", "IFNULL", "IFNONNULL",
            "GOTO"
    })
    @DisplayName("Branch opcodes decode 16-bit big-endian negative offsets")
    void testBranchOpcodeNegativeOffset(Opcode opcode) {
        // offset = -10 (0xFFF6)
        byte[] code = new byte[]{(byte) opcode.code(), (byte) 0xFF, (byte) 0xF6};
        Instruction ins = decoder.decode(code, 0);

        assertEquals(opcode, ins.opcode());
        assertEquals(3, ins.length());
        assertEquals(-10, ins.branchOffset());
    }

    @Test
    @DisplayName("Branch offset boundary decoding: 0, max short (+32767), and min short (-32768)")
    void testBranchOffsetBoundaries() {
        // goto offset 0
        byte[] zeroOffset = new byte[]{(byte) 0xA7, 0x00, 0x00};
        assertEquals(0, decoder.decode(zeroOffset, 0).branchOffset());

        // goto offset +32767 (0x7FFF)
        byte[] maxShort = new byte[]{(byte) 0xA7, 0x7F, (byte) 0xFF};
        assertEquals(32767, decoder.decode(maxShort, 0).branchOffset());

        // goto offset -32768 (0x8000)
        byte[] minShort = new byte[]{(byte) 0xA7, (byte) 0x80, 0x00};
        assertEquals(-32768, decoder.decode(minShort, 0).branchOffset());
    }

    @Test
    @DisplayName("iinc decodes unsigned local index and signed increment constant")
    void testIincDecoding() {
        // iinc 5 by 1
        byte[] code1 = new byte[]{(byte) 0x84, 0x05, 0x01};
        Instruction ins1 = decoder.decode(code1, 0);
        assertEquals(Opcode.IINC, ins1.opcode());
        assertEquals(3, ins1.length());
        assertEquals(5, ins1.localIndex());
        assertEquals(1, ins1.incrementConst());

        // iinc 255 by -128
        byte[] code2 = new byte[]{(byte) 0x84, (byte) 0xFF, (byte) 0x80};
        Instruction ins2 = decoder.decode(code2, 0);
        assertEquals(255, ins2.localIndex());
        assertEquals(-128, ins2.incrementConst());

        // iinc 0 by 127
        byte[] code3 = new byte[]{(byte) 0x84, 0x00, 0x7F};
        Instruction ins3 = decoder.decode(code3, 0);
        assertEquals(0, ins3.localIndex());
        assertEquals(127, ins3.incrementConst());

        // iinc 10 by -1
        byte[] code4 = new byte[]{(byte) 0x84, 0x0A, (byte) 0xFF};
        Instruction ins4 = decoder.decode(code4, 0);
        assertEquals(10, ins4.localIndex());
        assertEquals(-1, ins4.incrementConst());
    }

    @Test
    @DisplayName("return and ireturn decode as 1-byte instructions with 0 operands")
    void testReturnOpcodes() {
        byte[] code = new byte[]{(byte) 0xAC, (byte) 0xB1}; // ireturn, return

        Instruction iret = decoder.decode(code, 0);
        assertEquals(Opcode.IRETURN, iret.opcode());
        assertEquals(1, iret.length());
        assertEquals(0, iret.operand());
        assertTrue(iret.opcode().isReturn());

        Instruction ret = decoder.decode(code, 1);
        assertEquals(Opcode.RETURN, ret.opcode());
        assertEquals(1, ret.length());
        assertEquals(0, ret.operand());
        assertTrue(ret.opcode().isReturn());
    }

    @Test
    @DisplayName("Truncated branch and iinc bytecodes throw ClassFormatException")
    void testTruncatedPhase04Bytecodes() {
        // goto requires 3 bytes, only 1 provided
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xA7}, 0));
        // goto requires 3 bytes, only 2 provided
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xA7, 0x00}, 0));

        // ifeq requires 3 bytes, only 2 provided
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0x99, 0x00}, 0));

        // if_icmpeq requires 3 bytes, only 1 provided
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0x9F}, 0));

        // iinc requires 3 bytes, only 2 provided
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0x84, 0x01}, 0));
    }
}
