package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.CodeAttribute;
import dev.ben.minijvm.classfile.ConstantPool;
import dev.ben.minijvm.classfile.ConstantPoolEntry;
import dev.ben.minijvm.classfile.ExceptionTableEntry;
import dev.ben.minijvm.classfile.MethodInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionTableResolverTest {

    private ClassRepository repository;
    private ExceptionTableResolver resolver;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        resolver = new ExceptionTableResolver(repository);
    }

    private ConstantPool createConstantPoolWithClasses(String... classNames) {
        List<ConstantPoolEntry> entries = new java.util.ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("Slot 0"));

        int index = 1;
        for (String className : classNames) {
            entries.add(new ConstantPoolEntry.Utf8Entry(className)); // #index: utf8
            entries.add(new ConstantPoolEntry.ClassEntry(index));     // #(index+1): class -> utf8
            index += 2;
        }

        return new ConstantPool(entries);
    }

    private ClassFile createTestClass(String className, String superClassName, ConstantPool cp) {
        int thisClassIdx = 2; // first class entry
        int superClassIdx = (superClassName != null) ? 4 : 0; // second class entry if exists

        return new ClassFile(
                0, 65,
                cp,
                0x0001,
                thisClassIdx,
                superClassIdx,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private Frame createFrameWithExceptionTable(List<ExceptionTableEntry> exTable, ConstantPool cp) {
        CodeAttribute code = new CodeAttribute(4, 4, new byte[50], exTable, List.of());
        MethodInfo method = new MethodInfo(0x0001, 1, 1, List.of(), code);
        return new Frame(method, cp, 4, 4);
    }

    @Test
    @DisplayName("Range checking: startPc inclusive, endPc exclusive")
    void testPcRangeBoundaries() {
        ConstantPool cp = createConstantPoolWithClasses("java/lang/Exception");
        // entry: [5, 15), handler = 20, catchType = 0 (catch-all)
        ExceptionTableEntry entry = new ExceptionTableEntry(5, 15, 20, 0);
        Frame frame = createFrameWithExceptionTable(List.of(entry), cp);

        // Before start
        assertTrue(resolver.findHandler(frame, "java/lang/RuntimeException", 4).isEmpty());
        // At start (inclusive)
        assertTrue(resolver.findHandler(frame, "java/lang/RuntimeException", 5).isPresent());
        assertEquals(20, resolver.findHandler(frame, "java/lang/RuntimeException", 5).get().handlerPc());
        // Inside range
        assertTrue(resolver.findHandler(frame, "java/lang/RuntimeException", 10).isPresent());
        // At end - 1 (last included instruction)
        assertTrue(resolver.findHandler(frame, "java/lang/RuntimeException", 14).isPresent());
        // At end (exclusive)
        assertTrue(resolver.findHandler(frame, "java/lang/RuntimeException", 15).isEmpty());
        // After end
        assertTrue(resolver.findHandler(frame, "java/lang/RuntimeException", 16).isEmpty());
    }

    @Test
    @DisplayName("catchType 0 matches any exception (finally block)")
    void testCatchTypeZeroMatchesAll() {
        ConstantPool cp = createConstantPoolWithClasses("java/lang/Exception");
        ExceptionTableEntry finallyHandler = new ExceptionTableEntry(0, 10, 30, 0);
        Frame frame = createFrameWithExceptionTable(List.of(finallyHandler), cp);

        Optional<ExceptionTableEntry> match1 = resolver.findHandler(frame, "java/lang/NullPointerException", 2);
        assertTrue(match1.isPresent());
        assertEquals(30, match1.get().handlerPc());

        Optional<ExceptionTableEntry> match2 = resolver.findHandler(frame, "com/custom/CustomError", 2);
        assertTrue(match2.isPresent());
        assertEquals(30, match2.get().handlerPc());
    }

    @Test
    @DisplayName("Exact catchType match resolves handler")
    void testExactCatchTypeMatch() {
        ConstantPool cp = createConstantPoolWithClasses("com/example/MyException");
        // class entry for com/example/MyException is at CP index 2
        ExceptionTableEntry handler = new ExceptionTableEntry(0, 10, 25, 2);
        Frame frame = createFrameWithExceptionTable(List.of(handler), cp);

        Optional<ExceptionTableEntry> match = resolver.findHandler(frame, "com/example/MyException", 0);
        assertTrue(match.isPresent());
        assertEquals(25, match.get().handlerPc());

        Optional<ExceptionTableEntry> noMatch = resolver.findHandler(frame, "com/example/OtherException", 0);
        assertTrue(noMatch.isEmpty());
    }

    @Test
    @DisplayName("Subclass exception caught by superclass catchType")
    void testSubclassCatchTypeMatch() {
        ConstantPool superCp = createConstantPoolWithClasses("com/example/SuperException", "java/lang/Exception");
        ClassFile superCf = createTestClass("com/example/SuperException", "java/lang/Exception", superCp);
        repository.register(superCf);

        ConstantPool subCp = createConstantPoolWithClasses("com/example/SubException", "com/example/SuperException");
        ClassFile subCf = createTestClass("com/example/SubException", "com/example/SuperException", subCp);
        repository.register(subCf);

        // Frame catch block catching SuperException (CP index 2 in superCp)
        ExceptionTableEntry handler = new ExceptionTableEntry(0, 10, 40, 2);
        Frame frame = createFrameWithExceptionTable(List.of(handler), superCp);

        // Throwing SubException should match SuperException catch block
        Optional<ExceptionTableEntry> match = resolver.findHandler(frame, "com/example/SubException", 5);
        assertTrue(match.isPresent());
        assertEquals(40, match.get().handlerPc());
    }

    @Test
    @DisplayName("First matching handler is chosen based on declaration order")
    void testFirstMatchingHandlerWins() {
        ConstantPool cp = createConstantPoolWithClasses(
                "com/example/SpecificException", // CP #2
                "com/example/GeneralException"   // CP #4
        );

        ExceptionTableEntry specificHandler = new ExceptionTableEntry(0, 20, 100, 2);
        ExceptionTableEntry generalHandler = new ExceptionTableEntry(0, 20, 200, 4);
        ExceptionTableEntry finallyHandler = new ExceptionTableEntry(0, 20, 300, 0);

        Frame frame = createFrameWithExceptionTable(
                List.of(specificHandler, generalHandler, finallyHandler),
                cp
        );

        // SpecificException matches first handler (100)
        Optional<ExceptionTableEntry> match1 = resolver.findHandler(frame, "com/example/SpecificException", 5);
        assertTrue(match1.isPresent());
        assertEquals(100, match1.get().handlerPc());

        // GeneralException matches second handler (200)
        Optional<ExceptionTableEntry> match2 = resolver.findHandler(frame, "com/example/GeneralException", 5);
        assertTrue(match2.isPresent());
        assertEquals(200, match2.get().handlerPc());

        // UnrelatedException falls through to finally handler (300)
        Optional<ExceptionTableEntry> match3 = resolver.findHandler(frame, "com/example/UnrelatedException", 5);
        assertTrue(match3.isPresent());
        assertEquals(300, match3.get().handlerPc());
    }

    @Test
    @DisplayName("Standard exception hierarchy: RuntimeException caught by Exception and Throwable")
    void testStandardHierarchyMatching() {
        assertTrue(resolver.isSubtypeOf("java/lang/RuntimeException", "java/lang/Exception"));
        assertTrue(resolver.isSubtypeOf("java/lang/RuntimeException", "java/lang/Throwable"));
        assertTrue(resolver.isSubtypeOf("java/lang/RuntimeException", "java/lang/Object"));
        assertTrue(resolver.isSubtypeOf("java/lang/Exception", "java/lang/Throwable"));
        assertTrue(resolver.isSubtypeOf("java/lang/Error", "java/lang/Throwable"));

        assertFalse(resolver.isSubtypeOf("java/lang/Exception", "java/lang/RuntimeException"));
        assertFalse(resolver.isSubtypeOf("java/lang/Throwable", "java/lang/Exception"));
    }
}
