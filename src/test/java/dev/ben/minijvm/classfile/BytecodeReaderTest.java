package dev.ben.minijvm.classfile;

import dev.ben.minijvm.exception.ClassFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeReaderTest {

    @Test
    @DisplayName("readU1 and readS1 handle unsigned and signed bytes correctly")
    void testReadU1AndS1() {
        byte[] bytes = new byte[]{(byte) 0x00, (byte) 0x7F, (byte) 0x80, (byte) 0xFF};
        BytecodeReader reader = new BytecodeReader(bytes);

        assertEquals(0x00, reader.readU1());
        assertEquals(127, reader.readU1());
        assertEquals(128, reader.readU1());
        assertEquals(255, reader.readU1());
        assertEquals(0, reader.remaining());

        BytecodeReader signedReader = new BytecodeReader(bytes);
        assertEquals(0, signedReader.readS1());
        assertEquals(127, signedReader.readS1());
        assertEquals(-128, signedReader.readS1());
        assertEquals(-1, signedReader.readS1());
    }

    @Test
    @DisplayName("readU2 and readS2 handle 16-bit big-endian values")
    void testReadU2AndS2() {
        byte[] bytes = new byte[]{
                (byte) 0x00, (byte) 0x2A, // 42
                (byte) 0xFF, (byte) 0xFE  // 65534 unsigned, -2 signed
        };
        BytecodeReader reader = new BytecodeReader(bytes);

        assertEquals(42, reader.readU2());
        assertEquals(65534, reader.readU2());

        BytecodeReader signedReader = new BytecodeReader(bytes);
        assertEquals(42, signedReader.readS2());
        assertEquals(-2, signedReader.readS2());
    }

    @Test
    @DisplayName("readU4 and readS4 handle 32-bit big-endian integers")
    void testReadU4AndS4() {
        byte[] bytes = new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, // 0xCAFEBABE
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE  // -2 or 4294967294L
        };
        BytecodeReader reader = new BytecodeReader(bytes);

        assertEquals(0xCAFEBABEL, reader.readU4());
        assertEquals(4294967294L, reader.readU4());

        BytecodeReader signedReader = new BytecodeReader(bytes);
        assertEquals((int) 0xCAFEBABE, signedReader.readS4());
        assertEquals(-2, signedReader.readS4());
    }

    @Test
    @DisplayName("readS8 handles 64-bit integers")
    void testReadS8() {
        byte[] bytes = new byte[]{
                0, 0, 0, 0, 0, 0, 0, 1,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFE
        };
        BytecodeReader reader = new BytecodeReader(bytes);

        assertEquals(1L, reader.readS8());
        assertEquals(-2L, reader.readS8());
    }

    @Test
    @DisplayName("slice creates a bounded view and advances position")
    void testSlice() {
        byte[] bytes = new byte[]{1, 2, 3, 4, 5, 6};
        BytecodeReader reader = new BytecodeReader(bytes);

        assertEquals(1, reader.readU1());
        BytecodeReader slice = reader.slice(3);

        assertEquals(3, slice.remaining());
        assertEquals(2, slice.readU1());
        assertEquals(3, slice.readU1());
        assertEquals(4, slice.readU1());
        assertEquals(0, slice.remaining());

        assertEquals(2, reader.remaining());
        assertEquals(5, reader.readU1());
        assertEquals(6, reader.readU1());
    }

    @Test
    @DisplayName("ensureRemaining throws ClassFormatException on premature EOF")
    void testPrematureEof() {
        byte[] bytes = new byte[]{1, 2};
        BytecodeReader reader = new BytecodeReader(bytes);

        assertThrows(ClassFormatException.class, reader::readU4);
        assertThrows(ClassFormatException.class, () -> reader.readBytes(5));
        assertThrows(ClassFormatException.class, () -> reader.skip(3));
        assertThrows(ClassFormatException.class, () -> reader.slice(3));
    }

    @Test
    @DisplayName("readModifiedUtf8 decodes standard ASCII and multi-byte UTF-8 correctly")
    void testModifiedUtf8Decoding() {
        // "Hello"
        byte[] hello = "Hello".getBytes();
        BytecodeReader r1 = new BytecodeReader(hello);
        assertEquals("Hello", r1.readModifiedUtf8(hello.length));

        // JVM modified UTF-8 encoding of '\u0000' is 0xC0, 0x80
        byte[] nullCharBytes = new byte[]{(byte) 0xC0, (byte) 0x80};
        BytecodeReader r2 = new BytecodeReader(nullCharBytes);
        assertEquals("\u0000", r2.readModifiedUtf8(nullCharBytes.length));

        // Multi-byte character: Euro sign € is 3 bytes: 0xE2 0x82 0xAC
        byte[] euroBytes = new byte[]{(byte) 0xE2, (byte) 0x82, (byte) 0xAC};
        BytecodeReader r3 = new BytecodeReader(euroBytes);
        assertEquals("€", r3.readModifiedUtf8(euroBytes.length));
    }

    @Test
    @DisplayName("readModifiedUtf8 rejects malformed or truncated UTF-8 sequences")
    void testMalformedUtf8() {
        // Raw null byte is forbidden in modified UTF-8
        byte[] rawNull = new byte[]{0};
        BytecodeReader r1 = new BytecodeReader(rawNull);
        assertThrows(ClassFormatException.class, () -> r1.readModifiedUtf8(1));

        // Incomplete 2-byte sequence
        byte[] truncated2 = new byte[]{(byte) 0xC0};
        BytecodeReader r2 = new BytecodeReader(truncated2);
        assertThrows(ClassFormatException.class, () -> r2.readModifiedUtf8(1));

        // Bad trail byte in 2-byte sequence
        byte[] badTrail2 = new byte[]{(byte) 0xC0, (byte) 0x00};
        BytecodeReader r3 = new BytecodeReader(badTrail2);
        assertThrows(ClassFormatException.class, () -> r3.readModifiedUtf8(2));

        // Invalid leading byte (0xFF)
        byte[] invalidLeading = new byte[]{(byte) 0xFF};
        BytecodeReader r4 = new BytecodeReader(invalidLeading);
        assertThrows(ClassFormatException.class, () -> r4.readModifiedUtf8(1));
    }
}
