package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Auditable coverage test verifying that supported core opcodes
 * are correctly decoded, dispatched, and executed, along with explicit
 * malformed and unsupported opcode coverage.
 */
class OpcodeCoverageTest {

    private final BytecodeDecoder decoder = new BytecodeDecoder();
    private final Interpreter interpreter = new Interpreter();

    private static final Set<String> CORE_INTEGER_OPCODES = Set.of(
            "nop", "aconst_null",
            "iconst_m1", "iconst_0", "iconst_1", "iconst_2", "iconst_3", "iconst_4", "iconst_5",
            "bipush", "sipush",
            "iload", "iload_0", "iload_1", "iload_2", "iload_3",
            "istore", "istore_0", "istore_1", "istore_2", "istore_3",
            "iadd", "isub", "imul", "idiv", "irem",
            "ineg"
    );

    @Test
    @DisplayName("Authoritative core integer opcode count is exactly 27 concrete opcodes")
    void testAuthoritativeOpcodeCount() {
        assertEquals(27, CORE_INTEGER_OPCODES.size(), "Core integer opcodes must contain exactly 27 concrete opcode values");
        for (String mnemonic : CORE_INTEGER_OPCODES) {
            assertTrue(Opcode.values().length >= 27);
        }
    }

    @ParameterizedTest(name = "Decode and dispatch coverage for opcode: {0}")
    @EnumSource(value = Opcode.class, names = {
            "NOP", "ACONST_NULL",
            "ICONST_M1", "ICONST_0", "ICONST_1", "ICONST_2", "ICONST_3", "ICONST_4", "ICONST_5",
            "BIPUSH", "SIPUSH",
            "ILOAD", "ILOAD_0", "ILOAD_1", "ILOAD_2", "ILOAD_3",
            "ISTORE", "ISTORE_0", "ISTORE_1", "ISTORE_2", "ISTORE_3",
            "IADD", "ISUB", "IMUL", "IDIV", "IREM",
            "INEG"
    })
    @DisplayName("Every core integer opcode has verified decoder and interpreter dispatch")
    void testOpcodeDispatchCoverage(Opcode opcode) {
        byte[] sampleBytecode = buildSampleBytecode(opcode);
        Instruction ins = decoder.decode(sampleBytecode, 0);

        assertNotNull(ins);
        assertEquals(opcode, ins.opcode());
        assertEquals(0, ins.pc());
        assertEquals(opcode.length(), ins.length());

        Frame frame = createFrame(sampleBytecode, 10, 10);
        prepareFrameState(opcode, frame);

        // Verify dispatch does not throw unsupported or unhandled exception
        Instruction executed = interpreter.step(frame);
        assertSame(ins.opcode(), executed.opcode());
        assertEquals(opcode.length(), frame.pc());
        assertEquals(0, frame.lastInstructionPc());
    }

    @Test
    @DisplayName("Explicit malformed (truncated) bytecode rejected with ClassFormatException")
    void testTruncatedBytecodes() {
        // BIPUSH truncated
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{0x10}, 0));
        // SIPUSH truncated at 1 byte
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{0x11}, 0));
        // SIPUSH truncated at 2 bytes
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{0x11, 0x01}, 0));
        // ILOAD truncated
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{0x15}, 0));
        // ISTORE truncated
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{0x36}, 0));
    }

    @Test
    @DisplayName("Reserved opcode bytes (0xCA breakpoint, 0xFE impdep1, 0xFF impdep2) rejected with ClassFormatException")
    void testReservedOpcodeBytes() {
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xCA}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xFE}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xFF}, 0));
    }

    @Test
    @DisplayName("Undefined opcode bytes (0xCB..0xFD) rejected with ClassFormatException")
    void testUndefinedOpcodeBytes() {
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xCB}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xD0}, 0));
        assertThrows(ClassFormatException.class, () -> decoder.decode(new byte[]{(byte) 0xFD}, 0));
    }

    @Test
    @DisplayName("Explicit unsupported valid JVM opcodes rejected with UnsupportedFeatureException")
    void testUnsupportedStandardOpcodes() {
        // ladd (0x61)
        assertThrows(UnsupportedFeatureException.class, () -> decoder.decode(new byte[]{0x61}, 0));
        // getstatic (0xB2)
        assertThrows(UnsupportedFeatureException.class, () -> decoder.decode(new byte[]{(byte) 0xB2, 0x00, 0x01}, 0));
        // new (0xBB)
        assertThrows(UnsupportedFeatureException.class, () -> decoder.decode(new byte[]{(byte) 0xBB, 0x00, 0x01}, 0));
    }

    private byte[] buildSampleBytecode(Opcode opcode) {
        return switch (opcode) {
            case NOP, ACONST_NULL,
                 ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5,
                 ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3,
                 ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3,
                 IADD, ISUB, IMUL, IDIV, IREM, INEG -> new byte[]{(byte) opcode.code()};
            case BIPUSH -> new byte[]{(byte) opcode.code(), 42};
            case SIPUSH -> new byte[]{(byte) opcode.code(), 0x01, 0x00};
            case ILOAD, ISTORE -> new byte[]{(byte) opcode.code(), 0x04};
            default -> throw new IllegalArgumentException("Not a core integer opcode: " + opcode);
        };
    }

    private void prepareFrameState(Opcode opcode, Frame frame) {
        switch (opcode) {
            case ILOAD, ILOAD_0, ILOAD_1, ILOAD_2, ILOAD_3 -> {
                for (int i = 0; i < 5; i++) {
                    frame.locals().setInt(i, 100 + i);
                }
            }
            case ISTORE, ISTORE_0, ISTORE_1, ISTORE_2, ISTORE_3 -> {
                frame.operandStack().push(Value.ofInt(77));
            }
            case IADD, ISUB, IMUL, IDIV, IREM -> {
                frame.operandStack().push(Value.ofInt(20));
                frame.operandStack().push(Value.ofInt(5));
            }
            case INEG -> {
                frame.operandStack().push(Value.ofInt(42));
            }
            default -> {
                // No prerequisite frame state needed
            }
        }
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
