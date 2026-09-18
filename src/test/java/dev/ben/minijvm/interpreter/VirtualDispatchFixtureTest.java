package dev.ben.minijvm.interpreter;

import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.opcode.Opcode;
import dev.ben.minijvm.runtime.ClassRepository;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.Value;
import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests using real javac-compiled Java 21 bytecode fixtures
 * demonstrating true runtime-type-based virtual dispatch (JVMS Section 5.4.6 & Section 6.5).
 *
 * Verifies that symbolic resolution (Parent.value:()I) is decoupled from
 * runtime virtual method selection (Child.value:()I), and cross-checks guest VM
 * results directly against host JVM execution.
 */
class VirtualDispatchFixtureTest {

    private ClassRepository repository;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        repository = new ClassRepository();
        interpreter = new Interpreter(
                new BytecodeDecoder(),
                new MethodResolver(repository),
                new MethodSelector(repository)
        );
    }

    private ClassLoader createHostClassLoader(Map<String, byte[]> compiled) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = compiled.get(name);
                if (bytes != null) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };
    }

    @Test
    @DisplayName("Polymorphic invokevirtual: Parent vs Child override cross-checked with host JVM")
    void testVirtualDispatchParentAndChild() throws Exception {
        String parentSrc = """
                package fixtures.virtual;
                public class Parent {
                    public int value() {
                        return 1;
                    }
                }
                """;

        String childSrc = """
                package fixtures.virtual;
                public class Child extends Parent {
                    @Override
                    public int value() {
                        return 2;
                    }
                }
                """;

        String callerSrc = """
                package fixtures.virtual;
                public class Caller {
                    public int test(Parent p) {
                        return p.value();
                    }
                }
                """;

        Map<String, byte[]> compiled = CompilerTestUtils.compileAll(Map.of(
                "fixtures.virtual.Parent", parentSrc,
                "fixtures.virtual.Child", childSrc,
                "fixtures.virtual.Caller", callerSrc
        ));

        ClassFile parentCf = ClassFileReader.read(compiled.get("fixtures.virtual.Parent"));
        ClassFile childCf = ClassFileReader.read(compiled.get("fixtures.virtual.Child"));
        ClassFile callerCf = ClassFileReader.read(compiled.get("fixtures.virtual.Caller"));

        repository.register(parentCf);
        repository.register(childCf);
        repository.register(callerCf);

        MethodInfo testMethod = callerCf.findMethod("test", "(Lfixtures/virtual/Parent;)I").orElseThrow();

        // Verify javac produced invokevirtual at PC 1
        BytecodeDecoder decoder = new BytecodeDecoder();
        byte[] code = testMethod.code().orElseThrow().code();
        Instruction invokevirtualIns = decoder.decode(code, 1);
        assertEquals(Opcode.INVOKEVIRTUAL, invokevirtualIns.opcode());

        // Host JVM reference execution via ClassLoader reflection
        ClassLoader hostLoader = createHostClassLoader(compiled);
        Object hostCaller = hostLoader.loadClass("fixtures.virtual.Caller").getConstructor().newInstance();
        Object hostParent = hostLoader.loadClass("fixtures.virtual.Parent").getConstructor().newInstance();
        Object hostChild = hostLoader.loadClass("fixtures.virtual.Child").getConstructor().newInstance();

        int hostParentResult = (int) hostCaller.getClass()
                .getMethod("test", hostLoader.loadClass("fixtures.virtual.Parent"))
                .invoke(hostCaller, hostParent);
        int hostChildResult = (int) hostCaller.getClass()
                .getMethod("test", hostLoader.loadClass("fixtures.virtual.Parent"))
                .invoke(hostCaller, hostChild);

        assertEquals(1, hostParentResult);
        assertEquals(2, hostChildResult);

        // --- Guest Mini JVM Execution with Parent Receiver ---
        Frame parentCallerFrame = new Frame(callerCf, testMethod);
        parentCallerFrame.operandStack().push(Value.ofReference(1001L, "fixtures/virtual/Parent"));
        parentCallerFrame.setPc(1); // Station at invokevirtual

        FrameStack parentStack = new FrameStack();
        parentStack.push(parentCallerFrame);
        interpreter.execute(parentStack);

        assertTrue(parentCallerFrame.isReturned());
        assertEquals(hostParentResult, parentCallerFrame.returnValue().orElseThrow().asInt(),
                "Mini JVM Parent result must match host JVM result");

        // --- Guest Mini JVM Execution with Child Receiver ---
        Frame childCallerFrame = new Frame(callerCf, testMethod);
        childCallerFrame.operandStack().push(Value.ofReference(2002L, "fixtures/virtual/Child"));
        childCallerFrame.setPc(1); // Station at invokevirtual

        FrameStack childStack = new FrameStack();
        childStack.push(childCallerFrame);
        interpreter.execute(childStack);

        assertTrue(childCallerFrame.isReturned());
        assertEquals(hostChildResult, childCallerFrame.returnValue().orElseThrow().asInt(),
                "Mini JVM Child result must match host JVM result");
    }

    @Test
    @DisplayName("Multi-level inheritance override: Parent -> Child -> GrandChild")
    void testMultiLevelOverrideFixture() throws Exception {
        String parentSrc = """
                package fixtures.virtual;
                public class MultiParent {
                    public int calc() {
                        return 10;
                    }
                }
                """;

        String childSrc = """
                package fixtures.virtual;
                public class MultiChild extends MultiParent {
                    @Override
                    public int calc() {
                        return 20;
                    }
                }
                """;

        String grandChildSrc = """
                package fixtures.virtual;
                public class MultiGrandChild extends MultiChild {
                    @Override
                    public int calc() {
                        return 30;
                    }
                }
                """;

        String callerSrc = """
                package fixtures.virtual;
                public class MultiCaller {
                    public int invoke(MultiParent p) {
                        return p.calc();
                    }
                }
                """;

        Map<String, byte[]> compiled = CompilerTestUtils.compileAll(Map.of(
                "fixtures.virtual.MultiParent", parentSrc,
                "fixtures.virtual.MultiChild", childSrc,
                "fixtures.virtual.MultiGrandChild", grandChildSrc,
                "fixtures.virtual.MultiCaller", callerSrc
        ));

        ClassFile parentCf = ClassFileReader.read(compiled.get("fixtures.virtual.MultiParent"));
        ClassFile childCf = ClassFileReader.read(compiled.get("fixtures.virtual.MultiChild"));
        ClassFile grandChildCf = ClassFileReader.read(compiled.get("fixtures.virtual.MultiGrandChild"));
        ClassFile callerCf = ClassFileReader.read(compiled.get("fixtures.virtual.MultiCaller"));

        repository.register(parentCf);
        repository.register(childCf);
        repository.register(grandChildCf);
        repository.register(callerCf);

        MethodInfo invokeMethod = callerCf.findMethod("invoke", "(Lfixtures/virtual/MultiParent;)I").orElseThrow();

        // Host JVM reference
        ClassLoader hostLoader = createHostClassLoader(compiled);
        Object hostCaller = hostLoader.loadClass("fixtures.virtual.MultiCaller").getConstructor().newInstance();
        Object hostGrandChild = hostLoader.loadClass("fixtures.virtual.MultiGrandChild").getConstructor().newInstance();
        int hostGrandResult = (int) hostCaller.getClass()
                .getMethod("invoke", hostLoader.loadClass("fixtures.virtual.MultiParent"))
                .invoke(hostCaller, hostGrandChild);
        assertEquals(30, hostGrandResult);

        // Guest Mini JVM: GrandChild receiver
        Frame callerFrame = new Frame(callerCf, invokeMethod);
        callerFrame.operandStack().push(Value.ofReference(3003L, "fixtures/virtual/MultiGrandChild"));
        callerFrame.setPc(1);

        FrameStack stack = new FrameStack();
        stack.push(callerFrame);
        interpreter.execute(stack);

        assertTrue(callerFrame.isReturned());
        assertEquals(hostGrandResult, callerFrame.returnValue().orElseThrow().asInt(),
                "Mini JVM GrandChild dispatch must match host JVM result (30)");
    }

    @Test
    @DisplayName("Inherited non-overridden method in real javac fixture")
    void testInheritedNonOverriddenMethodFixture() throws Exception {
        String baseSrc = """
                package fixtures.virtual;
                public class ServiceBase {
                    public int computeRate() {
                        return 100;
                    }
                }
                """;

        String subSrc = """
                package fixtures.virtual;
                public class ServiceSub extends ServiceBase {
                    // inherits computeRate without overriding
                }
                """;

        String callerSrc = """
                package fixtures.virtual;
                public class ServiceCaller {
                    public int call(ServiceBase s) {
                        return s.computeRate();
                    }
                }
                """;

        Map<String, byte[]> compiled = CompilerTestUtils.compileAll(Map.of(
                "fixtures.virtual.ServiceBase", baseSrc,
                "fixtures.virtual.ServiceSub", subSrc,
                "fixtures.virtual.ServiceCaller", callerSrc
        ));

        ClassFile baseCf = ClassFileReader.read(compiled.get("fixtures.virtual.ServiceBase"));
        ClassFile subCf = ClassFileReader.read(compiled.get("fixtures.virtual.ServiceSub"));
        ClassFile callerCf = ClassFileReader.read(compiled.get("fixtures.virtual.ServiceCaller"));

        repository.register(baseCf);
        repository.register(subCf);
        repository.register(callerCf);

        MethodInfo callMethod = callerCf.findMethod("call", "(Lfixtures/virtual/ServiceBase;)I").orElseThrow();

        // Host JVM check
        ClassLoader hostLoader = createHostClassLoader(compiled);
        Object hostCaller = hostLoader.loadClass("fixtures.virtual.ServiceCaller").getConstructor().newInstance();
        Object hostSub = hostLoader.loadClass("fixtures.virtual.ServiceSub").getConstructor().newInstance();
        int hostResult = (int) hostCaller.getClass()
                .getMethod("call", hostLoader.loadClass("fixtures.virtual.ServiceBase"))
                .invoke(hostCaller, hostSub);
        assertEquals(100, hostResult);

        // Guest Mini JVM execution
        Frame frame = new Frame(callerCf, callMethod);
        frame.operandStack().push(Value.ofReference(4004L, "fixtures/virtual/ServiceSub"));
        frame.setPc(1);

        FrameStack stack = new FrameStack();
        stack.push(frame);
        interpreter.execute(stack);

        assertTrue(frame.isReturned());
        assertEquals(hostResult, frame.returnValue().orElseThrow().asInt(),
                "Inherited method dispatch must match host JVM result (100)");
    }

    @Test
    @DisplayName("Alternating receivers on the same compiled call site")
    void testAlternatingReceivers() {
        String parentSrc = """
                package fixtures.virtual;
                public class AlphaParent {
                    public int get() { return 5; }
                }
                """;
        String childSrc = """
                package fixtures.virtual;
                public class AlphaChild extends AlphaParent {
                    @Override
                    public int get() { return 15; }
                }
                """;
        String callerSrc = """
                package fixtures.virtual;
                public class AlphaCaller {
                    public int test(AlphaParent p) { return p.get(); }
                }
                """;

        Map<String, byte[]> compiled = CompilerTestUtils.compileAll(Map.of(
                "fixtures.virtual.AlphaParent", parentSrc,
                "fixtures.virtual.AlphaChild", childSrc,
                "fixtures.virtual.AlphaCaller", callerSrc
        ));

        ClassFile parentCf = ClassFileReader.read(compiled.get("fixtures.virtual.AlphaParent"));
        ClassFile childCf = ClassFileReader.read(compiled.get("fixtures.virtual.AlphaChild"));
        ClassFile callerCf = ClassFileReader.read(compiled.get("fixtures.virtual.AlphaCaller"));

        repository.register(parentCf);
        repository.register(childCf);
        repository.register(callerCf);

        MethodInfo testMethod = callerCf.findMethod("test", "(Lfixtures/virtual/AlphaParent;)I").orElseThrow();

        // Test alternating: Parent -> Child -> Parent -> Child
        String[] receiverClasses = {
                "fixtures/virtual/AlphaParent",
                "fixtures/virtual/AlphaChild",
                "fixtures/virtual/AlphaParent",
                "fixtures/virtual/AlphaChild"
        };
        int[] expectedValues = {5, 15, 5, 15};

        for (int i = 0; i < receiverClasses.length; i++) {
            Frame frame = new Frame(callerCf, testMethod);
            frame.operandStack().push(Value.ofReference(i + 1, receiverClasses[i]));
            frame.setPc(1);

            FrameStack stack = new FrameStack();
            stack.push(frame);
            interpreter.execute(stack);

            assertTrue(frame.isReturned());
            assertEquals(expectedValues[i], frame.returnValue().orElseThrow().asInt(),
                    String.format("Iteration %d for receiver %s", i, receiverClasses[i]));
        }
    }

    @Test
    @DisplayName("Prove symbolic resolution targets Parent while runtime selection executes Child")
    void testSymbolicVsSelectionProof() {
        String parentSrc = """
                package fixtures.virtual;
                public class Shape {
                    public int sides() { return 0; }
                }
                """;
        String childSrc = """
                package fixtures.virtual;
                public class Triangle extends Shape {
                    @Override
                    public int sides() { return 3; }
                }
                """;
        String callerSrc = """
                package fixtures.virtual;
                public class Geometry {
                    public int getSides(Shape s) { return s.sides(); }
                }
                """;

        Map<String, byte[]> compiled = CompilerTestUtils.compileAll(Map.of(
                "fixtures.virtual.Shape", parentSrc,
                "fixtures.virtual.Triangle", childSrc,
                "fixtures.virtual.Geometry", callerSrc
        ));

        ClassFile shapeCf = ClassFileReader.read(compiled.get("fixtures.virtual.Shape"));
        ClassFile triangleCf = ClassFileReader.read(compiled.get("fixtures.virtual.Triangle"));
        ClassFile geomCf = ClassFileReader.read(compiled.get("fixtures.virtual.Geometry"));

        repository.register(shapeCf);
        repository.register(triangleCf);
        repository.register(geomCf);

        MethodInfo getSides = geomCf.findMethod("getSides", "(Lfixtures/virtual/Shape;)I").orElseThrow();

        // Step 1: Prove Constant Pool symbolic Methodref targets Shape.sides:()I
        BytecodeDecoder decoder = new BytecodeDecoder();
        byte[] code = getSides.code().orElseThrow().code();
        Instruction invokeIns = decoder.decode(code, 1);
        int cpIndex = invokeIns.operand();

        MethodResolver resolver = new MethodResolver(repository);
        MethodResolver.ResolvedMethod resolved = resolver.resolveMethod(geomCf.constantPool(), geomCf, cpIndex, Opcode.INVOKEVIRTUAL);

        // Symbolic target is declared in Shape
        assertEquals("fixtures/virtual/Shape", resolved.classFile().thisClassName());
        assertEquals("sides", resolved.method().name(resolved.classFile().constantPool()));

        // Step 2: Prove MethodSelector with Triangle receiver selects Triangle.sides:()I
        MethodSelector selector = new MethodSelector(repository);
        MethodSelector.SelectedMethod selected = selector.selectMethod(resolved, triangleCf);

        // Selected target is declared in Triangle
        assertEquals("fixtures/virtual/Triangle", selected.declaringClass().thisClassName());
        assertNotEquals(resolved.classFile(), selected.declaringClass(),
                "Declaring class of resolved symbolic reference must differ from selected class");

        // Step 3: Execute in Interpreter and verify returned value is 3 from Triangle
        Frame frame = new Frame(geomCf, getSides);
        frame.operandStack().push(Value.ofReference(99L, "fixtures/virtual/Triangle"));
        frame.setPc(1);

        FrameStack stack = new FrameStack();
        stack.push(frame);
        interpreter.execute(stack);

        assertTrue(frame.isReturned());
        assertEquals(3, frame.returnValue().orElseThrow().asInt());
    }
}
