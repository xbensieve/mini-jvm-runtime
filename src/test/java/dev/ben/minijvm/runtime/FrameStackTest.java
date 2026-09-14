package dev.ben.minijvm.runtime;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.exception.StackFaultException;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrameStackTest {

    @Test
    @DisplayName("FrameStack maintains strict LIFO push, peek, and pop order")
    void testLifoBehavior() {
        Frame f1 = createDummyFrame("m1");
        Frame f2 = createDummyFrame("m2");
        Frame f3 = createDummyFrame("m3");

        FrameStack stack = new FrameStack(10);
        assertTrue(stack.isEmpty());
        assertEquals(0, stack.depth());

        stack.push(f1);
        assertEquals(1, stack.depth());
        assertSame(f1, stack.peek());
        assertSame(f1, stack.current());

        stack.push(f2);
        assertEquals(2, stack.depth());
        assertSame(f2, stack.peek());

        stack.push(f3);
        assertEquals(3, stack.depth());
        assertSame(f3, stack.peek());

        // LIFO pop
        assertSame(f3, stack.pop());
        assertEquals(2, stack.depth());
        assertSame(f2, stack.peek());

        assertSame(f2, stack.pop());
        assertEquals(1, stack.depth());
        assertSame(f1, stack.peek());

        assertSame(f1, stack.pop());
        assertEquals(0, stack.depth());
        assertTrue(stack.isEmpty());
    }

    @Test
    @DisplayName("FrameStack underflow on pop or peek from empty stack")
    void testUnderflow() {
        FrameStack stack = new FrameStack();
        assertThrows(StackFaultException.class, stack::pop);
        assertThrows(StackFaultException.class, stack::peek);
        assertThrows(StackFaultException.class, stack::current);
    }

    @Test
    @DisplayName("FrameStack overflow when exceeding max depth")
    void testCallStackOverflow() {
        FrameStack stack = new FrameStack(2);
        Frame f1 = createDummyFrame("m1");
        Frame f2 = createDummyFrame("m2");
        Frame f3 = createDummyFrame("m3");

        stack.push(f1);
        stack.push(f2);

        StackFaultException ex = assertThrows(StackFaultException.class, () -> stack.push(f3));
        assertTrue(ex.getMessage().contains("Call stack overflow"));
    }

    @Test
    @DisplayName("FrameStack rejects null frame and negative/zero capacity")
    void testInvalidArguments() {
        FrameStack stack = new FrameStack();
        assertThrows(StackFaultException.class, () -> stack.push(null));
        assertThrows(StackFaultException.class, () -> new FrameStack(0));
        assertThrows(StackFaultException.class, () -> new FrameStack(-5));
    }

    @Test
    @DisplayName("toList returns an unmodifiable snapshot of active frames")
    void testToList() {
        Frame f1 = createDummyFrame("m1");
        Frame f2 = createDummyFrame("m2");

        FrameStack stack = new FrameStack();
        stack.push(f1);
        stack.push(f2);

        List<Frame> list = stack.toList();
        assertEquals(2, list.size());
        assertSame(f2, list.get(0)); // Top of stack first
        assertSame(f1, list.get(1));

        assertThrows(UnsupportedOperationException.class, () -> list.add(f1));
    }

    private Frame createDummyFrame(String methodName) {
        String src = String.format("""
                package fixtures;
                public class Dummy_%s {
                    public void %s() {}
                }
                """, methodName, methodName);
        byte[] bytes = CompilerTestUtils.compile("fixtures.Dummy_" + methodName, src);
        ClassFile cf = ClassFileReader.read(bytes);
        MethodInfo m = cf.findMethod(methodName, "()V").orElseThrow();
        return new Frame(cf, m);
    }
}
