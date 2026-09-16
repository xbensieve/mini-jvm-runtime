package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.ClassFormatException;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.exception.UnsupportedFeatureException;
import dev.ben.minijvm.opcode.Opcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodResolverTest {

    private ClassRepository repository;
    private MethodResolver resolver;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        resolver = new MethodResolver(repository);
    }

    private ClassFile createTestClass(String className, List<MethodInfo> methods, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1, // thisClassIndex (pointing to ClassEntry)
                0,
                List.of(),
                List.of(),
                methods,
                List.of()
        );
    }

    @Test
    @DisplayName("Resolve static method successfully")
    void testResolveStaticMethod() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3 NameAndType add:(II)I
        entries.add(new ConstantPoolEntry.Utf8Entry("add"));                   // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("(II)I"));                 // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6 MethodRef #1.#3
        ConstantPool cp = new ConstantPool(entries);

        CodeAttribute codeAttr = new CodeAttribute(4, 4, new byte[]{(byte) 0xB1}, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 4, 5, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);
        repository.register(cf);

        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(cp, cf, 6, Opcode.INVOKESTATIC);
        assertNotNull(resolved);
        assertEquals(cf, resolved.classFile());
        assertEquals(method, resolved.method());
        assertEquals("(II)I", resolved.descriptor().rawDescriptor());
        assertEquals(2, resolved.descriptor().parameterCount());
    }

    @Test
    @DisplayName("Resolve instance method successfully with INVOKEVIRTUAL")
    void testResolveInstanceMethod() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3 NameAndType compute:()I
        entries.add(new ConstantPoolEntry.Utf8Entry("compute"));               // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6 MethodRef #1.#3
        ConstantPool cp = new ConstantPool(entries);

        CodeAttribute codeAttr = new CodeAttribute(4, 4, new byte[]{(byte) 0xAC}, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC, 4, 5, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);
        repository.register(cf);

        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(cp, cf, 6, Opcode.INVOKEVIRTUAL);
        assertNotNull(resolved);
        assertEquals(cf, resolved.classFile());
        assertEquals(method, resolved.method());
    }

    @Test
    @DisplayName("Fail when class is not found in repository")
    void testClassNotFound() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Missing"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Missing"));           // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("foo"));                   // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(LinkageException.class, () ->
                resolver.resolveMethod(cp, null, 6, Opcode.INVOKESTATIC)
        );
    }

    @Test
    @DisplayName("Fail when method is not found in class")
    void testMethodNotFound() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("missingMethod"));          // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        ClassFile cf = createTestClass("pkg/Test", List.of(), cp);
        repository.register(cf);

        assertThrows(LinkageException.class, () ->
                resolver.resolveMethod(cp, cf, 6, Opcode.INVOKESTATIC)
        );
    }

    @Test
    @DisplayName("Fail when method has no Code attribute")
    void testMethodWithoutCodeAttribute() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("abstractMethod"));        // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_ABSTRACT, 4, 5, List.of(), null);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);
        repository.register(cf);

        assertThrows(LinkageException.class, () ->
                resolver.resolveMethod(cp, cf, 6, Opcode.INVOKESTATIC)
        );
    }

    @Test
    @DisplayName("Fail when invokestatic targets non-static method")
    void testInvokeStaticOnNonStaticMethod() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("instanceMethod"));        // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        CodeAttribute codeAttr = new CodeAttribute(4, 4, new byte[]{(byte) 0xB1}, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC, 4, 5, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);
        repository.register(cf);

        assertThrows(LinkageException.class, () ->
                resolver.resolveMethod(cp, cf, 6, Opcode.INVOKESTATIC)
        );
    }

    @Test
    @DisplayName("Fail when invokevirtual targets static method")
    void testInvokeVirtualOnStaticMethod() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("staticMethod"));          // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        CodeAttribute codeAttr = new CodeAttribute(4, 4, new byte[]{(byte) 0xB1}, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 4, 5, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);
        repository.register(cf);

        assertThrows(LinkageException.class, () ->
                resolver.resolveMethod(cp, cf, 6, Opcode.INVOKEVIRTUAL)
        );
    }

    @Test
    @DisplayName("Fail when invokevirtual targets constructor <init>")
    void testInvokeVirtualOnConstructor() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("<init>"));                // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        CodeAttribute codeAttr = new CodeAttribute(4, 4, new byte[]{(byte) 0xB1}, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC, 4, 5, List.of(), codeAttr);
        ClassFile cf = createTestClass("pkg/Test", List.of(method), cp);
        repository.register(cf);

        assertThrows(LinkageException.class, () ->
                resolver.resolveMethod(cp, cf, 6, Opcode.INVOKEVIRTUAL)
        );
    }

    @Test
    @DisplayName("Fail when method descriptor has category-2 argument or return")
    void testCategory2MethodRejected() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));              // #2
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(4, 5));             // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("longMethod"));            // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("(J)V"));                  // #5
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 3));               // #6
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(UnsupportedFeatureException.class, () ->
                resolver.resolveMethod(cp, null, 6, Opcode.INVOKESTATIC)
        );
    }

    @Test
    @DisplayName("Fail on wrong constant pool entry type")
    void testWrongConstantPoolEntryType() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.IntegerEntry(42));                   // #1 Integer (not MethodRef)
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(ClassFormatException.class, () ->
                resolver.resolveMethod(cp, null, 1, Opcode.INVOKESTATIC)
        );
    }
}
