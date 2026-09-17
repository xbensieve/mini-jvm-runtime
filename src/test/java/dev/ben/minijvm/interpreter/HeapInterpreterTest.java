package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeapInterpreterTest {

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

    private ClassFile createTestClass(String className, List<FieldInfo> fields, List<MethodInfo> methods, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1,
                0,
                List.of(),
                fields,
                methods,
                List.of()
        );
    }

    @Test
    @DisplayName("Interpreter executes NEW, DUP, ASTORE, ALOAD, ARETURN end-to-end")
    void testNewDupStoreLoadAreturn() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Widget"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Widget"));                // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()Lpkg/Widget;"));            // #4
        ConstantPool cp = new ConstantPool(entries);

        // Bytecode:
        // 0: new #1 (3 bytes)
        // 3: dup (1 byte)
        // 4: astore_1 (1 byte)
        // 5: aload_1 (1 byte)
        // 6: areturn (1 byte)
        byte[] code = new byte[]{
                (byte) 0xBB, 0x00, 0x01,  // new #1
                0x59,                     // dup
                0x4C,                     // astore_1
                0x2B,                     // aload_1
                (byte) 0xB0               // areturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Widget", List.of(), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        assertTrue(frame.returnValue().isPresent());
        Value ret = frame.returnValue().get();
        assertTrue(ret.isReference());
        assertFalse(ret.isNull());
        ObjectReference objRef = (ObjectReference) ret;
        assertEquals("pkg/Widget", objRef.runtimeClassName());
        assertEquals(1L, objRef.handle());
        assertTrue(heap.containsObject(objRef.handle()));
    }

    @Test
    @DisplayName("Interpreter executes PUTFIELD and GETFIELD on heap-allocated object")
    void testPutFieldAndGetField() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Widget"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Widget"));                // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                    // #5 FieldRef Widget.val:I
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));                 // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("val"));                       // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));                         // #8
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo valField = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());

        // Bytecode:
        // 0: new #1 (3 bytes)
        // 3: dup (1 byte)
        // 4: astore_1 (1 byte)
        // 5: aload_1 (1 byte)
        // 6: bipush 42 (2 bytes)
        // 8: putfield #5 (3 bytes)  -> stack: ..., objectref, value
        // 11: aload_1 (1 byte)
        // 12: getfield #5 (3 bytes) -> stack: ..., objectref -> value
        // 15: ireturn (1 byte)
        byte[] code = new byte[]{
                (byte) 0xBB, 0x00, 0x01,  // 0: new #1
                0x59,                     // 3: dup
                0x4C,                     // 4: astore_1
                0x2B,                     // 5: aload_1
                0x10, 42,                 // 6: bipush 42
                (byte) 0xB5, 0x00, 0x05,  // 8: putfield #5
                0x2B,                     // 11: aload_1
                (byte) 0xB4, 0x00, 0x05,  // 12: getfield #5
                (byte) 0xAC               // 15: ireturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Widget", List.of(valField), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        assertEquals(Value.ofInt(42), frame.returnValue().orElseThrow());

        // Also check object state on heap directly
        GuestObject obj = heap.getObject(1L);
        assertEquals(Value.ofInt(42), obj.getField(new FieldKey("pkg/Widget", "val", "I")));
    }

    @Test
    @DisplayName("Interpreter executes PUTSTATIC and GETSTATIC")
    void testPutStaticAndGetStatic() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Widget"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Widget"));                // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                    // #5 FieldRef Widget.counter:I
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));                 // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("counter"));                   // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));                         // #8
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo staticField = new FieldInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 7, 8, List.of());

        // Bytecode:
        // 0: bipush 99 (2 bytes)
        // 2: putstatic #5 (3 bytes)
        // 5: getstatic #5 (3 bytes)
        // 8: ireturn (1 byte)
        byte[] code = new byte[]{
                0x10, 99,                 // 0: bipush 99
                (byte) 0xB3, 0x00, 0x05,  // 2: putstatic #5
                (byte) 0xB2, 0x00, 0x05,  // 5: getstatic #5
                (byte) 0xAC               // 8: ireturn
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Widget", List.of(staticField), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        assertEquals(Value.ofInt(99), frame.returnValue().orElseThrow());
        assertEquals(Value.ofInt(99), heap.getStaticField(new FieldKey("pkg/Widget", "counter", "I")));
    }

    @Test
    @DisplayName("GETFIELD on null receiver throws StackFaultException (NullPointerException equivalent)")
    void testGetFieldOnNullReceiverThrows() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Widget"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Widget"));                // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("test"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                       // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                    // #5 FieldRef Widget.val:I
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));                 // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("val"));                       // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));                         // #8
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo valField = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());

        // Bytecode:
        // 0: aconst_null (1 byte)
        // 1: getfield #5 (3 bytes)
        byte[] code = new byte[]{
                0x01,                     // 0: aconst_null
                (byte) 0xB4, 0x00, 0x05   // 1: getfield #5
        };

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Widget", List.of(valField), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        StackFaultException ex = assertThrows(StackFaultException.class, () -> interpreter.execute(frame));
        assertTrue(ex.getMessage().contains("Null pointer dereference"));
        assertTrue(frame.hasFailed());
    }

    @Test
    @DisplayName("POP removes top value from operand stack")
    void testPopInstruction() {
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Test"),
                new ConstantPoolEntry.Utf8Entry("test"),
                new ConstantPoolEntry.Utf8Entry("()I")
        ));

        // 0: iconst_1
        // 1: iconst_2
        // 2: pop
        // 3: ireturn
        byte[] code = new byte[]{0x04, 0x05, 0x57, (byte) 0xAC};

        CodeAttribute codeAttr = new CodeAttribute(4, 4, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(), List.of(method), cp);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        assertTrue(frame.isReturned());
        assertEquals(Value.ofInt(1), frame.returnValue().orElseThrow());
    }
}
