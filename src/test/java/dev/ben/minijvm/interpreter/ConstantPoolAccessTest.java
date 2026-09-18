package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.AccessFlags;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstantPoolAccessTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
    }

    private Frame createFrame(byte[] code, ConstantPool cp) {
        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 1, 2, List.of(), codeAttr);
        return new Frame(method, cp, 4, 4);
    }

    @Test
    @DisplayName("ldc loads integer constant successfully")
    void testLdcInteger() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));          // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));           // #2
        entries.add(new ConstantPoolEntry.IntegerEntry(12345));        // #3
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{
                (byte) 0x12, 0x03, // 0: ldc #3
                (byte) 0xB1        // 2: return
        };
        Frame frame = createFrame(code, cp);

        interpreter.step(frame);
        assertEquals(2, frame.pc());
        assertEquals(0, frame.lastInstructionPc());
        assertEquals(1, frame.operandStack().slots());
        assertEquals(12345, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("ldc loads string literal reference handle")
    void testLdcString() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));          // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));           // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("Hello World"));   // #3
        entries.add(new ConstantPoolEntry.StringEntry(3));             // #4 String -> #3
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{
                (byte) 0x12, 0x04, // 0: ldc #4
                (byte) 0xB1        // 2: return
        };
        Frame frame = createFrame(code, cp);

        interpreter.step(frame);
        assertEquals(2, frame.pc());
        assertEquals(1, frame.operandStack().slots());
        Value val = frame.operandStack().pop();
        assertTrue(val.isReference());
        assertInstanceOf(dev.ben.minijvm.runtime.ObjectReference.class, val);
        assertEquals(4, ((dev.ben.minijvm.runtime.ObjectReference) val).handle());
    }

    @Test
    @DisplayName("ldc_w loads constant with 16-bit constant pool index > 255")
    void testLdcWHighIndex() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));          // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));           // #2

        // Pad constant pool up to 300 entries
        for (int i = 3; i < 300; i++) {
            entries.add(new ConstantPoolEntry.IntegerEntry(i));
        }
        entries.add(new ConstantPoolEntry.IntegerEntry(99999));        // #300
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{
                (byte) 0x13, 0x01, 0x2C, // 0: ldc_w #300 (0x012C = 300)
                (byte) 0xB1              // 3: return
        };
        Frame frame = createFrame(code, cp);

        interpreter.step(frame);
        assertEquals(3, frame.pc());
        assertEquals(0, frame.lastInstructionPc());
        assertEquals(1, frame.operandStack().slots());
        assertEquals(99999, frame.operandStack().popInt());
    }

    @Test
    @DisplayName("ldc with invalid constant pool index throws ClassFormatException")
    void testLdcInvalidIndex() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        ConstantPool cp = new ConstantPool(entries);

        // ldc pointing to index 99 (pool size 3)
        byte[] code = new byte[]{(byte) 0x12, 99};
        Frame frame = createFrame(code, cp);

        assertThrows(ClassFormatException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("ldc with index 0 throws ClassFormatException")
    void testLdcIndexZero() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{(byte) 0x12, 0x00};
        Frame frame = createFrame(code, cp);

        assertThrows(ClassFormatException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("ldc referencing 8-byte long constant throws ClassFormatException")
    void testLdc8ByteLongThrowsClassFormatException() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        entries.add(new ConstantPoolEntry.LongEntry(123456789L)); // #3
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 4"));
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{(byte) 0x12, 0x03}; // ldc #3
        Frame frame = createFrame(code, cp);

        assertThrows(ClassFormatException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("ldc referencing 8-byte double constant throws ClassFormatException")
    void testLdc8ByteDoubleThrowsClassFormatException() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        entries.add(new ConstantPoolEntry.DoubleEntry(3.14159)); // #3
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 4"));
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{(byte) 0x12, 0x03}; // ldc #3
        Frame frame = createFrame(code, cp);

        assertThrows(ClassFormatException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("ldc referencing float throws UnsupportedFeatureException")
    void testLdcFloatThrowsUnsupported() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        entries.add(new ConstantPoolEntry.FloatEntry(1.5f)); // #3
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{(byte) 0x12, 0x03}; // ldc #3
        Frame frame = createFrame(code, cp);

        assertThrows(UnsupportedFeatureException.class, () -> interpreter.step(frame));
    }

    @Test
    @DisplayName("ldc referencing wrong entry type (e.g. MethodRef) throws ClassFormatException")
    void testLdcWrongEntryType() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 2)); // #3
        ConstantPool cp = new ConstantPool(entries);

        byte[] code = new byte[]{(byte) 0x12, 0x03}; // ldc #3
        Frame frame = createFrame(code, cp);

        assertThrows(ClassFormatException.class, () -> interpreter.step(frame));
    }
}
