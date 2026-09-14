package dev.ben.minijvm.runtime;

/**
 * Base sealed interface for primitive runtime values.
 */
public sealed interface PrimitiveValue extends Value
        permits IntValue, LongValue, FloatValue, DoubleValue {
}
