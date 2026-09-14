package dev.ben.minijvm.classfile;

/**
 * Base interface for parsed class-file attributes.
 */
public sealed interface Attribute permits CodeAttribute, UnknownAttribute {
    String name();
}
