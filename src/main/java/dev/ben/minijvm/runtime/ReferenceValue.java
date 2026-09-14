package dev.ben.minijvm.runtime;

/**
 * Base sealed interface for reference values (Category-1, 1 slot).
 * In accordance with ADR-005, guest references model VM heap identities (handles),
 * never direct host Java object references.
 */
public sealed interface ReferenceValue extends Value
        permits ObjectReference, NullReference {

    @Override
    default int slotWidth() {
        return 1;
    }

    @Override
    default ReferenceValue asReference() {
        return this;
    }
}
