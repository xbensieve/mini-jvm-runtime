package dev.ben.minijvm.classfile;

import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ClassFileReaderTest {

    @Test
    @DisplayName("Throws NullPointerException when given null byte array")
    void testNullBytes() {
        assertThrows(NullPointerException.class, () -> ClassFileReader.read((byte[]) null));
    }

    @Test
    @DisplayName("Throws ClassFormatException on invalid magic number")
    void testInvalidMagic() {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03, 0x00, 0x00, 0x00, 65};
        ClassFormatException ex = assertThrows(ClassFormatException.class, () -> ClassFileReader.read(bytes));
        assertTrue(ex.getMessage().contains("Invalid class file magic"));
    }

    @Test
    @DisplayName("Throws ClassFormatException on truncated header")
    void testTruncatedHeader() {
        byte[] shortBytes = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        assertThrows(ClassFormatException.class, () -> ClassFileReader.read(shortBytes));
    }

    @Test
    @DisplayName("Throws UnsupportedFeatureException on unsupported major version")
    void testUnsupportedMajorVersion() throws IOException {
        // Version 44 (below min 45)
        byte[] v44 = buildMinimalClassBytes(0, 44);
        UnsupportedFeatureException ex1 = assertThrows(UnsupportedFeatureException.class, () -> ClassFileReader.read(v44));
        assertTrue(ex1.getMessage().contains("Unsupported class file version"));

        // Version 66 (above max 65)
        byte[] v66 = buildMinimalClassBytes(0, 66);
        UnsupportedFeatureException ex2 = assertThrows(UnsupportedFeatureException.class, () -> ClassFileReader.read(v66));
        assertTrue(ex2.getMessage().contains("Unsupported class file version"));
    }

    @Test
    @DisplayName("Throws UnsupportedFeatureException on unsupported constant pool tags (e.g., InvokeDynamic)")
    void testUnsupportedCpTag() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt((int) ClassFileReader.MAGIC);
        dos.writeShort(0); // minor
        dos.writeShort(65); // major
        dos.writeShort(2); // cp_count = 2 (1 entry)
        dos.writeByte(18); // tag 18 = CONSTANT_InvokeDynamic (unsupported)
        dos.writeShort(0);
        dos.writeShort(0);

        byte[] bytes = baos.toByteArray();
        assertThrows(UnsupportedFeatureException.class, () -> ClassFileReader.read(bytes));
    }

    @Test
    @DisplayName("Throws ClassFormatException on unknown constant pool tag")
    void testUnknownCpTag() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt((int) ClassFileReader.MAGIC);
        dos.writeShort(0);
        dos.writeShort(65);
        dos.writeShort(2);
        dos.writeByte(99); // invalid tag 99

        byte[] bytes = baos.toByteArray();
        assertThrows(ClassFormatException.class, () -> ClassFileReader.read(bytes));
    }

    @Test
    @DisplayName("Throws ClassFormatException when Long constant overflows constant pool bounds")
    void testLongOverflowsCp() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt((int) ClassFileReader.MAGIC);
        dos.writeShort(0);
        dos.writeShort(65);
        dos.writeShort(2); // cp_count = 2, so only 1 slot, but Long requires 2 slots
        dos.writeByte(5);  // CONSTANT_Long
        dos.writeLong(12345L);

        byte[] bytes = baos.toByteArray();
        assertThrows(ClassFormatException.class, () -> ClassFileReader.read(bytes));
    }

    @Test
    @DisplayName("Throws ClassFormatException when this_class does not point to a ClassEntry")
    void testThisClassNotClassEntry() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt((int) ClassFileReader.MAGIC);
        dos.writeShort(0);
        dos.writeShort(65);
        dos.writeShort(2); // cp_count = 2
        dos.writeByte(1);  // Utf8Entry
        dos.writeUTF("NotAClassEntry");
        dos.writeShort(AccessFlags.ACC_PUBLIC);
        dos.writeShort(1); // this_class points to Utf8Entry, not ClassEntry!
        dos.writeShort(0); // super_class
        dos.writeShort(0); // interfaces
        dos.writeShort(0); // fields
        dos.writeShort(0); // methods
        dos.writeShort(0); // attributes

        byte[] bytes = baos.toByteArray();
        ClassFormatException ex = assertThrows(ClassFormatException.class, () -> ClassFileReader.read(bytes));
        assertTrue(ex.getMessage().contains("Invalid this_class"));
    }

    @Test
    @DisplayName("Throws ClassFormatException on abstract method with Code attribute")
    void testAbstractMethodWithCodeAttribute() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt((int) ClassFileReader.MAGIC);
        dos.writeShort(0);
        dos.writeShort(65);

        // Constant pool:
        // 1: Utf8 "Test"
        // 2: Class -> 1
        // 3: Utf8 "m"
        // 4: Utf8 "()V"
        // 5: Utf8 "Code"
        dos.writeShort(6);
        dos.writeByte(1); dos.writeUTF("Test");
        dos.writeByte(7); dos.writeShort(1);
        dos.writeByte(1); dos.writeUTF("m");
        dos.writeByte(1); dos.writeUTF("()V");
        dos.writeByte(1); dos.writeUTF("Code");

        dos.writeShort(AccessFlags.ACC_PUBLIC);
        dos.writeShort(2); // this_class = Test
        dos.writeShort(0); // super_class
        dos.writeShort(0); // interfaces
        dos.writeShort(0); // fields

        // Methods: 1 method, ACC_ABSTRACT, but with Code attribute
        dos.writeShort(1);
        dos.writeShort(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_ABSTRACT);
        dos.writeShort(3); // name = m
        dos.writeShort(4); // desc = ()V
        dos.writeShort(1); // attributes_count = 1

        // Code attribute
        dos.writeShort(5); // attr_name = "Code"
        dos.writeInt(12);  // length = 12
        dos.writeShort(1); // max_stack
        dos.writeShort(1); // max_locals
        dos.writeInt(0);   // code_length = 0
        dos.writeShort(0); // exception_table_length = 0
        dos.writeShort(0); // attributes_count = 0

        dos.writeShort(0); // class attributes

        byte[] bytes = baos.toByteArray();
        ClassFormatException ex = assertThrows(ClassFormatException.class, () -> ClassFileReader.read(bytes));
        assertTrue(ex.getMessage().contains("Abstract or native method cannot have a Code attribute"));
    }

    @Test
    @DisplayName("Throws ClassFormatException on trailing bytes at end of class file")
    void testTrailingBytes() throws IOException {
        byte[] validBytes = buildMinimalClassBytes(0, 65);
        byte[] withGarbage = new byte[validBytes.length + 2];
        System.arraycopy(validBytes, 0, withGarbage, 0, validBytes.length);
        withGarbage[validBytes.length] = (byte) 0xAA;
        withGarbage[validBytes.length + 1] = (byte) 0xBB;

        ClassFormatException ex = assertThrows(ClassFormatException.class, () -> ClassFileReader.read(withGarbage));
        assertTrue(ex.getMessage().contains("Trailing unread data"));
    }

    private byte[] buildMinimalClassBytes(int minor, int major) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt((int) ClassFileReader.MAGIC);
        dos.writeShort(minor);
        dos.writeShort(major);

        // Constant pool:
        // 1: Utf8 "Minimal"
        // 2: Class -> 1
        dos.writeShort(3);
        dos.writeByte(1); dos.writeUTF("Minimal");
        dos.writeByte(7); dos.writeShort(1);

        dos.writeShort(AccessFlags.ACC_PUBLIC);
        dos.writeShort(2); // this_class
        dos.writeShort(0); // super_class
        dos.writeShort(0); // interfaces
        dos.writeShort(0); // fields
        dos.writeShort(0); // methods
        dos.writeShort(0); // attributes

        return baos.toByteArray();
    }
}
