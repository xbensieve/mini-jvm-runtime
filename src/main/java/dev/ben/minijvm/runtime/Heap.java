package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.AccessFlags;
import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.FieldInfo;
import dev.ben.minijvm.exception.StackFaultException;

import java.util.*;

/**
 * Deterministic educational Heap managing guest runtime memory (ADR-005, ADR-012).
 * Owns instance object allocation, monotonic unique handle generation,
 * and class static field storage.
 */
public final class Heap {

    private final ClassRepository classRepository;
    private long nextHandle = 1L;
    private final Map<Long, GuestObject> objects = new HashMap<>();
    private final Map<FieldKey, Value> staticFields = new HashMap<>();

    public Heap(ClassRepository classRepository) {
        this.classRepository = Objects.requireNonNull(classRepository, "classRepository cannot be null");
    }

    public ClassRepository classRepository() {
        return classRepository;
    }

    /**
     * Allocates a new guest object of the specified class.
     * Collects all instance fields across the class hierarchy (from superclasses down to this class)
     * and initializes them to their JVM default values.
     *
     * @param classFile The runtime class of the new object.
     * @return An ObjectReference holding the allocated handle and runtime class name.
     */
    public ObjectReference allocate(ClassFile classFile) {
        Objects.requireNonNull(classFile, "classFile cannot be null");

        Map<FieldKey, Value> initialFields = new HashMap<>();

        // Collect all instance fields from class hierarchy
        ClassFile current = classFile;
        while (current != null) {
            String declaringClassName = current.thisClassName();
            for (FieldInfo field : current.fields()) {
                if (!AccessFlags.isStatic(field.accessFlags())) {
                    String name = field.name(current.constantPool());
                    String desc = field.descriptor(current.constantPool());
                    FieldKey key = new FieldKey(declaringClassName, name, desc);
                    // Avoid re-initializing if shadowed field already added from subclass
                    initialFields.putIfAbsent(key, defaultValueForDescriptor(desc));
                }
            }

            if (current.superClassName().isPresent()) {
                String superName = current.superClassName().get();
                current = classRepository.findClass(superName).orElse(null);
            } else {
                current = null;
            }
        }

        long handle = nextHandle++;
        GuestObject guestObject = new GuestObject(handle, classFile, initialFields);
        objects.put(handle, guestObject);

        return new ObjectReference(handle, classFile.thisClassName());
    }

    /**
     * Allocates a new guest array of the specified array type descriptor and length.
     *
     * @param arrayDescriptor The array type descriptor (e.g. "[I", "[Ljava/lang/String;").
     * @param length          The non-negative array length.
     * @return An ObjectReference holding the allocated handle and array type descriptor.
     */
    public ObjectReference allocateArray(String arrayDescriptor, int length) {
        Objects.requireNonNull(arrayDescriptor, "arrayDescriptor cannot be null");
        if (length < 0) {
            throw new StackFaultException("Negative array size: " + length);
        }

        long handle = nextHandle++;
        GuestArray array = new GuestArray(handle, arrayDescriptor, length);
        objects.put(handle, array);

        return array.toReference();
    }

    /**
     * Recursively allocates a multi-dimensional array according to the specified dimensions array.
     *
     * @param arrayDescriptor The multi-dimensional array descriptor (e.g. "[[I").
     * @param dimensions      Array of non-negative dimension sizes (length >= 1).
     * @return An ObjectReference pointing to the root array.
     */
    public ObjectReference allocateMultiArray(String arrayDescriptor, int[] dimensions) {
        Objects.requireNonNull(arrayDescriptor, "arrayDescriptor cannot be null");
        Objects.requireNonNull(dimensions, "dimensions cannot be null");
        if (dimensions.length == 0) {
            throw new IllegalArgumentException("dimensions cannot be empty");
        }
        for (int dim : dimensions) {
            if (dim < 0) {
                throw new StackFaultException("Negative array size: " + dim);
            }
        }

        return allocateMultiArrayHelper(arrayDescriptor, dimensions, 0);
    }

    private ObjectReference allocateMultiArrayHelper(String arrayDescriptor, int[] dimensions, int depth) {
        int length = dimensions[depth];
        ObjectReference arrayRef = allocateArray(arrayDescriptor, length);

        if (depth < dimensions.length - 1) {
            GuestArray currentArray = getArray(arrayRef.handle());
            String subDescriptor = arrayDescriptor.substring(1);
            for (int i = 0; i < length; i++) {
                ObjectReference subArrayRef = allocateMultiArrayHelper(subDescriptor, dimensions, depth + 1);
                currentArray.set(i, subArrayRef);
            }
        }

        return arrayRef;
    }

    /**
     * Retrieves an array by handle, validating that the object is indeed a GuestArray.
     *
     * @param handle Guest handle.
     * @return The GuestArray instance.
     * @throws StackFaultException if handle is invalid or does not correspond to an array.
     */
    public GuestArray getArray(long handle) {
        GuestObject obj = getObject(handle);
        if (!(obj instanceof GuestArray array)) {
            throw new StackFaultException("Guest object @" + handle + " is not an array: " + obj.runtimeClassName());
        }
        return array;
    }

    /**
     * Retrieves an object by handle.
     *
     * @param handle Guest handle.
     * @return The GuestObject instance.
     * @throws StackFaultException if the handle does not correspond to an allocated object.
     */
    public GuestObject getObject(long handle) {
        GuestObject obj = objects.get(handle);
        if (obj == null) {
            throw new StackFaultException("Invalid or dangling guest object handle: @" + handle);
        }
        return obj;
    }

    public Optional<GuestObject> findObject(long handle) {
        return Optional.ofNullable(objects.get(handle));
    }

    public boolean containsObject(long handle) {
        return objects.containsKey(handle);
    }

    public int objectCount() {
        return objects.size();
    }

    /**
     * Returns an unmodifiable view of all currently allocated guest objects and arrays keyed by handle.
     */
    public Map<Long, GuestObject> objects() {
        return Collections.unmodifiableMap(objects);
    }

    /**
     * Returns an unmodifiable snapshot set of all currently allocated guest object handles.
     */
    public Set<Long> allocatedHandles() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(objects.keySet()));
    }

    /**
     * Returns an unmodifiable view of the class-level static field registry.
     */
    public Map<FieldKey, Value> staticFields() {
        return Collections.unmodifiableMap(staticFields);
    }

    /**
     * Explicitly deallocates a guest object or array by handle.
     *
     * @param handle Guest handle to remove.
     * @return true if the object was resident and removed, false otherwise.
     */
    public boolean removeObject(long handle) {
        return objects.remove(handle) != null;
    }

    /**
     * Sweeps the heap by removing all objects whose handles are not present in the specified live set.
     *
     * @param liveHandles The set of reachable handles to retain.
     * @return The unmodifiable set of reclaimed handles.
     */
    public Set<Long> sweep(Set<Long> liveHandles) {
        Objects.requireNonNull(liveHandles, "liveHandles cannot be null");
        Set<Long> reclaimed = new LinkedHashSet<>();
        Iterator<Map.Entry<Long, GuestObject>> it = objects.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, GuestObject> entry = it.next();
            if (!liveHandles.contains(entry.getKey())) {
                reclaimed.add(entry.getKey());
                it.remove();
            }
        }
        return Collections.unmodifiableSet(reclaimed);
    }

    /**
     * Triggers explicit mark-and-sweep garbage collection over this heap using roots
     * from the active FrameStack and class static fields.
     */
    public GcResult collectGarbage(FrameStack frameStack) {
        return GarbageCollector.collect(this, frameStack);
    }

    /**
     * Triggers explicit mark-and-sweep garbage collection over this heap using roots
     * from the provided collection of frames and class static fields.
     */
    public GcResult collectGarbage(Collection<Frame> frames) {
        return GarbageCollector.collect(this, frames);
    }

    /**
     * Triggers explicit mark-and-sweep garbage collection scanning only class static fields as roots.
     */
    public GcResult collectGarbage() {
        return GarbageCollector.collect(this);
    }

    /**
     * Retrieves a static field value, initializing it to the default value if not yet set.
     */
    public Value getStaticField(FieldKey key) {
        Objects.requireNonNull(key, "FieldKey cannot be null");
        return staticFields.computeIfAbsent(key, k -> defaultValueForDescriptor(k.descriptor()));
    }

    /**
     * Stores a static field value.
     */
    public void setStaticField(FieldKey key, Value value) {
        Objects.requireNonNull(key, "FieldKey cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        staticFields.put(key, value);
    }

    /**
     * Clears all heap-allocated objects and static fields, resetting the handle generator.
     */
    public void reset() {
        objects.clear();
        staticFields.clear();
        nextHandle = 1L;
    }

    /**
     * Computes the specification-mandated default value for a given field descriptor (JVMS Section 2.3, Section 2.4).
     */
    public static Value defaultValueForDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor cannot be null");
        if (descriptor.isEmpty()) {
            throw new IllegalArgumentException("Empty field descriptor");
        }
        char first = descriptor.charAt(0);
        return switch (first) {
            case 'Z', 'B', 'C', 'S', 'I' -> Value.ofInt(0);
            case 'J' -> Value.ofLong(0L);
            case 'F' -> Value.ofFloat(0.0f);
            case 'D' -> Value.ofDouble(0.0d);
            case 'L', '[' -> NullReference.INSTANCE;
            default -> throw new IllegalArgumentException("Unsupported field descriptor for default value: " + descriptor);
        };
    }
}
