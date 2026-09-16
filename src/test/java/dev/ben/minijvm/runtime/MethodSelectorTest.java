package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.LinkageException;
import dev.ben.minijvm.opcode.Opcode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodSelectorTest {

    private ClassRepository repository;
    private MethodSelector selector;
    private MethodResolver resolver;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        selector = new MethodSelector(repository);
        resolver = new MethodResolver(repository);
    }

    private ClassFile createClassWithSuper(String className, String superClassName, List<MethodInfo> methods, ConstantPool cp) {
        int superIdx = 0;
        if (superClassName != null) {
            // Find or assume entry index for super
            superIdx = 3;
        }
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1, // thisClassIndex (points to ClassEntry #1)
                superIdx,
                List.of(),
                List.of(),
                methods,
                List.of()
        );
    }

    @Test
    @DisplayName("Constructor and parameter null checks")
    void testNullChecks() {
        assertThrows(NullPointerException.class, () -> new MethodSelector(null));
        assertThrows(NullPointerException.class, () -> selector.selectMethod(null, null));
    }

    @Test
    @DisplayName("Receiver of exact declaring class selects that method")
    void testExactClassSelection() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Class "Base"
        entries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #2
        entries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Class "java/lang/Object"
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 NameAndType getValue:()I
        entries.add(new ConstantPoolEntry.Utf8Entry("getValue"));              // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #7
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));               // #8 Base.getValue:()I
        ConstantPool cp = new ConstantPool(entries);

        CodeAttribute code = new CodeAttribute(2, 2, new byte[]{(byte) 0x04, (byte) 0xAC}, List.of(), List.of());
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(), code);
        ClassFile baseClass = createClassWithSuper("Base", "java/lang/Object", List.of(baseMethod), cp);
        repository.register(baseClass);

        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(cp, baseClass, 8, Opcode.INVOKEVIRTUAL);
        MethodSelector.SelectedMethod selected = selector.selectMethod(resolved, baseClass);

        assertNotNull(selected);
        assertEquals(baseClass, selected.declaringClass());
        assertEquals(baseMethod, selected.method());
        assertEquals("()I", selected.descriptor().rawDescriptor());
    }

    @Test
    @DisplayName("Derived class overriding method selects Derived implementation")
    void testDerivedClassOverrideSelection() {
        // CP for Base
        List<ConstantPoolEntry> baseEntries = new ArrayList<>();
        baseEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Base
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #2
        baseEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Object
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        baseEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 getValue:()I
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("getValue"));              // #6
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #7
        baseEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));               // #8 Base.getValue:()I
        ConstantPool baseCp = new ConstantPool(baseEntries);

        CodeAttribute baseCode = new CodeAttribute(2, 2, new byte[]{(byte) 0x04, (byte) 0xAC}, List.of(), List.of());
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(), baseCode);
        ClassFile baseClass = createClassWithSuper("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // CP for Derived
        List<ConstantPoolEntry> derivedEntries = new ArrayList<>();
        derivedEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Derived
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));               // #2
        derivedEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Base
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #4
        derivedEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 getValue:()I
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("getValue"));              // #6
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #7
        ConstantPool derivedCp = new ConstantPool(derivedEntries);

        CodeAttribute derivedCode = new CodeAttribute(2, 2, new byte[]{(byte) 0x05, (byte) 0xAC}, List.of(), List.of());
        MethodInfo derivedMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(), derivedCode);
        ClassFile derivedClass = createClassWithSuper("Derived", "Base", List.of(derivedMethod), derivedCp);
        repository.register(derivedClass);

        // Symbolic resolution is against Base.getValue:()I (#8 in Base CP)
        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(baseCp, baseClass, 8, Opcode.INVOKEVIRTUAL);
        assertEquals("Base", resolved.classFile().thisClassName());

        // Virtual dispatch on Derived receiver selects Derived implementation
        MethodSelector.SelectedMethod selected = selector.selectMethod(resolved, derivedClass);
        assertNotNull(selected);
        assertEquals(derivedClass, selected.declaringClass());
        assertEquals(derivedMethod, selected.method());
    }

    @Test
    @DisplayName("Derived class inheriting non-overridden method selects Base implementation")
    void testInheritedMethodSelection() {
        // Base has getValue:()I
        List<ConstantPoolEntry> baseEntries = new ArrayList<>();
        baseEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Base
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #2
        baseEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Object
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        baseEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 getValue:()I
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("getValue"));              // #6
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #7
        baseEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));               // #8 Base.getValue:()I
        ConstantPool baseCp = new ConstantPool(baseEntries);

        CodeAttribute baseCode = new CodeAttribute(2, 2, new byte[]{(byte) 0x04, (byte) 0xAC}, List.of(), List.of());
        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(), baseCode);
        ClassFile baseClass = createClassWithSuper("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Derived has NO methods of its own
        List<ConstantPoolEntry> derivedEntries = new ArrayList<>();
        derivedEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Derived
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));               // #2
        derivedEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Base
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #4
        ConstantPool derivedCp = new ConstantPool(derivedEntries);

        ClassFile derivedClass = createClassWithSuper("Derived", "Base", List.of(), derivedCp);
        repository.register(derivedClass);

        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(baseCp, baseClass, 8, Opcode.INVOKEVIRTUAL);
        MethodSelector.SelectedMethod selected = selector.selectMethod(resolved, derivedClass);

        assertNotNull(selected);
        assertEquals(baseClass, selected.declaringClass());
        assertEquals(baseMethod, selected.method());
    }

    @Test
    @DisplayName("Multi-level hierarchy: Base -> Mid -> Derived with intermediate override")
    void testMultiLevelOverride() {
        // Base
        List<ConstantPoolEntry> baseEntries = new ArrayList<>();
        baseEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        baseEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Base
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #2
        baseEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Object
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        baseEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 calc:()I
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("calc"));                  // #6
        baseEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #7
        baseEntries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));               // #8 Base.calc:()I
        ConstantPool baseCp = new ConstantPool(baseEntries);

        MethodInfo baseMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 2, new byte[]{(byte) 0x03, (byte) 0xAC}, List.of(), List.of()));
        ClassFile baseClass = createClassWithSuper("Base", "java/lang/Object", List.of(baseMethod), baseCp);
        repository.register(baseClass);

        // Mid overrides calc:()I
        List<ConstantPoolEntry> midEntries = new ArrayList<>();
        midEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        midEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Mid
        midEntries.add(new ConstantPoolEntry.Utf8Entry("Mid"));                   // #2
        midEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Base
        midEntries.add(new ConstantPoolEntry.Utf8Entry("Base"));                  // #4
        midEntries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 calc:()I
        midEntries.add(new ConstantPoolEntry.Utf8Entry("calc"));                  // #6
        midEntries.add(new ConstantPoolEntry.Utf8Entry("()I"));                   // #7
        ConstantPool midCp = new ConstantPool(midEntries);

        MethodInfo midMethod = new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                new CodeAttribute(2, 2, new byte[]{(byte) 0x04, (byte) 0xAC}, List.of(), List.of()));
        ClassFile midClass = createClassWithSuper("Mid", "Base", List.of(midMethod), midCp);
        repository.register(midClass);

        // Derived does NOT override calc:()I
        List<ConstantPoolEntry> derivedEntries = new ArrayList<>();
        derivedEntries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        derivedEntries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 Derived
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("Derived"));               // #2
        derivedEntries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Mid
        derivedEntries.add(new ConstantPoolEntry.Utf8Entry("Mid"));                   // #4
        ConstantPool derivedCp = new ConstantPool(derivedEntries);

        ClassFile derivedClass = createClassWithSuper("Derived", "Mid", List.of(), derivedCp);
        repository.register(derivedClass);

        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(baseCp, baseClass, 8, Opcode.INVOKEVIRTUAL);

        // When receiver is Mid: selects Mid implementation
        MethodSelector.SelectedMethod midSelected = selector.selectMethod(resolved, midClass);
        assertEquals(midClass, midSelected.declaringClass());
        assertEquals(midMethod, midSelected.method());

        // When receiver is Derived: walks up to Mid and selects Mid implementation
        MethodSelector.SelectedMethod derivedSelected = selector.selectMethod(resolved, derivedClass);
        assertEquals(midClass, derivedSelected.declaringClass());
        assertEquals(midMethod, derivedSelected.method());
    }

    @Test
    @DisplayName("Receiver not a subtype of resolved class throws LinkageException")
    void testReceiverNotSubtypeThrows() {
        List<ConstantPoolEntry> entriesA = new ArrayList<>();
        entriesA.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entriesA.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 ClassA
        entriesA.add(new ConstantPoolEntry.Utf8Entry("ClassA"));                // #2
        entriesA.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Object
        entriesA.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        entriesA.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 run:()V
        entriesA.add(new ConstantPoolEntry.Utf8Entry("run"));                   // #6
        entriesA.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #7
        entriesA.add(new ConstantPoolEntry.MethodRefEntry(1, 5));               // #8 ClassA.run:()V
        ConstantPool cpA = new ConstantPool(entriesA);

        ClassFile classA = createClassWithSuper("ClassA", "java/lang/Object",
                List.of(new MethodInfo(AccessFlags.ACC_PUBLIC, 6, 7, List.of(),
                        new CodeAttribute(1, 1, new byte[]{(byte) 0xB1}, List.of(), List.of()))),
                cpA);
        repository.register(classA);

        // ClassB is unrelated to ClassA
        List<ConstantPoolEntry> entriesB = new ArrayList<>();
        entriesB.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entriesB.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 ClassB
        entriesB.add(new ConstantPoolEntry.Utf8Entry("ClassB"));                // #2
        entriesB.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Object
        entriesB.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        ConstantPool cpB = new ConstantPool(entriesB);

        ClassFile classB = createClassWithSuper("ClassB", "java/lang/Object", List.of(), cpB);
        repository.register(classB);

        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(cpA, classA, 8, Opcode.INVOKEVIRTUAL);

        LinkageException ex = assertThrows(LinkageException.class, () -> selector.selectMethod(resolved, classB));
        assertTrue(ex.getMessage().contains("not a subtype"));
    }

    @Test
    @DisplayName("Method without CodeAttribute throws LinkageException")
    void testMethodWithoutCodeThrows() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                      // #1 NativeService
        entries.add(new ConstantPoolEntry.Utf8Entry("NativeService"));          // #2
        entries.add(new ConstantPoolEntry.ClassEntry(4));                      // #3 Object
        entries.add(new ConstantPoolEntry.Utf8Entry("java/lang/Object"));      // #4
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(6, 7));             // #5 nativeOp:()V
        entries.add(new ConstantPoolEntry.Utf8Entry("nativeOp"));              // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                   // #7
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 5));               // #8 NativeService.nativeOp:()V
        ConstantPool cp = new ConstantPool(entries);

        // Abstract / native method: no CodeAttribute
        MethodInfo methodNoCode = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_ABSTRACT, 6, 7, List.of(), (CodeAttribute) null);
        ClassFile cf = createClassWithSuper("NativeService", "java/lang/Object", List.of(methodNoCode), cp);
        repository.register(cf);

        MethodResolver.ResolvedMethod resolved = new MethodResolver.ResolvedMethod(cf, methodNoCode, MethodDescriptor.parse("()V"));

        LinkageException ex = assertThrows(LinkageException.class, () -> selector.selectMethod(resolved, cf));
        assertTrue(ex.getMessage().contains("has no Code attribute"));
    }

    @Test
    @DisplayName("Subtype check handles identity, object, and hierarchy correctly")
    void testIsSubtypeOf() {
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("X"));
        entries.add(new ConstantPoolEntry.ClassEntry(4));
        entries.add(new ConstantPoolEntry.Utf8Entry("Y"));
        ConstantPool cp = new ConstantPool(entries);

        ClassFile classX = createClassWithSuper("X", "Y", List.of(), cp);
        repository.register(classX);

        assertTrue(selector.isSubtypeOf(classX, "X"));
        assertTrue(selector.isSubtypeOf(classX, "java/lang/Object"));
        assertTrue(selector.isSubtypeOf(classX, "Y"));
        assertFalse(selector.isSubtypeOf(classX, "Z"));
        assertFalse(selector.isSubtypeOf(null, "X"));
        assertFalse(selector.isSubtypeOf(classX, null));
    }
}
