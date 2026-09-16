package dev.ben.minijvm.classfile;

import dev.ben.minijvm.exception.ClassFormatException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class MethodDescriptorTest {

    @Test
    @DisplayName("Parse zero-argument void method: ()V")
    void testZeroArgVoid() {
        MethodDescriptor desc = MethodDescriptor.parse("()V");
        assertEquals("()V", desc.rawDescriptor());
        assertEquals(0, desc.parameterCount());
        assertEquals(0, desc.parameterSlotCount());
        assertTrue(desc.isVoidReturn());
        assertFalse(desc.isCategory2Return());
        assertFalse(desc.hasCategory2Parameters());
        assertEquals(MethodDescriptor.TypeKind.VOID, desc.returnKind());
        assertEquals("V", desc.returnDescriptor());
    }

    @Test
    @DisplayName("Parse single int argument with int return: (I)I")
    void testSingleIntArgumentIntReturn() {
        MethodDescriptor desc = MethodDescriptor.parse("(I)I");
        assertEquals("(I)I", desc.rawDescriptor());
        assertEquals(1, desc.parameterCount());
        assertEquals(1, desc.parameterSlotCount());
        assertFalse(desc.isVoidReturn());
        assertTrue(desc.isCategory1IntReturn());
        assertEquals(MethodDescriptor.TypeKind.INT, desc.parameters().get(0).kind());
        assertEquals(1, desc.parameters().get(0).slotWidth());
        assertEquals("I", desc.parameters().get(0).descriptor());
        assertFalse(desc.parameters().get(0).isCategory2());
    }

    @Test
    @DisplayName("Parse multi-argument int method: (II)I")
    void testMultiIntArguments() {
        MethodDescriptor desc = MethodDescriptor.parse("(II)I");
        assertEquals(2, desc.parameterCount());
        assertEquals(2, desc.parameterSlotCount());
        assertTrue(desc.isCategory1IntReturn());
        assertEquals(MethodDescriptor.TypeKind.INT, desc.parameters().get(0).kind());
        assertEquals(MethodDescriptor.TypeKind.INT, desc.parameters().get(1).kind());
    }

    @Test
    @DisplayName("Parse object reference and primitive arguments: (Ljava/lang/String;I)V")
    void testReferenceAndPrimitiveArguments() {
        MethodDescriptor desc = MethodDescriptor.parse("(Ljava/lang/String;I)V");
        assertEquals(2, desc.parameterCount());
        assertEquals(2, desc.parameterSlotCount());
        assertTrue(desc.isVoidReturn());

        MethodDescriptor.Parameter p0 = desc.parameters().get(0);
        assertEquals(MethodDescriptor.TypeKind.REFERENCE, p0.kind());
        assertEquals("Ljava/lang/String;", p0.descriptor());
        assertTrue(p0.isReference());
        assertFalse(p0.isCategory2());

        MethodDescriptor.Parameter p1 = desc.parameters().get(1);
        assertEquals(MethodDescriptor.TypeKind.INT, p1.kind());
        assertEquals("I", p1.descriptor());
        assertFalse(p1.isReference());
    }

    @Test
    @DisplayName("Parse array argument: ([I)I and ([[Ljava/lang/Object;)V")
    void testArrayArguments() {
        MethodDescriptor desc1 = MethodDescriptor.parse("([I)I");
        assertEquals(1, desc1.parameterCount());
        assertEquals(1, desc1.parameterSlotCount());
        assertTrue(desc1.parameters().get(0).isReference());
        assertEquals("[I", desc1.parameters().get(0).descriptor());

        MethodDescriptor desc2 = MethodDescriptor.parse("([[Ljava/lang/Object;)V");
        assertEquals(1, desc2.parameterCount());
        assertEquals(1, desc2.parameterSlotCount());
        assertTrue(desc2.parameters().get(0).isReference());
        assertEquals("[[Ljava/lang/Object;", desc2.parameters().get(0).descriptor());
    }

    @Test
    @DisplayName("Parse category-2 arguments and return: (J)J and (ID)V")
    void testCategory2Types() {
        MethodDescriptor desc1 = MethodDescriptor.parse("(J)J");
        assertEquals(1, desc1.parameterCount());
        assertEquals(2, desc1.parameterSlotCount());
        assertTrue(desc1.parameters().get(0).isCategory2());
        assertTrue(desc1.hasCategory2Parameters());
        assertTrue(desc1.isCategory2Return());

        MethodDescriptor desc2 = MethodDescriptor.parse("(ID)V");
        assertEquals(2, desc2.parameterCount());
        assertEquals(3, desc2.parameterSlotCount()); // 1 for int + 2 for double
        assertFalse(desc2.parameters().get(0).isCategory2());
        assertTrue(desc2.parameters().get(1).isCategory2());
        assertTrue(desc2.hasCategory2Parameters());
        assertFalse(desc2.isCategory2Return());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "(",
            ")",
            "()",
            "(I)",
            "I()V",
            "(I)II",
            "(Ljava/lang/String)V",
            "(L;)V",
            "()X",
            "(X)V",
            "(V)V",
            "([V)V",
            "([)V",
            "()L;",
            "()Ljava/lang/String"
    })
    @DisplayName("Deterministic rejection of malformed method descriptors")
    void testMalformedDescriptors(String malformed) {
        assertThrows(ClassFormatException.class, () -> MethodDescriptor.parse(malformed));
    }

    @Test
    @DisplayName("Null descriptor throws ClassFormatException")
    void testNullDescriptor() {
        assertThrows(ClassFormatException.class, () -> MethodDescriptor.parse(null));
    }
}
