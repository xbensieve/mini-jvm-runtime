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

class FieldResolverTest {

    private ClassRepository repository;
    private FieldResolver resolver;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        resolver = new FieldResolver(repository);
    }

    private ClassFile createTestClass(String className, String superClassName, List<FieldInfo> fields, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1,
                superClassName != null ? 3 : 0,
                List.of(),
                fields,
                List.of(),
                List.of()
        );
    }

    @Test
    @DisplayName("Resolve static field successfully with GETSTATIC and PUTSTATIC")
    void testResolveStaticField() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));                  // #2
        entries.add(new ConstantPoolEntry.ClassEntry(4));                          // #3 Class "java/lang/Object"
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));          // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                    // #5 FieldRef #1.#6
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));                 // #6 NameAndType counter:I
        entries.add(new ConstantPoolEntry.Utf8Entry("counter"));                   // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));                         // #8
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo staticField = new FieldInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 7, 8, List.of());
        ClassFile cf = createTestClass("pkg/Test", null, List.of(staticField), cp);
        repository.register(cf);

        FieldResolver.ResolvedField resolvedGet = resolver.resolveField(cp, cf, 5, Opcode.GETSTATIC);
        assertNotNull(resolvedGet);
        assertTrue(resolvedGet.isStatic());
        assertEquals("pkg/Test", resolvedGet.declaringClass().thisClassName());
        assertEquals("counter", resolvedGet.field().name(cp));
        assertEquals(new FieldKey("pkg/Test", "counter", "I"), resolvedGet.key());

        FieldResolver.ResolvedField resolvedPut = resolver.resolveField(cp, cf, 5, Opcode.PUTSTATIC);
        assertNotNull(resolvedPut);
        assertEquals(resolvedGet.key(), resolvedPut.key());
    }

    @Test
    @DisplayName("Resolve instance field successfully with GETFIELD and PUTFIELD")
    void testResolveInstanceField() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                          // #1 Class "pkg/Test"
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));                  // #2
        entries.add(new ConstantPoolEntry.ClassEntry(4));                          // #3 Class "java/lang/Object"
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));          // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                    // #5 FieldRef #1.#6
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));                 // #6 NameAndType instanceCount:I
        entries.add(new ConstantPoolEntry.Utf8Entry("instanceCount"));             // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));                         // #8
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo instanceField = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());
        ClassFile cf = createTestClass("pkg/Test", null, List.of(instanceField), cp);
        repository.register(cf);

        FieldResolver.ResolvedField resolvedGet = resolver.resolveField(cp, cf, 5, Opcode.GETFIELD);
        assertNotNull(resolvedGet);
        assertFalse(resolvedGet.isStatic());
        assertEquals("instanceCount", resolvedGet.field().name(cp));

        FieldResolver.ResolvedField resolvedPut = resolver.resolveField(cp, cf, 5, Opcode.PUTFIELD);
        assertNotNull(resolvedPut);
    }

    @Test
    @DisplayName("Opcode mismatch: GETFIELD on static field throws LinkageException")
    void testGetFieldOnStaticFieldThrows() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));
        entries.add(new ConstantPoolEntry.ClassEntry(4));
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));
        entries.add(new ConstantPoolEntry.Utf8Entry("counter"));
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo staticField = new FieldInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 7, 8, List.of());
        ClassFile cf = createTestClass("pkg/Test", null, List.of(staticField), cp);
        repository.register(cf);

        LinkageException ex = assertThrows(LinkageException.class, () ->
                resolver.resolveField(cp, cf, 5, Opcode.GETFIELD)
        );
        assertTrue(ex.getMessage().contains("attempted on static field"));
    }

    @Test
    @DisplayName("Opcode mismatch: GETSTATIC on non-static field throws LinkageException")
    void testGetStaticOnInstanceFieldThrows() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));
        entries.add(new ConstantPoolEntry.ClassEntry(4));
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));
        entries.add(new ConstantPoolEntry.Utf8Entry("val"));
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo instanceField = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());
        ClassFile cf = createTestClass("pkg/Test", null, List.of(instanceField), cp);
        repository.register(cf);

        LinkageException ex = assertThrows(LinkageException.class, () ->
                resolver.resolveField(cp, cf, 5, Opcode.GETSTATIC)
        );
        assertTrue(ex.getMessage().contains("attempted on non-static field"));
    }

    @Test
    @DisplayName("Resolves inherited field from superclass hierarchy")
    void testResolveInheritedField() {
        // Super class with field
        List<ConstantPoolEntry> superEntries = new ArrayList<>();
        superEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        superEntries.add(new ConstantPoolEntry.ClassEntry(2));
        superEntries.add(new ConstantPoolEntry.Utf8Entry("pkg/Super"));
        superEntries.add(new ConstantPoolEntry.ClassEntry(4));
        superEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        superEntries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));
        superEntries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));
        superEntries.add(new ConstantPoolEntry.Utf8Entry("baseField"));
        superEntries.add(new ConstantPoolEntry.Utf8Entry("I"));
        ConstantPool superCp = new ConstantPool(superEntries);

        FieldInfo superField = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());
        ClassFile superCf = createTestClass("pkg/Super", null, List.of(superField), superCp);
        repository.register(superCf);

        // Subclass referencing pkg/Sub but field defined in pkg/Super
        List<ConstantPoolEntry> subEntries = new ArrayList<>();
        subEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        subEntries.add(new ConstantPoolEntry.ClassEntry(2));
        subEntries.add(new ConstantPoolEntry.Utf8Entry("pkg/Sub"));
        subEntries.add(new ConstantPoolEntry.ClassEntry(4));
        subEntries.add(new ConstantPoolEntry.Utf8Entry("pkg/Super"));
        subEntries.add(new ConstantPoolEntry.FieldRefEntry(1, 6)); // points to pkg/Sub
        subEntries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));
        subEntries.add(new ConstantPoolEntry.Utf8Entry("baseField"));
        subEntries.add(new ConstantPoolEntry.Utf8Entry("I"));
        ConstantPool subCp = new ConstantPool(subEntries);

        ClassFile subCf = createTestClass("pkg/Sub", "pkg/Super", List.of(), subCp);
        repository.register(subCf);

        FieldResolver.ResolvedField resolved = resolver.resolveField(subCp, subCf, 5, Opcode.GETFIELD);
        assertNotNull(resolved);
        assertEquals("pkg/Super", resolved.declaringClass().thisClassName());
        assertEquals("baseField", resolved.field().name(superCp));
    }

    @Test
    @DisplayName("Throws LinkageException when field is not found")
    void testFieldNotFoundThrows() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));
        entries.add(new ConstantPoolEntry.ClassEntry(4));
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));
        entries.add(new ConstantPoolEntry.Utf8Entry("nonExistent"));
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));
        ConstantPool cp = new ConstantPool(entries);

        ClassFile cf = createTestClass("pkg/Test", null, List.of(), cp);
        repository.register(cf);

        assertThrows(LinkageException.class, () ->
                resolver.resolveField(cp, cf, 5, Opcode.GETFIELD)
        );
    }

    @Test
    @DisplayName("Throws ClassFormatException for invalid constant pool index or tag")
    void testInvalidConstantPoolEntries() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.Utf8Entry("just a string"));
        ConstantPool cp = new ConstantPool(entries);

        assertThrows(ClassFormatException.class, () -> resolver.resolveField(cp, null, 0, Opcode.GETSTATIC));
        assertThrows(ClassFormatException.class, () -> resolver.resolveField(cp, null, 5, Opcode.GETSTATIC));
        assertThrows(ClassFormatException.class, () -> resolver.resolveField(cp, null, 1, Opcode.GETSTATIC));
    }

    @Test
    @DisplayName("Throws UnsupportedFeatureException when passed an invalid opcode")
    void testUnsupportedOpcodeThrows() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Test"));
        entries.add(new ConstantPoolEntry.ClassEntry(4));
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(7, 8));
        entries.add(new ConstantPoolEntry.Utf8Entry("f"));
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));
        ConstantPool cp = new ConstantPool(entries);

        FieldInfo f = new FieldInfo(AccessFlags.ACC_PUBLIC, 7, 8, List.of());
        ClassFile cf = createTestClass("pkg/Test", null, List.of(f), cp);
        repository.register(cf);

        assertThrows(UnsupportedFeatureException.class, () ->
                resolver.resolveField(cp, cf, 5, Opcode.INVOKEVIRTUAL)
        );
    }
}
