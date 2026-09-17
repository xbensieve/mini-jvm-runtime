package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GcIntegrationTest {

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

    private ClassFile createClass(String className, List<FieldInfo> fields, List<MethodInfo> methods, ConstantPool cp) {
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
    @DisplayName("Interpreter execution drops object reference in local variable; GC reclaims de-referenced object")
    void testLocalVariableDereferenceReclamation() {
        // CP:
        // #1 Class "pkg/Widget"
        // #2 Utf8 "pkg/Widget"
        // #3 Utf8 "run"
        // #4 Utf8 "()V"
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Widget"));
        entries.add(new ConstantPoolEntry.Utf8Entry("run"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        ConstantPool cp = new ConstantPool(entries);

        // Bytecode:
        // 0: new #1 (allocates obj1 @1)
        // 3: astore_1
        // 4: new #1 (allocates obj2 @2)
        // 7: astore_2
        // 8: aconst_null
        // 9: astore_1 (drops obj1 reference)
        // 10: return
        byte[] code = new byte[]{
                (byte) 0xBB, 0x00, 0x01, // new #1
                0x4C,                    // astore_1
                (byte) 0xBB, 0x00, 0x01, // new #1
                0x4D,                    // astore_2
                0x01,                    // aconst_null
                0x4C,                    // astore_1
                (byte) 0xB1              // return
        };

        CodeAttribute codeAttr = new CodeAttribute(3, 3, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createClass("pkg/Widget", List.of(), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        // Step up to PC = 8 (both objects allocated, stored in locals 1 and 2)
        while (frame.pc() < 8) {
            interpreter.step(frame, frameStack);
        }

        assertEquals(2, heap.objectCount());
        GcResult midGc = interpreter.collectGarbage(frameStack);
        assertEquals(2, midGc.rootsCount());
        assertEquals(0, midGc.reclaimedCount());
        assertEquals(2, midGc.survivorsCount());

        // Step through aconst_null and astore_1 (PC advances past 9 to 10)
        interpreter.step(frame, frameStack); // aconst_null
        interpreter.step(frame, frameStack); // astore_1

        // Now local 1 is null, local 2 still holds obj2 (@2)
        GcResult afterDropGc = interpreter.collectGarbage(frameStack);
        assertEquals(1, afterDropGc.rootsCount());
        assertEquals(1, afterDropGc.markedCount());
        assertEquals(1, afterDropGc.reclaimedCount());
        assertEquals(Set.of(1L), afterDropGc.reclaimedHandles());
        assertEquals(Set.of(2L), afterDropGc.survivingHandles());

        assertFalse(heap.containsObject(1L));
        assertTrue(heap.containsObject(2L));
        assertThrows(StackFaultException.class, () -> heap.getObject(1L));
    }

    @Test
    @DisplayName("Temporary object allocated in callee frame is reclaimed after callee returns")
    void testCalleeTemporaryObjectReclaimedOnReturn() {
        // CP:
        // #1 Class "pkg/Helper"
        // #2 Utf8 "pkg/Helper"
        // #3 Utf8 "callee"
        // #4 Utf8 "()I"
        // #5 Utf8 "caller"
        // #6 Utf8 "()I"
        // #7 Methodref #1, #8 ("callee:()I")
        // #8 NameAndType #3, #4
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Helper"));                 // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("callee"));                     // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                        // #4
        entries.add(new ConstantPoolEntry.Utf8Entry("caller"));                     // #5
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                        // #6
        entries.add(new ConstantPoolEntry.MethodRefEntry(1, 8));                     // #7
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(3, 4));                  // #8
        ConstantPool cp = new ConstantPool(entries);

        // Callee bytecode:
        // 0: new #1 (allocates temp object)
        // 3: astore_0
        // 4: iconst_5
        // 5: ireturn
        byte[] calleeCode = new byte[]{
                (byte) 0xBB, 0x00, 0x01,
                0x4B,
                0x08,
                (byte) 0xAC
        };

        // Caller bytecode:
        // 0: invokestatic #7
        // 3: ireturn
        byte[] callerCode = new byte[]{
                (byte) 0xB8, 0x00, 0x07,
                (byte) 0xAC
        };

        MethodInfo calleeMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(),
                new CodeAttribute(2, 2, calleeCode, List.of(), List.of())
        );

        MethodInfo callerMethod = new MethodInfo(
                AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 5, 6, List.of(),
                new CodeAttribute(2, 1, callerCode, List.of(), List.of())
        );

        ClassFile cf = createClass("pkg/Helper", List.of(), List.of(calleeMethod, callerMethod), cp);
        repository.register(cf);

        Frame callerFrame = new Frame(cf, callerMethod);
        FrameStack frameStack = new FrameStack();
        frameStack.push(callerFrame);

        // Step invokestatic -> pushes calleeFrame
        interpreter.step(callerFrame, frameStack);
        assertEquals(2, frameStack.depth());

        Frame calleeFrame = frameStack.current();
        // Step callee new & astore_0
        interpreter.step(calleeFrame, frameStack); // new
        interpreter.step(calleeFrame, frameStack); // astore_0
        assertEquals(1, heap.objectCount());

        // While callee is active, GC retains the object
        GcResult duringCalleeGc = interpreter.collectGarbage(frameStack);
        assertEquals(1, duringCalleeGc.markedCount());
        assertEquals(0, duringCalleeGc.reclaimedCount());

        // Execute callee to completion (iconst_5, ireturn)
        interpreter.step(calleeFrame, frameStack); // iconst_5
        interpreter.step(calleeFrame, frameStack); // ireturn
        // Callee returned, value passed to caller operand stack, callee frame popped
        assertEquals(1, frameStack.depth());
        assertEquals(callerFrame, frameStack.current());
        assertEquals(Value.ofInt(5), callerFrame.operandStack().peek());

        // Callee frame is gone; its local variable was popped. Object is now dead!
        GcResult afterReturnGc = interpreter.collectGarbage(frameStack);
        assertEquals(0, afterReturnGc.rootsCount());
        assertEquals(0, afterReturnGc.markedCount());
        assertEquals(1, afterReturnGc.reclaimedCount());
        assertEquals(0, afterReturnGc.survivorsCount());
        assertEquals(0, heap.objectCount());
    }

    @Test
    @DisplayName("Nested object referenced only through an array element survives, and is swept when array reference is cleared")
    void testArrayElementTransitiveReachabilityInInterpreter() {
        // CP:
        // #1 Class "pkg/Payload"
        // #2 Utf8 "pkg/Payload"
        // #3 Utf8 "run"
        // #4 Utf8 "()V"
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Payload"));
        entries.add(new ConstantPoolEntry.Utf8Entry("run"));
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));
        ConstantPool cp = new ConstantPool(entries);

        // Bytecode:
        // 0: iconst_1
        // 1: anewarray #1 (creates GuestArray @1 of length 1)
        // 4: astore_1     (locals[1] = array @1)
        // 5: new #1       (creates GuestObject @2)
        // 8: astore_2     (locals[2] = object @2)
        // 9: aload_1
        // 10: iconst_0
        // 11: aload_2
        // 12: aastore     (array[0] = object @2)
        // 13: aconst_null
        // 14: astore_2    (locals[2] = null; object @2 reachable ONLY via array[0])
        // 15: return
        byte[] code = new byte[]{
                0x04,                               // iconst_1
                (byte) 0xBD, 0x00, 0x01,            // anewarray #1
                0x4C,                               // astore_1
                (byte) 0xBB, 0x00, 0x01,            // new #1
                0x4D,                               // astore_2
                0x2B,                               // aload_1
                0x03,                               // iconst_0
                0x2C,                               // aload_2
                0x53,                               // aastore
                0x01,                               // aconst_null
                0x4D,                               // astore_2
                (byte) 0xB1                         // return
        };

        CodeAttribute codeAttr = new CodeAttribute(3, 3, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of(), codeAttr);
        ClassFile cf = createClass("pkg/Payload", List.of(), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        FrameStack stack = new FrameStack();
        stack.push(frame);

        // Step up to PC = 15 (after locals[2] = null)
        while (frame.pc() < 15) {
            interpreter.step(frame, stack);
        }

        // Local 1 has array @1; array[0] holds object @2; local 2 is null
        assertEquals(2, heap.objectCount());
        GcResult gc1 = interpreter.collectGarbage(stack);

        // array is root -> traverses to object @2 -> both survive
        assertEquals(1, gc1.rootsCount()); // array @1
        assertEquals(2, gc1.markedCount()); // array @1 and object @2
        assertEquals(0, gc1.reclaimedCount());
        assertEquals(2, gc1.survivorsCount());
        assertTrue(heap.containsObject(1L));
        assertTrue(heap.containsObject(2L));

        // Now overwrite local 1 with null (dropping the array root)
        frame.locals().set(1, Value.nullRef());
        GcResult gc2 = interpreter.collectGarbage(stack);

        // Both array @1 and nested object @2 are swept!
        assertEquals(0, gc2.rootsCount());
        assertEquals(0, gc2.markedCount());
        assertEquals(2, gc2.reclaimedCount());
        assertEquals(0, gc2.survivorsCount());
        assertFalse(heap.containsObject(1L));
        assertFalse(heap.containsObject(2L));
        assertEquals(0, heap.objectCount());
    }

    @Test
    @DisplayName("Static field root holds object alive through interpreter execution, swept when reset to null")
    void testStaticFieldRootAcrossExecution() {
        FieldInfo staticFld = new FieldInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 3, 4, List.of());
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/StaticOwner"));            // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("globalObj"));                  // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("Lpkg/StaticOwner;"));          // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                      // #5
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(3, 4));                  // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("setup"));                      // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("()V"));                        // #8
        ConstantPool cp = new ConstantPool(entries);

        // Bytecode:
        // 0: new #1
        // 3: putstatic #5
        // 6: return
        byte[] code = new byte[]{
                (byte) 0xBB, 0x00, 0x01,
                (byte) 0xB3, 0x00, 0x05,
                (byte) 0xB1
        };

        CodeAttribute codeAttr = new CodeAttribute(2, 1, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 7, 8, List.of(), codeAttr);
        ClassFile cf = createClass("pkg/StaticOwner", List.of(staticFld), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        interpreter.execute(frame);

        // Frame is finished and discarded; only static field holds object @1
        assertEquals(1, heap.objectCount());
        GcResult gcNoFrames = interpreter.collectGarbage();

        assertEquals(1, gcNoFrames.rootsCount());
        assertEquals(1, gcNoFrames.markedCount());
        assertEquals(0, gcNoFrames.reclaimedCount());
        assertEquals(1, gcNoFrames.survivorsCount());
        assertTrue(heap.containsObject(1L));

        // Clear static field and re-run GC
        FieldKey key = new FieldKey("pkg/StaticOwner", "globalObj", "Lpkg/StaticOwner;");
        heap.setStaticField(key, Value.nullRef());

        GcResult gcAfterReset = interpreter.collectGarbage();
        assertEquals(0, gcAfterReset.rootsCount());
        assertEquals(1, gcAfterReset.reclaimedCount());
        assertEquals(0, heap.objectCount());
    }

    @Test
    @DisplayName("Interpreter continues execution and mutates surviving object after GC cycle")
    void testExecutionContinuesAfterGc() {
        FieldInfo valueField = new FieldInfo(AccessFlags.ACC_PUBLIC, 3, 4, List.of());
        List<ConstantPoolEntry> entries = new ArrayList<>();
        entries.add(new ConstantPoolEntry.UnusableEntry("slot 0"));
        entries.add(new ConstantPoolEntry.ClassEntry(2));                           // #1
        entries.add(new ConstantPoolEntry.Utf8Entry("pkg/Counter"));                // #2
        entries.add(new ConstantPoolEntry.Utf8Entry("count"));                      // #3
        entries.add(new ConstantPoolEntry.Utf8Entry("I"));                          // #4
        entries.add(new ConstantPoolEntry.FieldRefEntry(1, 6));                      // #5
        entries.add(new ConstantPoolEntry.NameAndTypeEntry(3, 4));                  // #6
        entries.add(new ConstantPoolEntry.Utf8Entry("run"));                        // #7
        entries.add(new ConstantPoolEntry.Utf8Entry("()I"));                        // #8
        ConstantPool cp = new ConstantPool(entries);

        // Bytecode:
        // 0: new #1 (allocated dead @1)
        // 3: pop
        // 4: new #1 (allocated live @2)
        // 7: astore_1
        // 8: aload_1
        // 9: bipush 42
        // 11: putfield #5
        // 14: aload_1
        // 15: getfield #5
        // 18: ireturn
        byte[] code = new byte[]{
                (byte) 0xBB, 0x00, 0x01, // new dead @1
                0x57,                    // pop
                (byte) 0xBB, 0x00, 0x01, // new live @2
                0x4C,                    // astore_1
                0x2B,                    // aload_1
                0x10, 0x2A,              // bipush 42
                (byte) 0xB5, 0x00, 0x05, // putfield #5
                0x2B,                    // aload_1
                (byte) 0xB4, 0x00, 0x05, // getfield #5
                (byte) 0xAC              // ireturn
        };

        CodeAttribute codeAttr = new CodeAttribute(3, 2, code, List.of(), List.of());
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC | AccessFlags.ACC_STATIC, 7, 8, List.of(), codeAttr);
        ClassFile cf = createClass("pkg/Counter", List.of(valueField), List.of(method), cp);
        repository.register(cf);

        Frame frame = new Frame(cf, method);
        FrameStack stack = new FrameStack();
        stack.push(frame);

        // Step until PC = 8 (dead @1 popped, live @2 in local 1)
        while (frame.pc() < 8) {
            interpreter.step(frame, stack);
        }

        assertEquals(2, heap.objectCount());

        // Run GC explicitly mid-execution
        GcResult gcResult = interpreter.collectGarbage(stack);
        assertEquals(1, gcResult.rootsCount());
        assertEquals(1, gcResult.markedCount());
        assertEquals(1, gcResult.reclaimedCount());
        assertEquals(Set.of(1L), gcResult.reclaimedHandles());
        assertEquals(Set.of(2L), gcResult.survivingHandles());
        assertEquals(1, heap.objectCount());

        // Resume execution to completion: stores 42 in surviving object and returns 42
        while (!frame.isReturned()) {
            interpreter.step(frame, stack);
        }

        assertTrue(frame.returnValue().isPresent());
        assertEquals(Value.ofInt(42), frame.returnValue().get());

        // Surviving object still contains the mutated field
        FieldKey key = new FieldKey("pkg/Counter", "count", "I");
        assertEquals(Value.ofInt(42), heap.getObject(2L).getField(key));
    }
}
