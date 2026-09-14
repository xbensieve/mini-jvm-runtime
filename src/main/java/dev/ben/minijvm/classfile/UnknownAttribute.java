package dev.ben.minijvm.classfile;

import java.util.Arrays;
import java.util.Objects;

/**
 * Represents an attribute that is recognized or skipped without dedicated parsing.
 * Holds the validated raw byte payload of the attribute.
 */
public record UnknownAttribute(String name, byte[] info) implements Attribute {
    public UnknownAttribute {
        Objects.requireNonNull(name, "name cannot be null");
        info = (info == null) ? new byte[0] : info.clone();
    }

    @Override
    public byte[] info() {
        return info.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnknownAttribute that)) return false;
        return Objects.equals(name, that.name) && Arrays.equals(info, that.info);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(name);
        result = 31 * result + Arrays.hashCode(info);
        return result;
    }

    @Override
    public String toString() {
        return "UnknownAttribute[name=" + name + ", length=" + info.length + "]";
    }
}
