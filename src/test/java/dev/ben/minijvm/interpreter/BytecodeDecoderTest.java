package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeDecoderTest {

    private final BytecodeDecoder decoder = new BytecodeDecoder();

    @Test
    @DisplayName("Decode zero-operand Phase 03 opcodes")
    void testZeroOperandOpcodes() {
        byte[] code = new byte[]{
                0x00, // nop
                0x01, // aconst_null
                0x02, // iconst_m1
                0x03, // iconst_0
                0x04, // iconst_1
                0x05, // iconst_2
                0x06, // iconst_3
                0x07, // iconst_4
                0x08, // iconst_5
                0x1A, // iload_0
                0x1B, // iload_1
                0x1C, // iload_2
                0x1D, // iload_3
                0x3B, // istore_0
                0x3C, // istore_1
                0x3D, // istore_2
                0x3E, // istore_3
                0x60, // iadd
                0x64, // isub
                0x68, // imul
                0x6C, // idiv
                0x70, // irem
                0x74  // ineg
        };

        assertEquals(Opcode.NOP, decoder.decode(code, 0).opcode());
        assertEquals(Opcode.ACONST_NULL, decoder.decode(code, 1).opcode());
        assertEquals(-1, decoder.decode(code, 2).operand());
        assertEquals(0, decoder.decode(code, 3).operand());
        assertEquals(1, decoder.decode(code, 4).operand());
        assertEquals(2, decoder.decode(code, 5).operand());
        assertEquals(3, decoder.decode(code, 6).operand());
        assertEquals(4, decoder.decode(code, 7).operand());
        assertEquals(5, decoder.decode(code, 8).operand());
        assertEquals(0, decoder.decode(code, 9).operand()); // iload_0
        assertEquals(1, decoder.decode(code, 10).operand()); // iload_1
        assertEquals(2, decoder.decode(code, 11).operand()); // iload_2
        assertEquals(3, decoder.decode(code, 12).operand()); // iload_3
        assertEquals(0, decoder.decode(code, 13).operand()); // istore_0
        assertEquals(1, decoder.decode(code, 14).operand()); // istore_1
        assertEquals(2, decoder.decode(code, 15).operand()); // istore_2
        assertEquals(3, decoder.decode(code, 16).operand()); // istore_3
        assertEquals(Opcode.IADD, decoder.decode(code, 17).opcode());
        assertEquals(Opcode.ISUB, decoder.decode(code, 18).opcode());
        assertEquals(Opcode.IMUL, decoder.decode(code, 19).opcode());
        assertEquals(Opcode.IDIV, decoder.decode(code, 20).opcode());
        assertEquals(Opcode.IREM, decoder.decode(code, 21).opcode());
        assertEquals(Opcode.INEG, decoder.decode(code, 22).opcode());
    }

    @Test
    @DisplayName("Decode bipush with signed 8-bit immediate values")
    void testBipush() {
        byte[] code = new byte[]{
                0x10, (byte) 127,  // bipush 127
                0x10, (byte) -128, // bipush -128
                0x10, (byte) -1    // bipush -1
        };

        Instruction ins1 = decoder.decode(code, 0);
        assertEquals(Opcode.BIPUSH, ins1.opcode());
        assertEquals(0, ins1.pc());
        assertEquals(2, ins1.length());
        assertEquals(127, ins1.operand());

        Instruction ins2 = decoder.decode(code, 2);
        assertEquals(-128, ins2.operand());

        Instruction ins3 = decoder.decode(code, 4);
        assertEquals(-1, ins3.operand());
    }

    @Test
    @DisplayName("Decode sipush with signed 16-bit big-endian immediate values")
    void testSipush() {
        byte[] code = new byte[]{
                0x11, 0x7F, (byte) 0xFF, // sipush 32767
                0x11, (byte) 0x80, 0x00, // sipush -32768
                0x11, (byte) 0xFF, (byte) 0xFE  // sipush -2
        };

        Instruction ins1 = decoder.decode(code, 0);
        assertEquals(Opcode.SIPUSH, ins1.opcode());
        assertEquals(0, ins1.pc());
        assertEquals(3, ins1.length());
        assertEquals(32767, ins1.operand());

        Instruction ins2 = decoder.decode(code, 3);
        assertEquals(-32768, ins2.operand());

        Instruction ins3 = decoder.decode(code, 6);
        assertEquals(-2, ins3.operand());
    }

    @Test
    @DisplayName("Decode iload and istore with 1-byte local index")
    void testIloadAndIstore() {
        byte[] code = new byte[]{
                0x15, 0x05, // iload 5
                0x36, 0x0A  // istore 10
        };

        Instruction iload = decoder.decode(code, 0);
        assertEquals(Opcode.ILOAD, iload.opcode());
        assertEquals(5, iload.operand());
        assertEquals(2, iload.length());

        Instruction istore = decoder.decode(code, 2);
        assertEquals(Opcode.ISTORE, istore.opcode());
        assertEquals(10, istore.operand());
        assertEquals(2, istore.length());
    }

    @Test
    @DisplayName("Truncated instruction operands throw ClassFormatException")
    void testTruncatedInstructions() {
        // bipush requires 2 bytes, but code has only 1
        byte[] truncatedBipush = new byte[]{0x10};
        assertThrows(ClassFormatException.class, () -> decoder.decode(truncatedBipush, 0));

        // sipush requires 3 bytes, but code has only 2
        byte[] truncatedSipush = new byte[]{0x11, 0x01};
        assertThrows(ClassFormatException.class, () -> decoder.decode(truncatedSipush, 0));

        // iload requires 2 bytes, but code has only 1
        byte[] truncatedIload = new byte[]{0x15};
        assertThrows(ClassFormatException.class, () -> decoder.decode(truncatedIload, 0));
    }

    @Test
    @DisplayName("Unsupported standard JVM opcodes throw UnsupportedFeatureException")
    void testUnsupportedOpcodes() {
        byte[] code = new byte[]{
                0x61, // ladd (unsupported)
                (byte) 0xB2  // getstatic (unsupported)
        };

        assertThrows(UnsupportedFeatureException.class, () -> decoder.decode(code, 0));
        assertThrows(UnsupportedFeatureException.class, () -> decoder.decode(code, 1));
    }

    @Test
    @DisplayName("Reserved opcode bytes (0xCA breakpoint, 0xFE impdep1, 0xFF impdep2) throw ClassFormatException")
    void testReservedOpcodeBytes() {
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xCA}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xFE}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xFF}, 0));
    }

    @Test
    @DisplayName("Undefined opcode bytes (0xCB..0xFD) throw ClassFormatException")
    void testUndefinedOpcodeBytes() {
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xCB}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xFD}, 0));
    }

    @Test
    @DisplayName("Bounds checking on PC and null code")
    void testBoundsChecking() {
        byte[] code = new byte[]{0x00};
        assertThrows(ClassFormatException.class, () -> decoder.decode(code, -1));
        assertThrows(ClassFormatException.class, () -> decoder.decode(code, 1));
        assertThrows(ClassFormatException.class, () -> decoder.decode(null, 0));
    }
}
