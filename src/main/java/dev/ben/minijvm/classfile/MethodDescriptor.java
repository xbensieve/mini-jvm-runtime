package dev.ben.minijvm.classfile;

import dev.ben.minijvm.exception.ClassFormatException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic parser and semantic model for JVM method descriptors (JVMS §4.3.3).
 *
 * <pre>
 * MethodDescriptor:
 *   ( {ParameterDescriptor} ) ReturnDescriptor
 * </pre>
 */
public final class MethodDescriptor {

    public enum TypeKind {
        BYTE,
        CHAR,
        DOUBLE,
        FLOAT,
        INT,
        LONG,
        SHORT,
        BOOLEAN,
        REFERENCE,
        VOID
    }

    public record Parameter(TypeKind kind, int slotWidth, String descriptor) {
        public Parameter {
            Objects.requireNonNull(kind, "kind cannot be null");
            Objects.requireNonNull(descriptor, "descriptor cannot be null");
        }

        public boolean isCategory2() {
            return slotWidth == 2;
        }

        public boolean isReference() {
            return kind == TypeKind.REFERENCE;
        }

        public boolean isIntEquivalent() {
            return kind == TypeKind.INT
                    || kind == TypeKind.BOOLEAN
                    || kind == TypeKind.BYTE
                    || kind == TypeKind.CHAR
                    || kind == TypeKind.SHORT;
        }
    }

    private final String rawDescriptor;
    private final List<Parameter> parameters;
    private final TypeKind returnKind;
    private final String returnDescriptor;
    private final int parameterSlotCount;

    private MethodDescriptor(String rawDescriptor, List<Parameter> parameters, TypeKind returnKind, String returnDescriptor) {
        this.rawDescriptor = rawDescriptor;
        this.parameters = Collections.unmodifiableList(parameters);
        this.returnKind = returnKind;
        this.returnDescriptor = returnDescriptor;

        int totalSlots = 0;
        for (Parameter p : parameters) {
            totalSlots += p.slotWidth();
        }
        this.parameterSlotCount = totalSlots;
    }

    public static MethodDescriptor parse(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            throw new ClassFormatException("Method descriptor cannot be null or empty");
        }
        if (descriptor.charAt(0) != '(') {
            throw new ClassFormatException("Method descriptor must begin with '(': " + descriptor);
        }

        int pos = 1;
        int len = descriptor.length();
        List<Parameter> params = new ArrayList<>();

        while (pos < len && descriptor.charAt(pos) != ')') {
            ParsedType pt = parseFieldType(descriptor, pos, false);
            params.add(new Parameter(pt.kind(), pt.slotWidth(), pt.descriptor()));
            pos = pt.nextPos();
        }

        if (pos >= len || descriptor.charAt(pos) != ')') {
            throw new ClassFormatException("Unterminated parameter list in descriptor: " + descriptor);
        }
        pos++; // skip ')'

        if (pos >= len) {
            throw new ClassFormatException("Missing return descriptor in: " + descriptor);
        }

        ParsedType retType;
        if (descriptor.charAt(pos) == 'V') {
            retType = new ParsedType(TypeKind.VOID, 0, "V", pos + 1);
        } else {
            retType = parseFieldType(descriptor, pos, false);
        }

        if (retType.nextPos() != len) {
            throw new ClassFormatException(
                    String.format("Trailing characters '%s' after return descriptor in: %s",
                            descriptor.substring(retType.nextPos()), descriptor)
            );
        }

        return new MethodDescriptor(descriptor, params, retType.kind(), retType.descriptor());
    }

    private record ParsedType(TypeKind kind, int slotWidth, String descriptor, int nextPos) {}

    private static ParsedType parseFieldType(String desc, int pos, boolean inArray) {
        if (pos >= desc.length()) {
            throw new ClassFormatException("Unexpected end of descriptor at index " + pos + " in: " + desc);
        }

        char c = desc.charAt(pos);
        return switch (c) {
            case 'B' -> new ParsedType(TypeKind.BYTE, 1, "B", pos + 1);
            case 'C' -> new ParsedType(TypeKind.CHAR, 1, "C", pos + 1);
            case 'D' -> new ParsedType(TypeKind.DOUBLE, 2, "D", pos + 1);
            case 'F' -> new ParsedType(TypeKind.FLOAT, 1, "F", pos + 1);
            case 'I' -> new ParsedType(TypeKind.INT, 1, "I", pos + 1);
            case 'J' -> new ParsedType(TypeKind.LONG, 2, "J", pos + 1);
            case 'S' -> new ParsedType(TypeKind.SHORT, 1, "S", pos + 1);
            case 'Z' -> new ParsedType(TypeKind.BOOLEAN, 1, "Z", pos + 1);
            case 'L' -> {
                int semi = desc.indexOf(';', pos + 1);
                if (semi == -1) {
                    throw new ClassFormatException("Unterminated reference type descriptor at index " + pos + " in: " + desc);
                }
                if (semi == pos + 1) {
                    throw new ClassFormatException("Empty reference class name at index " + pos + " in: " + desc);
                }
                String refDesc = desc.substring(pos, semi + 1);
                yield new ParsedType(TypeKind.REFERENCE, 1, refDesc, semi + 1);
            }
            case '[' -> {
                int dims = 0;
                int cur = pos;
                while (cur < desc.length() && desc.charAt(cur) == '[') {
                    dims++;
                    cur++;
                }
                if (dims > 255) {
                    throw new ClassFormatException("Array dimension exceeds maximum 255 at index " + pos + " in: " + desc);
                }
                if (cur >= desc.length()) {
                    throw new ClassFormatException("Unexpected end of descriptor in array type at index " + cur + " in: " + desc);
                }
                ParsedType componentType = parseFieldType(desc, cur, true);
                if (componentType.kind() == TypeKind.VOID) {
                    throw new ClassFormatException("Array of void is illegal at index " + cur + " in: " + desc);
                }
                String arrayDesc = desc.substring(pos, componentType.nextPos());
                yield new ParsedType(TypeKind.REFERENCE, 1, arrayDesc, componentType.nextPos());
            }
            default -> throw new ClassFormatException(
                    String.format("Illegal descriptor character '%c' at index %d in: %s", c, pos, desc)
            );
        };
    }

    public String rawDescriptor() {
        return rawDescriptor;
    }

    public List<Parameter> parameters() {
        return parameters;
    }

    public int parameterCount() {
        return parameters.size();
    }

    public int parameterSlotCount() {
        return parameterSlotCount;
    }

    public TypeKind returnKind() {
        return returnKind;
    }

    public String returnDescriptor() {
        return returnDescriptor;
    }

    public boolean isVoidReturn() {
        return returnKind == TypeKind.VOID;
    }

    public boolean isCategory2Return() {
        return returnKind == TypeKind.DOUBLE || returnKind == TypeKind.LONG;
    }

    public boolean isCategory1IntReturn() {
        return returnKind == TypeKind.INT
                || returnKind == TypeKind.BOOLEAN
                || returnKind == TypeKind.BYTE
                || returnKind == TypeKind.CHAR
                || returnKind == TypeKind.SHORT;
    }

    public boolean isReferenceReturn() {
        return returnKind == TypeKind.REFERENCE;
    }

    public boolean hasCategory2Parameters() {
        for (Parameter p : parameters) {
            if (p.isCategory2()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodDescriptor that = (MethodDescriptor) o;
        return rawDescriptor.equals(that.rawDescriptor);
    }

    @Override
    public int hashCode() {
        return rawDescriptor.hashCode();
    }

    @Override
    public String toString() {
        return rawDescriptor;
    }
}
