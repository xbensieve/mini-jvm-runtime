package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.exception.StackFaultException;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Represents an allocated guest object instance resident in the guest Heap.
 * Owns its runtime class metadata and instance field storage.
 */
public class GuestObject {

    private final long handle;
    private final ClassFile runtimeClass;
    private final String runtimeClassName;
    private final Map<FieldKey, Value> fields;

    public GuestObject(long handle, ClassFile runtimeClass, Map<FieldKey, Value> initialFields) {
        if (handle <= 0) {
            throw new StackFaultException("GuestObject handle must be positive: " + handle);
        }
        this.handle = handle;
        this.runtimeClass = Objects.requireNonNull(runtimeClass, "runtimeClass cannot be null");
        this.runtimeClassName = runtimeClass.thisClassName();
        this.fields = new HashMap<>(Objects.requireNonNull(initialFields, "initialFields cannot be null"));
    }

    protected GuestObject(long handle, String runtimeClassName, Map<FieldKey, Value> fields) {
        if (handle <= 0) {
            throw new StackFaultException("GuestObject handle must be positive: " + handle);
        }
        this.handle = handle;
        this.runtimeClass = null;
        this.runtimeClassName = Objects.requireNonNull(runtimeClassName, "runtimeClassName cannot be null");
        this.fields = fields != null ? new HashMap<>(fields) : Collections.emptyMap();
    }

    public long handle() {
        return handle;
    }

    public ClassFile runtimeClass() {
        if (runtimeClass == null) {
            throw new StackFaultException("Guest object @" + handle + " of class '" + runtimeClassName + "' has no static ClassFile definition");
        }
        return runtimeClass;
    }

    public Optional<ClassFile> findRuntimeClass() {
        return Optional.ofNullable(runtimeClass);
    }

    public String runtimeClassName() {
        return runtimeClassName;
    }

    public boolean isArray() {
        return this instanceof GuestArray;
    }

    public GuestArray asArray() {
        if (this instanceof GuestArray array) {
            return array;
        }
        throw new StackFaultException("Guest object @" + handle + " of class '" + runtimeClassName + "' is not an array");
    }

    public Value getField(FieldKey key) {
        Objects.requireNonNull(key, "FieldKey cannot be null");
        Value val = fields.get(key);
        if (val == null) {
            throw new StackFaultException(
                    String.format("Instance field '%s' not found on object @%d of class '%s'",
                            key, handle, runtimeClassName())
            );
        }
        return val;
    }

    public void setField(FieldKey key, Value value) {
        Objects.requireNonNull(key, "FieldKey cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        if (!fields.containsKey(key)) {
            throw new StackFaultException(
                    String.format("Cannot set unknown instance field '%s' on object @%d of class '%s'",
                            key, handle, runtimeClassName())
            );
        }
        fields.put(key, value);
    }

    public boolean hasField(FieldKey key) {
        return fields.containsKey(key);
    }

    public Map<FieldKey, Value> fields() {
        return Collections.unmodifiableMap(fields);
    }

    public ObjectReference toReference() {
        return new ObjectReference(handle, runtimeClassName());
    }

    @Override
    public String toString() {
        return "GuestObject[@" + handle + ", class=" + runtimeClassName() + ", fields=" + fields.size() + "]";
    }
}
