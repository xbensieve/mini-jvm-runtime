package dev.ben.minijvm.classfile;

import dev.ben.minijvm.classfile.ConstantPoolEntry.*;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses binary JVM class files into immutable ClassFile models.
 * Strictly checks bounds, validates headers and constant pools, and safely handles attributes.
 */
public final class ClassFileReader {

    public static final long MAGIC = 0xCAFEBABEL;
    public static final int MIN_SUPPORTED_VERSION = 45; // Java 1.1
    public static final int MAX_SUPPORTED_VERSION = 65; // Java 21

    private ClassFileReader() {}

    public static ClassFile read(byte[] bytes) {
        Objects.requireNonNull(bytes, "Class file bytes cannot be null");
        BytecodeReader reader = new BytecodeReader(bytes);
        return parse(reader);
    }

    public static ClassFile read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "InputStream cannot be null");
        byte[] bytes = in.readAllBytes();
        return read(bytes);
    }

    public static ClassFile read(Path path) throws IOException {
        Objects.requireNonNull(path, "Path cannot be null");
        byte[] bytes = Files.readAllBytes(path);
        return read(bytes);
    }

    private static ClassFile parse(BytecodeReader reader) {
        // 1. Magic
        long magic = reader.readU4();
        if (magic != MAGIC) {
            throw new ClassFormatException(
                    String.format("Invalid class file magic: 0x%08X (expected 0x%08X)", magic, MAGIC)
            );
        }

        // 2. Version
        int minorVersion = reader.readU2();
        int majorVersion = reader.readU2();
        if (majorVersion < MIN_SUPPORTED_VERSION || majorVersion > MAX_SUPPORTED_VERSION) {
            throw new UnsupportedFeatureException(
                    String.format("Unsupported class file version %d.%d (supported major versions %d..%d)",
                            majorVersion, minorVersion, MIN_SUPPORTED_VERSION, MAX_SUPPORTED_VERSION)
            );
        }

        // 3. Constant Pool
        int cpCount = reader.readU2();
        if (cpCount < 1) {
            throw new ClassFormatException("Invalid constant_pool_count: " + cpCount);
        }
        List<ConstantPoolEntry> cpEntries = new ArrayList<>(cpCount);
        cpEntries.add(new UnusableEntry("Slot 0 is reserved"));

        for (int i = 1; i < cpCount; i++) {
            int tag = reader.readU1();
            switch (tag) {
                case 1 -> { // Utf8
                    int length = reader.readU2();
                    String str = reader.readModifiedUtf8(length);
                    cpEntries.add(new Utf8Entry(str));
                }
                case 3 -> { // Integer
                    int val = reader.readS4();
                    cpEntries.add(new IntegerEntry(val));
                }
                case 4 -> { // Float
                    int bits = reader.readS4();
                    cpEntries.add(new FloatEntry(Float.intBitsToFloat(bits)));
                }
                case 5 -> { // Long (2 slots)
                    long val = reader.readS8();
                    cpEntries.add(new LongEntry(val));
                    i++;
                    if (i >= cpCount) {
                        throw new ClassFormatException("Long entry at index " + (i - 1) + " exceeds constant pool bounds");
                    }
                    cpEntries.add(new UnusableEntry("Second slot of Long constant at index " + (i - 1)));
                }
                case 6 -> { // Double (2 slots)
                    long bits = reader.readS8();
                    cpEntries.add(new DoubleEntry(Double.longBitsToDouble(bits)));
                    i++;
                    if (i >= cpCount) {
                        throw new ClassFormatException("Double entry at index " + (i - 1) + " exceeds constant pool bounds");
                    }
                    cpEntries.add(new UnusableEntry("Second slot of Double constant at index " + (i - 1)));
                }
                case 7 -> { // Class
                    int nameIndex = reader.readU2();
                    cpEntries.add(new ClassEntry(nameIndex));
                }
                case 8 -> { // String
                    int stringIndex = reader.readU2();
                    cpEntries.add(new StringEntry(stringIndex));
                }
                case 9 -> { // Fieldref
                    int classIndex = reader.readU2();
                    int nameAndTypeIndex = reader.readU2();
                    cpEntries.add(new FieldRefEntry(classIndex, nameAndTypeIndex));
                }
                case 10 -> { // Methodref
                    int classIndex = reader.readU2();
                    int nameAndTypeIndex = reader.readU2();
                    cpEntries.add(new MethodRefEntry(classIndex, nameAndTypeIndex));
                }
                case 11 -> { // InterfaceMethodref
                    int classIndex = reader.readU2();
                    int nameAndTypeIndex = reader.readU2();
                    cpEntries.add(new InterfaceMethodRefEntry(classIndex, nameAndTypeIndex));
                }
                case 12 -> { // NameAndType
                    int nameIndex = reader.readU2();
                    int descriptorIndex = reader.readU2();
                    cpEntries.add(new NameAndTypeEntry(nameIndex, descriptorIndex));
                }
                case 15, 16, 17, 18, 19, 20 ->
                        throw new UnsupportedFeatureException("Unsupported constant pool tag: " + tag);
                default ->
                        throw new ClassFormatException("Unknown constant pool tag: " + tag + " at index " + i);
            }
        }

        ConstantPool constantPool = new ConstantPool(cpEntries);

        // 4. Access flags, this_class, super_class
        int accessFlags = reader.readU2();
        int thisClassIndex = reader.readU2();
        validateClassIndex(constantPool, thisClassIndex, "this_class");

        int superClassIndex = reader.readU2();
        if (superClassIndex != 0) {
            validateClassIndex(constantPool, superClassIndex, "super_class");
        }

        // 5. Interfaces
        int interfacesCount = reader.readU2();
        List<Integer> interfaces = new ArrayList<>(interfacesCount);
        for (int i = 0; i < interfacesCount; i++) {
            int ifaceIndex = reader.readU2();
            validateClassIndex(constantPool, ifaceIndex, "interface[" + i + "]");
            interfaces.add(ifaceIndex);
        }

        // 6. Fields
        int fieldsCount = reader.readU2();
        List<FieldInfo> fields = new ArrayList<>(fieldsCount);
        for (int i = 0; i < fieldsCount; i++) {
            int fFlags = reader.readU2();
            int nameIndex = reader.readU2();
            int descriptorIndex = reader.readU2();
            validateUtf8Index(constantPool, nameIndex, "field name");
            validateUtf8Index(constantPool, descriptorIndex, "field descriptor");

            int attrCount = reader.readU2();
            List<Attribute> fieldAttrs = parseAttributes(reader, constantPool, attrCount, false);
            fields.add(new FieldInfo(fFlags, nameIndex, descriptorIndex, fieldAttrs));
        }

        // 7. Methods
        int methodsCount = reader.readU2();
        List<MethodInfo> methods = new ArrayList<>(methodsCount);
        for (int i = 0; i < methodsCount; i++) {
            int mFlags = reader.readU2();
            int nameIndex = reader.readU2();
            int descriptorIndex = reader.readU2();
            validateUtf8Index(constantPool, nameIndex, "method name");
            validateUtf8Index(constantPool, descriptorIndex, "method descriptor");

            int attrCount = reader.readU2();
            List<Attribute> methodAttrs = parseAttributes(reader, constantPool, attrCount, true);

            CodeAttribute codeAttr = null;
            for (Attribute attr : methodAttrs) {
                if (attr instanceof CodeAttribute ca) {
                    if (codeAttr != null) {
                        throw new ClassFormatException("Duplicate Code attribute in method");
                    }
                    codeAttr = ca;
                }
            }

            if (AccessFlags.isAbstract(mFlags) || AccessFlags.isNative(mFlags)) {
                if (codeAttr != null) {
                    throw new ClassFormatException("Abstract or native method cannot have a Code attribute");
                }
            }

            methods.add(new MethodInfo(mFlags, nameIndex, descriptorIndex, methodAttrs, codeAttr));
        }

        // 8. Class Attributes
        int classAttrCount = reader.readU2();
        List<Attribute> classAttrs = parseAttributes(reader, constantPool, classAttrCount, false);

        // 9. Trailing byte check
        if (reader.hasRemaining()) {
            throw new ClassFormatException(
                    String.format("Trailing unread data: %d bytes remaining at end of class file", reader.remaining())
            );
        }

        return new ClassFile(
                minorVersion,
                majorVersion,
                constantPool,
                accessFlags,
                thisClassIndex,
                superClassIndex,
                interfaces,
                fields,
                methods,
                classAttrs
        );
    }

    private static List<Attribute> parseAttributes(
            BytecodeReader reader,
            ConstantPool cp,
            int count,
            boolean allowCode
    ) {
        List<Attribute> attributes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int attrNameIndex = reader.readU2();
            validateUtf8Index(cp, attrNameIndex, "attribute name");
            String attrName = cp.getUtf8(attrNameIndex);

            long attrLength = reader.readU4();
            if (attrLength > Integer.MAX_VALUE) {
                throw new ClassFormatException("Attribute length exceeds maximum supported size: " + attrLength);
            }
            if (reader.remaining() < attrLength) {
                throw new ClassFormatException(
                        String.format("Truncated attribute '%s': declared %d bytes, only %d remaining",
                                attrName, attrLength, reader.remaining())
                );
            }

            BytecodeReader attrReader = reader.slice((int) attrLength);

            if (allowCode && "Code".equals(attrName)) {
                attributes.add(parseCodeAttribute(attrReader, cp));
            } else {
                byte[] rawBytes = attrReader.readBytes((int) attrLength);
                attributes.add(new UnknownAttribute(attrName, rawBytes));
            }
        }
        return attributes;
    }

    private static CodeAttribute parseCodeAttribute(BytecodeReader attrReader, ConstantPool cp) {
        int maxStack = attrReader.readU2();
        int maxLocals = attrReader.readU2();
        long codeLength = attrReader.readU4();

        if (codeLength > Integer.MAX_VALUE) {
            throw new ClassFormatException("Code length exceeds maximum supported size: " + codeLength);
        }
        if (attrReader.remaining() < codeLength) {
            throw new ClassFormatException(
                    String.format("Truncated code in Code attribute: declared %d bytes, only %d remaining",
                            codeLength, attrReader.remaining())
            );
        }

        byte[] code = attrReader.readBytes((int) codeLength);

        int exTableLength = attrReader.readU2();
        List<ExceptionTableEntry> exTable = new ArrayList<>(exTableLength);
        for (int e = 0; e < exTableLength; e++) {
            int startPc = attrReader.readU2();
            int endPc = attrReader.readU2();
            int handlerPc = attrReader.readU2();
            int catchType = attrReader.readU2();

            if (catchType != 0) {
                validateClassIndex(cp, catchType, "exception table catch_type");
            }
            exTable.add(new ExceptionTableEntry(startPc, endPc, handlerPc, catchType));
        }

        int nestedAttrCount = attrReader.readU2();
        List<Attribute> nestedAttrs = parseAttributes(attrReader, cp, nestedAttrCount, false);

        if (attrReader.hasRemaining()) {
            throw new ClassFormatException(
                    String.format("Extra unread data in Code attribute: %d bytes remaining", attrReader.remaining())
            );
        }

        return new CodeAttribute(maxStack, maxLocals, code, exTable, nestedAttrs);
    }

    private static void validateClassIndex(ConstantPool cp, int index, String location) {
        try {
            ConstantPoolEntry entry = cp.get(index);
            if (!(entry instanceof ClassEntry)) {
                throw new ClassFormatException(
                        String.format("Invalid %s: index %d is not a CONSTANT_Class entry (found %s)",
                                location, index, entry.getClass().getSimpleName())
                );
            }
        } catch (Exception e) {
            throw new ClassFormatException("Invalid " + location + ": " + e.getMessage(), e);
        }
    }

    private static void validateUtf8Index(ConstantPool cp, int index, String location) {
        try {
            ConstantPoolEntry entry = cp.get(index);
            if (!(entry instanceof Utf8Entry)) {
                throw new ClassFormatException(
                        String.format("Invalid %s: index %d is not a CONSTANT_Utf8 entry (found %s)",
                                location, index, entry.getClass().getSimpleName())
                );
            }
        } catch (Exception e) {
            throw new ClassFormatException("Invalid " + location + ": " + e.getMessage(), e);
        }
    }
}
