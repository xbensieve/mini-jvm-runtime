package dev.ben.minijvm.classfile;

/**
 * An entry in a method's exception table within a Code attribute.
 *
 * @param startPc   Inclusive start of the range in bytecode array.
 * @param endPc     Exclusive end of the range in bytecode array.
 * @param handlerPc Target PC to jump to if exception is caught.
 * @param catchType Constant pool index of class of exception to catch, or 0 for any exception (finally).
 */
public record ExceptionTableEntry(int startPc, int endPc, int handlerPc, int catchType) {
}
