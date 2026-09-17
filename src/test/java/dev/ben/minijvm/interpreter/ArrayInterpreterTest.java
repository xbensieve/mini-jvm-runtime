package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArrayInterpreterTest {

    private ClassRepository repository;
    private MethodResolver methodResolver;
    private MethodSelector methodSelector;
    private FieldResolver fieldResolver;
    private Heap heap;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        methodResolver = new MethodResolver(repository);
        methodSelector = new MethodSelector(repository);
        fieldResolver = new FieldResolver(repository);
        heap = new Heap(repository);
        interpreter = new Interpreter(new BytecodeDecoder(), methodResolver, methodSelector, fieldResolver, heap);
    }

    private ClassFile createTestClass(String className, List<MethodInfo> methods, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1,
                0,
                List.of(),
                List.of(),
                methods,
                List.of()
        );
    }

    @Test
    @DisplayName("Execute newarray and arraylength for int array")
    void testNewarrayAndArraylengthInt() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()I")
        ));

        // Bytecode:
        // 0: bipush 10
        // 2: newarray 10 (T_INT)
        // 4: arraylength
        // 5: ireturn
        byte[] code = new byte[]{
                0x10, 10,                 // bipush 10
                (byte) 0xBC, 10,          // newarray 10 (T_INT)
                (byte) 0xBE,              // arraylength
                (byte) 0xAC               // ireturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        assertEquals(Value.ofInt(10), frame.returnValue().orElseThrow());
    }

    @Test
    @DisplayName("Execute newarray with negative count throws StackFaultException")
    void testNewarrayNegativeCountThrows() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()V")
        ));

        // 0: iconst_m1
        // 1: newarray 10
        byte[] code = new byte[]{
                0x02,                     // iconst_m1
                (byte) 0xBC, 10           // newarray 10
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);

        Frame frame = new Frame(cf, method);
        StackFaultException ex = assertThrows(StackFaultException.class, () -> interpreter.execute(frame));
        assertTrue(ex.getMessage().contains("Negative array size"));
    }

    @Test
    @DisplayName("Execute iaload and iastore sequentially")
    void testIastoreAndIaload() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()I")
        ));

        // Bytecode:
        // 0: iconst_3
        // 1: newarray 10 (T_INT)
        // 3: astore_0
        // 4: aload_0
        // 5: iconst_1      (index 1)
        // 6: bipush 42     (value 42)
        // 8: iastore
        // 9: aload_0
        // 10: iconst_1     (index 1)
        // 11: iaload
        // 12: ireturn
        byte[] code = new byte[]{
                0x06,                     // 0: iconst_3
                (byte) 0xBC, 10,          // 1: newarray 10
                0x4B,                     // 3: astore_0
                0x2A,                     // 4: aload_0
                0x04,                     // 5: iconst_1
                0x10, 42,                 // 6: bipush 42
                0x4F,                     // 8: iastore
                0x2A,                     // 9: aload_0
                0x04,                     // 10: iconst_1
                0x2E,                     // 11: iaload
                (byte) 0xAC               // 12: ireturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        assertEquals(Value.ofInt(42), frame.returnValue().orElseThrow());
    }

    @Test
    @DisplayName("Array out of bounds throws StackFaultException on load and store")
    void testArrayOutOfBoundsThrows() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()I")
        ));

        // Out of bounds store: arr of len 2, store at index 2
        byte[] codeStoreOob = new byte[]{
                0x05,                     // 0: iconst_2 (len 2)
                (byte) 0xBC, 10,          // 1: newarray 10
                0x05,                     // 3: iconst_2 (index 2)
                0x10, 99,                 // 4: bipush 99
                0x4F                      // 6: iastore
        };

        CodeAttribute codeAttrStore = new CodeAttribute(4, 4, codeStoreOob, List.of(), List.of());
        MethodInfo methodStore = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttrStore);
        ClassFile cfStore = createTestClass("pkg/Test", List.of(methodStore), cp);

        Frame frameStore = new Frame(cfStore, methodStore);
        StackFaultException exStore = assertThrows(StackFaultException.class, () -> interpreter.execute(frameStore));
        assertTrue(exStore.getMessage().contains("Array index out of bounds"));

        // Out of bounds load: arr of len 2, load at index -1
        byte[] codeLoadOob = new byte[]{
                0x05,                     // 0: iconst_2
                (byte) 0xBC, 10,          // 1: newarray 10
                0x02,                     // 3: iconst_m1
                0x2E                      // 4: iaload
        };

        CodeAttribute codeAttrLoad = new CodeAttribute(4, 4, codeLoadOob, List.of(), List.of());
        MethodInfo methodLoad = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttrLoad);
        ClassFile cfLoad = createTestClass("pkg/Test", List.of(methodLoad), cp);

        Frame frameLoad = new Frame(cfLoad, methodLoad);
        StackFaultException exLoad = assertThrows(StackFaultException.class, () -> interpreter.execute(frameLoad));
        assertTrue(exLoad.getMessage().contains("Array index out of bounds"));
    }

    @Test
    @DisplayName("Null array reference throws StackFaultException on length, load, and store")
    void testNullArrayDereferenceThrows() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()V")
        ));

        // arraylength on null
        byte[] codeLen = new byte[]{0x01, (byte) 0xBE}; // aconst_null, arraylength
        CodeAttribute attrLen = new CodeAttribute(2, 2, codeLen, List.of(), List.of());
        MethodInfo methodLen = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), attrLen);
        ClassFile cfLen = createTestClass("pkg/Test", List.of(methodLen), cp);
        Frame frameLen = new Frame(cfLen, methodLen);
        StackFaultException exLen = assertThrows(StackFaultException.class, () -> interpreter.execute(frameLen));
        assertTrue(exLen.getMessage().contains("Null pointer dereference"));

        // iaload on null
        byte[] codeLoad = new byte[]{0x01, 0x03, 0x2E}; // aconst_null, iconst_0, iaload
        CodeAttribute attrLoad = new CodeAttribute(3, 3, codeLoad, List.of(), List.of());
        MethodInfo methodLoad = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), attrLoad);
        ClassFile cfLoad = createTestClass("pkg/Test", List.of(methodLoad), cp);
        Frame frameLoad = new Frame(cfLoad, methodLoad);
        StackFaultException exLoad = assertThrows(StackFaultException.class, () -> interpreter.execute(frameLoad));
        assertTrue(exLoad.getMessage().contains("Null pointer dereference"));

        // iastore on null
        byte[] codeStore = new byte[]{0x01, 0x03, 0x03, 0x4F}; // aconst_null, iconst_0, iconst_0, iastore
        CodeAttribute attrStore = new CodeAttribute(3, 3, codeStore, List.of(), List.of());
        MethodInfo methodStore = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), attrStore);
        ClassFile cfStore = createTestClass("pkg/Test", List.of(methodStore), cp);
        Frame frameStore = new Frame(cfStore, methodStore);
        StackFaultException exStore = assertThrows(StackFaultException.class, () -> interpreter.execute(frameStore));
        assertTrue(exStore.getMessage().contains("Null pointer dereference"));
    }

    @Test
    @DisplayName("Execute anewarray, aastore, and aaload for reference arrays")
    void testAnewarrayAastoreAaload() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Widget"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Widget"));                // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()Lpkg/Widget;"));            // #4
        ConstantPool cp = new ConstantPool(entries);

        ClassFile widgetClass = new ClassFile(0, 65, cp, AccessFlags.ACC_PUBLIC, 1, 0, List.of(), List.of(), List.of(), List.of());
        repository.register(widgetClass);

        // Bytecode:
        // 0: iconst_2
        // 1: anewarray #1
        // 4: astore_0
        // 5: new #1
        // 8: astore_1
        // 9: aload_0
        // 10: iconst_0
        // 11: aload_1
        // 12: aastore
        // 13: aload_0
        // 14: iconst_0
        // 15: aaload
        // 16: areturn
        byte[] code = new byte[]{
                0x05,                     // 0: iconst_2
                (byte) 0xBD, 0x00, 0x01,  // 1: anewarray #1
                0x4B,                     // 4: astore_0
                (byte) 0xBB, 0x00, 0x01,  // 5: new #1
                0x4C,                     // 8: astore_1
                0x2A,                     // 9: aload_0
                0x03,                     // 10: iconst_0
                0x2B,                     // 11: aload_1
                0x53,                     // 12: aastore
                0x2A,                     // 13: aload_0
                0x03,                     // 14: iconst_0
                0x32,                     // 15: aaload
                (byte) 0xB0               // 16: areturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Widget", List.of(method), cp);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        Value ret = frame.returnValue().orElseThrow();
        assertTrue(ret.isReference());
        assertFalse(ret.isNull());
        ObjectReference ref = (ObjectReference) ret;
        assertEquals("pkg/Widget", ref.runtimeClassName());
    }

    @Test
    @DisplayName("Execute multianewarray for 2D and 3D arrays")
    void testMultianewarray() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "[[I"
        entries.add(new ConstantPoolEntry.Utf8Entry("[[I"));                       // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #4
        ConstantPool cp = new ConstantPool(entries);

        // Bytecode:
        // 0: iconst_3 (dim0 = 3)
        // 1: iconst_5 (dim1 = 5)
        // 2: multianewarray #1 dim=2
        // 6: astore_0
        // 7: aload_0
        // 8: iconst_1
        // 9: aaload
        // 10: arraylength
        // 11: ireturn
        byte[] code = new byte[]{
                0x06,                     // 0: iconst_3
                0x08,                     // 1: iconst_5
                (byte) 0xC5, 0x00, 0x01, 0x02, // 2: multianewarray #1 dim=2
                0x4B,                     // 6: astore_0
                0x2A,                     // 7: aload_0
                0x04,                     // 8: iconst_1
                0x32,                     // 9: aaload
                (byte) 0xBE,              // 10: arraylength
                (byte) 0xAC               // 11: ireturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        // Length of sub-array at index 1 is 5
        assertEquals(Value.ofInt(5), frame.returnValue().orElseThrow());
    }

    @Test
    @DisplayName("Execute baload, bastore, caload, castore, saload, sastore truncation and extension")
    void testByteCharShortArrayOpcodes() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()I")
        ));

        // Test baload sign extension:
        // 0: iconst_1 (len 1)
        // 1: newarray 8 (T_BYTE)
        // 3: astore_0
        // 4: aload_0
        // 5: iconst_0 (index 0)
        // 6: sipush 255 (0x00FF, as byte it is -1)
        // 9: bastore
        // 10: aload_0
        // 11: iconst_0
        // 12: baload
        // 13: ireturn
        byte[] codeByte = new byte[]{
                0x04,                     // iconst_1
                (byte) 0xBC, 8,           // newarray 8 (T_BYTE)
                0x4B,                     // astore_0
                0x2A,                     // aload_0
                0x03,                     // iconst_0
                0x11, 0x00, (byte) 0xFF,  // sipush 255
                0x54,                     // bastore
                0x2A,                     // aload_0
                0x03,                     // iconst_0
                0x33,                     // baload
                (byte) 0xAC               // ireturn
        };

        CodeAttribute attrByte = new CodeAttribute(4, 4, codeByte, List.of(), List.of());
        MethodInfo methodByte = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), attrByte);
        ClassFile cfByte = createTestClass("pkg/Test", List.of(methodByte), cp);

        Frame frameByte = new Frame(cfByte, methodByte);
        interpreter.execute(frameByte);

        assertTrue(frameByte.isReturned());
        // Byte 0xFF sign-extends to -1
        assertEquals(Value.ofInt(-1), frameByte.returnValue().orElseThrow());

        // Test caload zero extension:
        // store -1 (0xFFFF), caload should return 65535
        byte[] codeChar = new byte[]{
                0x04,                     // iconst_1
                (byte) 0xBC, 5,           // newarray 5 (T_CHAR)
                0x4B,                     // astore_0
                0x2A,                     // aload_0
                0x03,                     // iconst_0
                0x02,                     // iconst_m1 (-1)
                0x55,                     // castore
                0x2A,                     // aload_0
                0x03,                     // iconst_0
                0x34,                     // caload
                (byte) 0xAC               // ireturn
        };

        CodeAttribute attrChar = new CodeAttribute(4, 4, codeChar, List.of(), List.of());
        MethodInfo methodChar = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), attrChar);
        ClassFile cfChar = createTestClass("pkg/Test", List.of(methodChar), cp);

        Frame frameChar = new Frame(cfChar, methodChar);
        interpreter.execute(frameChar);

        assertTrue(frameChar.isReturned());
        // Char 0xFFFF zero-extends to 65535
        assertEquals(Value.ofInt(65535), frameChar.returnValue().orElseThrow());

        // Test saload sign extension:
        // store 65535 (0xFFFF), saload should return -1
        byte[] codeShort = new byte[]{
                0x04,                     // iconst_1
                (byte) 0xBC, 9,           // newarray 9 (T_SHORT)
                0x4B,                     // astore_0
                0x2A,                     // aload_0
                0x03,                     // iconst_0
                0x02,                     // iconst_m1 (-1)
                0x56,                     // sastore
                0x2A,                     // aload_0
                0x03,                     // iconst_0
                0x35,                     // saload
                (byte) 0xAC               // ireturn
        };

        CodeAttribute attrShort = new CodeAttribute(4, 4, codeShort, List.of(), List.of());
        MethodInfo methodShort = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), attrShort);
        ClassFile cfShort = createTestClass("pkg/Test", List.of(methodShort), cp);

        Frame frameShort = new Frame(cfShort, methodShort);
        interpreter.execute(frameShort);

        assertTrue(frameShort.isReturned());
        // Short 0xFFFF sign-extends to -1
        assertEquals(Value.ofInt(-1), frameShort.returnValue().orElseThrow());
    }
}
