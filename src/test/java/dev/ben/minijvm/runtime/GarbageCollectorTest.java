package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.*;
import dev.ben.minijvm.exception.StackFaultException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GarbageCollectorTest {

    private ClassRepository repository;
    private Heap heap;
    private GarbageCollector gc;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        heap = new Heap(repository);
        gc = new GarbageCollector();
    }

    private ClassFile createTestClass(String className, List<FieldInfo> fields, ConstantPool cp) {
        return new ClassFile(
                0, 65,
                cp,
                AccessFlags.ACC_PUBLIC,
                1,
                0,
                List.of(),
                fields,
                List.of(),
                List.of()
        );
    }

    private ConstantPool createClassConstantPool(String className) {
        return new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry(className)
        ));
    }

    private Frame createSimpleFrame(int maxLocals, int maxStack) {
        ConstantPool cp = createClassConstantPool("com/example/Caller");
        MethodInfo method = new MethodInfo(AccessFlags.ACC_PUBLIC, 2, 2, List.of(),
                new CodeAttribute(maxStack, maxLocals, new byte[]{0x00}, List.of(), List.of()));
        return new Frame(method, cp, maxLocals, maxStack);
    }

    @Test
    @DisplayName("Empty heap produces zero roots, zero marked, and zero reclaimed")
    void testCollectEmptyHeap() {
        GcResult result = heap.collectGarbage();

        assertEquals(0, result.rootsCount());
        assertEquals(0, result.markedCount());
        assertEquals(0, result.reclaimedCount());
        assertEquals(0, result.survivorsCount());
        assertFalse(result.hasReclaimed());
        assertTrue(result.reclaimedHandles().isEmpty());
        assertTrue(result.survivingHandles().isEmpty());
    }

    @Test
    @DisplayName("Unrooted single object is swept and deallocated from heap")
    void testReclaimUnrootedSingleObject() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference ref = heap.allocate(cf);
        assertEquals(1, heap.objectCount());
        assertTrue(heap.containsObject(ref.handle()));

        // Run GC without any roots
        GcResult result = heap.collectGarbage();

        assertEquals(0, result.rootsCount());
        assertEquals(0, result.markedCount());
        assertEquals(1, result.reclaimedCount());
        assertEquals(0, result.survivorsCount());
        assertTrue(result.hasReclaimed());
        assertEquals(Set.of(ref.handle()), result.reclaimedHandles());

        assertFalse(heap.containsObject(ref.handle()));
        assertEquals(0, heap.objectCount());
        assertThrows(StackFaultException.class, () -> heap.getObject(ref.handle()));
    }

    @Test
    @DisplayName("Object referenced by a local variable in an active frame is retained")
    void testRetainRootedLocalVariable() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference ref = heap.allocate(cf);
        Frame frame = createSimpleFrame(3, 2);
        frame.locals().setReference(1, ref);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        GcResult result = heap.collectGarbage(frameStack);

        assertEquals(1, result.rootsCount());
        assertEquals(1, result.markedCount());
        assertEquals(0, result.reclaimedCount());
        assertEquals(1, result.survivorsCount());
        assertFalse(result.hasReclaimed());
        assertEquals(Set.of(ref.handle()), result.survivingHandles());
        assertTrue(heap.containsObject(ref.handle()));
    }

    @Test
    @DisplayName("Object referenced on an operand stack is retained")
    void testRetainRootedOperandStack() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference ref = heap.allocate(cf);
        Frame frame = createSimpleFrame(2, 3);
        frame.operandStack().push(ref);

        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        GcResult result = heap.collectGarbage(frameStack);

        assertEquals(1, result.rootsCount());
        assertEquals(1, result.markedCount());
        assertEquals(0, result.reclaimedCount());
        assertEquals(1, result.survivorsCount());
        assertEquals(Set.of(ref.handle()), result.survivingHandles());
        assertTrue(heap.containsObject(ref.handle()));
    }

    @Test
    @DisplayName("Object referenced by a class static field is retained")
    void testRetainRootedStaticField() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference ref = heap.allocate(cf);
        FieldKey staticKey = new FieldKey("pkg/Holder", "instance", "Lpkg/Node;");
        heap.setStaticField(staticKey, ref);

        // Run GC with no active frames
        GcResult result = heap.collectGarbage();

        assertEquals(1, result.rootsCount());
        assertEquals(1, result.markedCount());
        assertEquals(0, result.reclaimedCount());
        assertEquals(1, result.survivorsCount());
        assertTrue(heap.containsObject(ref.handle()));

        // Overwrite static field with null and re-run GC
        heap.setStaticField(staticKey, Value.nullRef());
        GcResult secondResult = heap.collectGarbage();

        assertEquals(0, secondResult.rootsCount());
        assertEquals(0, secondResult.markedCount());
        assertEquals(1, secondResult.reclaimedCount());
        assertEquals(0, secondResult.survivorsCount());
        assertFalse(heap.containsObject(ref.handle()));
    }

    @Test
    @DisplayName("Transitive reachability through instance fields marks and retains nested objects")
    void testTransitiveReachabilityViaFields() {
        FieldInfo nextField = new FieldInfo(AccessFlags.ACC_PUBLIC, 3, 4, List.of());
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/LinkedList"),
                new ConstantPoolEntry.Utf8Entry("next"),
                new ConstantPoolEntry.Utf8Entry("Lpkg/LinkedList;")
        ));
        ClassFile cf = createTestClass("pkg/LinkedList", List.of(nextField), cp);
        repository.register(cf);

        FieldKey nextKey = new FieldKey("pkg/LinkedList", "next", "Lpkg/LinkedList;");

        // Chain: node1 -> node2 -> node3
        ObjectReference node1 = heap.allocate(cf);
        ObjectReference node2 = heap.allocate(cf);
        ObjectReference node3 = heap.allocate(cf);

        // Also allocate unreferenced node4
        ObjectReference node4 = heap.allocate(cf);

        heap.getObject(node1.handle()).setField(nextKey, node2);
        heap.getObject(node2.handle()).setField(nextKey, node3);

        // Root only points to node1 in a local variable
        Frame frame = createSimpleFrame(2, 2);
        frame.locals().setReference(0, node1);
        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        GcResult result = heap.collectGarbage(frameStack);

        assertEquals(1, result.rootsCount());
        assertEquals(3, result.markedCount());
        assertEquals(1, result.reclaimedCount());
        assertEquals(3, result.survivorsCount());

        assertTrue(result.reclaimedHandles().contains(node4.handle()));
        assertTrue(result.survivingHandles().containsAll(List.of(node1.handle(), node2.handle(), node3.handle())));

        assertTrue(heap.containsObject(node1.handle()));
        assertTrue(heap.containsObject(node2.handle()));
        assertTrue(heap.containsObject(node3.handle()));
        assertFalse(heap.containsObject(node4.handle()));
    }

    @Test
    @DisplayName("Transitive reachability through guest array elements marks and retains elements")
    void testTransitiveReachabilityViaArray() {
        ConstantPool cp = createClassConstantPool("pkg/Item");
        ClassFile cf = createTestClass("pkg/Item", List.of(), cp);
        repository.register(cf);

        ObjectReference item1 = heap.allocate(cf);
        ObjectReference item2 = heap.allocate(cf);
        ObjectReference unreferencedItem = heap.allocate(cf);

        // Allocate array of [Lpkg/Item; of size 2
        ObjectReference arrayRef = heap.allocateArray("[Lpkg/Item;", 2);
        GuestArray array = heap.getArray(arrayRef.handle());
        array.set(0, item1);
        array.set(1, item2);

        // Root points only to arrayRef
        Frame frame = createSimpleFrame(1, 1);
        frame.locals().setReference(0, arrayRef);
        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        GcResult result = heap.collectGarbage(frameStack);

        assertEquals(1, result.rootsCount()); // arrayRef
        assertEquals(3, result.markedCount()); // arrayRef, item1, item2
        assertEquals(1, result.reclaimedCount()); // unreferencedItem
        assertEquals(3, result.survivorsCount());

        assertTrue(heap.containsObject(arrayRef.handle()));
        assertTrue(heap.containsObject(item1.handle()));
        assertTrue(heap.containsObject(item2.handle()));
        assertFalse(heap.containsObject(unreferencedItem.handle()));
    }

    @Test
    @DisplayName("Cyclic object graph (A -> B -> A) is preserved when rooted")
    void testCyclicObjectGraphRetainedWhenRooted() {
        FieldInfo peerField = new FieldInfo(AccessFlags.ACC_PUBLIC, 3, 4, List.of());
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Peer"),
                new ConstantPoolEntry.Utf8Entry("peer"),
                new ConstantPoolEntry.Utf8Entry("Lpkg/Peer;")
        ));
        ClassFile cf = createTestClass("pkg/Peer", List.of(peerField), cp);
        repository.register(cf);

        FieldKey peerKey = new FieldKey("pkg/Peer", "peer", "Lpkg/Peer;");

        ObjectReference a = heap.allocate(cf);
        ObjectReference b = heap.allocate(cf);

        heap.getObject(a.handle()).setField(peerKey, b);
        heap.getObject(b.handle()).setField(peerKey, a);

        Frame frame = createSimpleFrame(1, 1);
        frame.locals().setReference(0, a);
        FrameStack frameStack = new FrameStack();
        frameStack.push(frame);

        GcResult result = heap.collectGarbage(frameStack);

        assertEquals(1, result.rootsCount());
        assertEquals(2, result.markedCount());
        assertEquals(0, result.reclaimedCount());
        assertEquals(2, result.survivorsCount());

        assertTrue(heap.containsObject(a.handle()));
        assertTrue(heap.containsObject(b.handle()));
    }

    @Test
    @DisplayName("Cyclic island (A -> B -> A) with no external roots is completely reclaimed")
    void testCyclicIslandReclaimedWhenUnrooted() {
        FieldInfo peerField = new FieldInfo(AccessFlags.ACC_PUBLIC, 3, 4, List.of());
        ConstantPool cp = new ConstantPool(List.of(
                new ConstantPoolEntry.UnusableEntry("Slot 0"),
                new ConstantPoolEntry.ClassEntry(2),
                new ConstantPoolEntry.Utf8Entry("pkg/Peer"),
                new ConstantPoolEntry.Utf8Entry("peer"),
                new ConstantPoolEntry.Utf8Entry("Lpkg/Peer;")
        ));
        ClassFile cf = createTestClass("pkg/Peer", List.of(peerField), cp);
        repository.register(cf);

        FieldKey peerKey = new FieldKey("pkg/Peer", "peer", "Lpkg/Peer;");

        ObjectReference a = heap.allocate(cf);
        ObjectReference b = heap.allocate(cf);

        heap.getObject(a.handle()).setField(peerKey, b);
        heap.getObject(b.handle()).setField(peerKey, a);

        // GC with empty FrameStack
        FrameStack frameStack = new FrameStack();
        GcResult result = heap.collectGarbage(frameStack);

        assertEquals(0, result.rootsCount());
        assertEquals(0, result.markedCount());
        assertEquals(2, result.reclaimedCount());
        assertEquals(0, result.survivorsCount());

        assertFalse(heap.containsObject(a.handle()));
        assertFalse(heap.containsObject(b.handle()));
        assertEquals(0, heap.objectCount());
    }

    @Test
    @DisplayName("Multi-dimensional array hierarchy is preserved when rooted and completely reclaimed when unrooted")
    void testMultiDimensionalArrayReclamation() {
        // Allocate 2x3 2D int array: [[I
        ObjectReference multiArray = heap.allocateMultiArray("[[I", new int[]{2, 3});
        // 1 outer array + 2 sub-arrays = 3 total objects in heap
        assertEquals(3, heap.objectCount());

        // 1. Rooted: survives
        Frame frame = createSimpleFrame(1, 1);
        frame.locals().setReference(0, multiArray);
        GcResult rootedResult = heap.collectGarbage(List.of(frame));

        assertEquals(1, rootedResult.rootsCount());
        assertEquals(3, rootedResult.markedCount());
        assertEquals(0, rootedResult.reclaimedCount());
        assertEquals(3, rootedResult.survivorsCount());

        // 2. Clear root: all 3 arrays are swept
        frame.locals().set(0, Value.nullRef());
        GcResult unrootedResult = heap.collectGarbage(List.of(frame));

        assertEquals(0, unrootedResult.rootsCount());
        assertEquals(0, unrootedResult.markedCount());
        assertEquals(3, unrootedResult.reclaimedCount());
        assertEquals(0, unrootedResult.survivorsCount());
        assertEquals(0, heap.objectCount());
    }

    @Test
    @DisplayName("Multi-frame root resolution scans across caller and callee frames")
    void testMultiFrameRootScanning() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference objCaller = heap.allocate(cf);
        ObjectReference objCalleeLocals = heap.allocate(cf);
        ObjectReference objCalleeStack = heap.allocate(cf);
        ObjectReference deadObj = heap.allocate(cf);

        Frame callerFrame = createSimpleFrame(2, 2);
        callerFrame.locals().setReference(0, objCaller);

        Frame calleeFrame = createSimpleFrame(2, 2);
        calleeFrame.locals().setReference(1, objCalleeLocals);
        calleeFrame.operandStack().push(objCalleeStack);

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);
        stack.push(calleeFrame);

        GcResult result = heap.collectGarbage(stack);

        assertEquals(3, result.rootsCount());
        assertEquals(3, result.markedCount());
        assertEquals(1, result.reclaimedCount());
        assertEquals(3, result.survivorsCount());

        assertTrue(result.reclaimedHandles().contains(deadObj.handle()));
        assertTrue(result.survivingHandles().containsAll(List.of(
                objCaller.handle(), objCalleeLocals.handle(), objCalleeStack.handle()
        )));

        // Callee pops, only caller survives
        stack.pop();
        GcResult secondResult = heap.collectGarbage(stack);

        assertEquals(1, secondResult.rootsCount());
        assertEquals(1, secondResult.markedCount());
        assertEquals(2, secondResult.reclaimedCount()); // objCalleeLocals and objCalleeStack reclaimed
        assertEquals(1, secondResult.survivorsCount());
        assertTrue(heap.containsObject(objCaller.handle()));
    }

    @Test
    @DisplayName("Frame with pending return value retains referenced object")
    void testReturnValueAsRoot() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference retRef = heap.allocate(cf);
        Frame frame = createSimpleFrame(1, 1);
        frame.setReturnValue(retRef);

        GcResult result = heap.collectGarbage(List.of(frame));
        assertEquals(1, result.rootsCount());
        assertEquals(1, result.markedCount());
        assertEquals(0, result.reclaimedCount());
        assertTrue(heap.containsObject(retRef.handle()));
    }

    @Test
    @DisplayName("Deterministic execution produces identical results across multiple runs")
    void testDeterministicReclamation() {
        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        ObjectReference live = heap.allocate(cf);
        ObjectReference dead1 = heap.allocate(cf);
        ObjectReference dead2 = heap.allocate(cf);

        Frame frame = createSimpleFrame(1, 1);
        frame.locals().setReference(0, live);

        GcResult run1 = heap.collectGarbage(List.of(frame));
        assertEquals(Set.of(dead1.handle(), dead2.handle()), run1.reclaimedHandles());
        assertEquals(Set.of(live.handle()), run1.survivingHandles());

        // Subsequent run with same state produces 0 reclaimed and exact same survivor
        GcResult run2 = heap.collectGarbage(List.of(frame));
        assertEquals(0, run2.reclaimedCount());
        assertEquals(Set.of(live.handle()), run2.survivingHandles());
    }

    @Test
    @DisplayName("GC trace listener observes root resolution, mark, and sweep phases")
    void testGcTraceListener() {
        List<String> traces = new ArrayList<>();
        GarbageCollector traceGc = new GarbageCollector(traces::add);

        ConstantPool cp = createClassConstantPool("pkg/Node");
        ClassFile cf = createTestClass("pkg/Node", List.of(), cp);
        repository.register(cf);

        heap.allocate(cf);
        GcResult result = traceGc.run(heap, Collections.emptyList());

        assertEquals(1, result.reclaimedCount());
        assertFalse(traces.isEmpty());
        assertTrue(traces.stream().anyMatch(t -> t.contains("Starting GC cycle")));
        assertTrue(traces.stream().anyMatch(t -> t.contains("Root resolution identified")));
        assertTrue(traces.stream().anyMatch(t -> t.contains("Mark phase traversed")));
        assertTrue(traces.stream().anyMatch(t -> t.contains("Sweep phase deallocated")));
        assertTrue(traces.stream().anyMatch(t -> t.contains("GC cycle completed")));
    }
}
