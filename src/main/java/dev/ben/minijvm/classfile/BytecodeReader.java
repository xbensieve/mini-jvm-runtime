package dev.ben.minijvm.classfile;

import dev.ben.minijvm.exception.ClassFormatException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A safe, bounded binary reader for JVM class-file structures.
 * Enforces strict boundary checks on all operations to prevent buffer overruns
 * and detect truncated input.
 */
public final class BytecodeReader {
    private final byte[] data;
    private final int limit;
    private int position;

    public BytecodeReader(byte[] data) {
        this(data, 0, data.length);
    }

    public BytecodeReader(byte[] data, int offset, int length) {
        if (data == null) {
            throw new ClassFormatException("Byte array cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new ClassFormatException(
                    String.format("Invalid reader bounds: offset=%d, length=%d, total=%d", offset, length, data.length)
            );
        }
        this.data = data;
        this.position = offset;
        this.limit = offset + length;
    }

    public int position() {
        return position;
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return limit - position;
    }

    public boolean hasRemaining() {
        return remaining() > 0;
    }

    private void ensureRemaining(int bytesNeeded) {
        if (bytesNeeded < 0) {
            throw new ClassFormatException("Negative byte count requested: " + bytesNeeded);
        }
        if (remaining() < bytesNeeded) {
            throw new ClassFormatException(
                    String.format("Unexpected end of class file: needed %d bytes, only %d remaining at position %d",
                            bytesNeeded, remaining(), position)
            );
        }
    }

    /**
     * Reads an unsigned 8-bit integer (0..255).
     */
    public int readU1() {
        ensureRemaining(1);
        return data[position++] & 0xFF;
    }

    /**
     * Reads a signed 8-bit integer (-128..127).
     */
    public int readS1() {
        ensureRemaining(1);
        return data[position++];
    }

    /**
     * Reads an unsigned 16-bit integer (0..65535) in big-endian order.
     */
    public int readU2() {
        ensureRemaining(2);
        int b1 = data[position++] & 0xFF;
        int b2 = data[position++] & 0xFF;
        return (b1 << 8) | b2;
    }

    /**
     * Reads a signed 16-bit integer (-32768..32767) in big-endian order.
     */
    public int readS2() {
        ensureRemaining(2);
        int b1 = data[position++] & 0xFF;
        int b2 = data[position++] & 0xFF;
        return (short) ((b1 << 8) | b2);
    }

    /**
     * Reads an unsigned 32-bit integer (0..4294967295) in big-endian order as a long.
     */
    public long readU4() {
        ensureRemaining(4);
        long b1 = data[position++] & 0xFFL;
        long b2 = data[position++] & 0xFFL;
        long b3 = data[position++] & 0xFFL;
        long b4 = data[position++] & 0xFFL;
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    /**
     * Reads a signed 32-bit integer in big-endian order.
     */
    public int readS4() {
        ensureRemaining(4);
        int b1 = data[position++] & 0xFF;
        int b2 = data[position++] & 0xFF;
        int b3 = data[position++] & 0xFF;
        int b4 = data[position++] & 0xFF;
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    /**
     * Reads a signed 64-bit integer in big-endian order.
     */
    public long readS8() {
        ensureRemaining(8);
        long high = ((long) readS4()) & 0xFFFFFFFFL;
        long low = ((long) readS4()) & 0xFFFFFFFFL;
        return (high << 32) | low;
    }

    /**
     * Reads exactly length bytes into a new byte array.
     */
    public byte[] readBytes(int length) {
        ensureRemaining(length);
        byte[] result = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return result;
    }

    /**
     * Skips n bytes forward.
     */
    public void skip(int n) {
        ensureRemaining(n);
        position += n;
    }

    /**
     * Returns a new BytecodeReader bounded to the next length bytes.
     * Advances this reader's position by length.
     */
    public BytecodeReader slice(int length) {
        ensureRemaining(length);
        BytecodeReader subReader = new BytecodeReader(data, position, length);
        position += length;
        return subReader;
    }

    /**
     * Decodes length bytes as JVM modified UTF-8 per JVMS 4.4.7.
     */
    public String readModifiedUtf8(int length) {
        ensureRemaining(length);
        int end = position + length;
        char[] charArr = new char[length];
        int charCount = 0;

        while (position < end) {
            int b1 = data[position++] & 0xFF;
            if ((b1 & 0x80) == 0) {
                // 1-byte format: 0xxxxxxx (1..127), note: null byte \0 is encoded as 2 bytes 0xC0 0x80
                if (b1 == 0) {
                    throw new ClassFormatException("Illegal null byte in modified UTF-8 at position " + (position - 1));
                }
                charArr[charCount++] = (char) b1;
            } else if ((b1 >> 5) == 6) {
                // 2-byte format: 110xxxxx 10xxxxxx
                if (position >= end) {
                    throw new ClassFormatException("Truncated 2-byte UTF-8 sequence at position " + position);
                }
                int b2 = data[position++] & 0xFF;
                if ((b2 & 0xC0) != 0x80) {
                    throw new ClassFormatException("Malformed second byte in 2-byte UTF-8 sequence: 0x" + Integer.toHexString(b2));
                }
                charArr[charCount++] = (char) (((b1 & 0x1F) << 6) | (b2 & 0x3F));
            } else if ((b1 >> 4) == 14) {
                // 3-byte format: 1110xxxx 10xxxxxx 10xxxxxx
                if (position + 1 >= end) {
                    throw new ClassFormatException("Truncated 3-byte UTF-8 sequence at position " + position);
                }
                int b2 = data[position++] & 0xFF;
                int b3 = data[position++] & 0xFF;
                if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                    throw new ClassFormatException("Malformed bytes in 3-byte UTF-8 sequence: 0x"
                            + Integer.toHexString(b2) + " 0x" + Integer.toHexString(b3));
                }
                charArr[charCount++] = (char) (((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
            } else {
                throw new ClassFormatException("Invalid UTF-8 leading byte: 0x" + Integer.toHexString(b1));
            }
        }

        return new String(charArr, 0, charCount);
    }
}
