package dev.ben.minijvm.runtime;

import java.util.*;
import java.util.function.Consumer;

/**
 * Explicit, deterministic mark-and-sweep garbage collector for the guest Heap (ADR-006, ADR-014).
 * Scans roots from the active call stack (local variables, operand stacks, return values)
 * and the class-level static field registry, performs reachability traversal across guest
 * object fields and array elements, and sweeps unreachable handles to reclaim heap capacity.
 */
public final class GarbageCollector {

    private final Consumer<String> traceListener;

    public GarbageCollector() {
        this(null);
    }

    public GarbageCollector(Consumer<String> traceListener) {
        this.traceListener = traceListener;
    }

    private void trace(String message) {
        if (traceListener != null) {
            traceListener.accept(message);
        }
    }

    /**
     * Executes a deterministic mark-and-sweep GC cycle over the specified heap using
     * roots discovered from the provided FrameStack and class static fields.
     */
    public GcResult run(Heap heap, FrameStack frameStack) {
        return run(heap, frameStack != null ? frameStack.toList() : Collections.emptyList());
    }

    /**
     * Executes a deterministic mark-and-sweep GC cycle over the specified heap using
     * roots discovered from the provided frames and class static fields.
     */
    public GcResult run(Heap heap, Collection<Frame> frames) {
        Objects.requireNonNull(heap, "Heap cannot be null");
        Collection<Frame> activeFrames = frames != null ? frames : Collections.emptyList();

        trace("Starting GC cycle. Initial heap object count: " + heap.objectCount());

        // 1. Root Resolution Phase
        Set<Long> roots = resolveRoots(heap, activeFrames);
        trace(String.format("Root resolution identified %d root handle(s): %s", roots.size(), roots));

        // 2. Mark Phase (Object Graph Traversal)
        Set<Long> marked = markReachableObjects(heap, roots);
        trace(String.format("Mark phase traversed %d reachable handle(s): %s", marked.size(), marked));

        // 3. Sweep Phase (Memory Reclamation)
        Set<Long> reclaimed = heap.sweep(marked);
        trace(String.format("Sweep phase deallocated %d unreachable handle(s): %s", reclaimed.size(), reclaimed));

        GcResult result = new GcResult(
                roots.size(),
                marked.size(),
                reclaimed.size(),
                heap.objectCount(),
                reclaimed,
                marked
        );

        trace("GC cycle completed: " + result);
        return result;
    }

    /**
     * Resolves all valid GC root handles from static fields and active stack frames.
     */
    public Set<Long> resolveRoots(Heap heap, Collection<Frame> frames) {
        Objects.requireNonNull(heap, "Heap cannot be null");
        Set<Long> roots = new LinkedHashSet<>();

        // 1a. Scan class static fields
        for (Value val : heap.staticFields().values()) {
            inspectValueForRoot(val, heap, roots);
        }

        // 1b. Scan active stack frames
        if (frames != null) {
            for (Frame frame : frames) {
                if (frame == null) {
                    continue;
                }

                // Scan local variables
                for (Value val : frame.locals().activeValues()) {
                    inspectValueForRoot(val, heap, roots);
                }

                // Scan operand stack
                for (Value val : frame.operandStack().toList()) {
                    inspectValueForRoot(val, heap, roots);
                }

                // Scan return value (if frame completed with a return value awaiting retrieval)
                if (frame.returnValue().isPresent()) {
                    inspectValueForRoot(frame.returnValue().get(), heap, roots);
                }
            }
        }

        return Collections.unmodifiableSet(roots);
    }

    private void inspectValueForRoot(Value val, Heap heap, Set<Long> roots) {
        if (val instanceof ObjectReference ref) {
            long handle = ref.handle();
            if (heap.containsObject(handle)) {
                roots.add(handle);
            }
        }
    }

    /**
     * Traverses the guest object reference graph starting from the specified root handles.
     * Correctly visits instance fields of GuestObject and elements of GuestArray.
     */
    public Set<Long> markReachableObjects(Heap heap, Set<Long> roots) {
        Objects.requireNonNull(heap, "Heap cannot be null");
        Objects.requireNonNull(roots, "Roots cannot be null");

        Set<Long> marked = new LinkedHashSet<>();
        Deque<Long> worklist = new ArrayDeque<>();

        for (Long rootHandle : roots) {
            if (rootHandle != null && heap.containsObject(rootHandle)) {
                if (marked.add(rootHandle)) {
                    worklist.add(rootHandle);
                }
            }
        }

        while (!worklist.isEmpty()) {
            long handle = worklist.poll();
            GuestObject obj = heap.findObject(handle).orElse(null);
            if (obj == null) {
                continue;
            }

            // Inspect instance fields
            for (Value fieldVal : obj.fields().values()) {
                inspectValueForMark(fieldVal, heap, marked, worklist);
            }

            // Inspect array elements if the object is a GuestArray
            if (obj instanceof GuestArray array) {
                for (Value elementVal : array.elements()) {
                    inspectValueForMark(elementVal, heap, marked, worklist);
                }
            }
        }

        return Collections.unmodifiableSet(marked);
    }

    private void inspectValueForMark(Value val, Heap heap, Set<Long> marked, Deque<Long> worklist) {
        if (val instanceof ObjectReference ref) {
            long handle = ref.handle();
            if (heap.containsObject(handle)) {
                if (marked.add(handle)) {
                    worklist.add(handle);
                }
            }
        }
    }

    // Static convenience methods

    public static GcResult collect(Heap heap, FrameStack frameStack) {
        return new GarbageCollector().run(heap, frameStack);
    }

    public static GcResult collect(Heap heap, Collection<Frame> frames) {
        return new GarbageCollector().run(heap, frames);
    }

    public static GcResult collect(Heap heap, Frame frame) {
        return new GarbageCollector().run(heap, frame != null ? List.of(frame) : Collections.emptyList());
    }

    public static GcResult collect(Heap heap) {
        return new GarbageCollector().run(heap, Collections.emptyList());
    }
}
