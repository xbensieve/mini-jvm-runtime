<div align="center">

# Mini JVM Runtime

**A deterministic, educational JVM runtime implemented in pure Java 21.**

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)](#verification)
[![Release](https://img.shields.io/badge/release-v0.1.0-blue?style=flat-square)](#getting-started)
[![Java](https://img.shields.io/badge/java-21-orange?style=flat-square)](#getting-started)
[![Tests](https://img.shields.io/badge/tests-404%20passing-brightgreen?style=flat-square)](#verification)
[![License](https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square)](#license)

</div>

---

## Overview

Mini JVM Runtime is an educational, deliberately scoped JVM bytecode interpreter built from scratch in Java 21 with **zero external runtime dependencies**. It parses real `.class` files, executes a well-defined subset of JVM opcodes on an isolated guest runtime, and provides full visibility into every step of execution.

This project exists to make JVM internals tangible. Every design decision prioritises **clarity** over completeness, and **determinism** over performance:

- **No host leakage** — guest objects live in a managed `Heap` with monotonic handle allocation, entirely separate from the host JVM's memory.
- **No JNI or native methods** — execution is self-contained within the interpreter loop.
- **No multi-threading** — a single, sequential instruction stream ensures reproducible behavior across runs.
- **No hidden state** — every opcode, every PC transition, every frame push/pop is observable and testable.

Whether you are a student dissecting the JVM specification, an educator building coursework around bytecode execution, or a developer curious about what happens between `javac` and runtime — this project offers a living, runnable reference implementation.

---

## Key Features

- **Class-file parsing** — supports major versions 45 through 65 (Java 1.1 → Java 21), including full constant-pool resolution, method descriptors, and `Code` attribute extraction.
- **85 concrete JVM opcodes** — constants, loads, stores, arithmetic, control flow, branching, comparisons, field access, array operations, object allocation, method invocation, exception handling, and more.
- **Two-tier method resolution** — static resolution via `MethodResolver` (JVMS §5.4.3.3) and polymorphic virtual dispatch via `MethodSelector`, with full class-hierarchy traversal.
- **Deterministic mark-and-sweep GC** — explicit `GarbageCollector` with root scanning from call-stack frames and static fields, object-graph reachability traversal, and sweep-based reclamation.
- **Structured exception handling** — `ExceptionTableResolver` implements catch-handler search with subtype-aware matching and deterministic call-stack unwinding via `athrow`.
- **Guest object model** — `GuestObject` and `GuestArray` representations with typed `Value` wrappers (`IntValue`, `LongValue`, `FloatValue`, `DoubleValue`, `ObjectReference`, `NullReference`).
- **Interactive step debugger** — CLI-integrated instruction-level debugger with state dumps, operand-stack inspection, and call-stack visualisation.
- **Deterministic trace logging** — reproducible instruction-level and detailed execution traces for regression testing and specification validation.
- **404 automated tests** across 43 test classes — parser, stack, frame, opcode, interpreter, integration, fixture, negative/error-path, and deterministic-trace test layers.

---

## Getting Started

### Prerequisites

- **Java 21** (or later)
- **Apache Maven 3.9+**

### Build from Source

```bash
git clone https://github.com/xbensieve/mini-jvm-runtime.git
cd mini-jvm-runtime
mvn -B clean verify
```

This compiles the project, runs all 404 tests, and produces the executable JAR:

```
target/mini-jvm-runtime-0.1.0-SNAPSHOT.jar
```

### Quick Smoke Test

```bash
# Compile a simple Java class
echo 'public class Hello { public static int add() { return 2 + 3; } }' > Hello.java
javac Hello.java

# Execute with Mini JVM
java -jar target/mini-jvm-runtime-0.1.0-SNAPSHOT.jar Hello.class --method add
```

Expected output:

```
Result: IntValue[5]
Execution completed successfully (RETURNED).
```

---

## Usage Guide

### CLI Mode

The main entry point is `dev.ben.minijvm.cli.Main`. The JAR manifest is pre-configured, so you can invoke it directly:

```
Usage: minijvm <class-file> [method] [options]

Arguments:
  <class-file>               Path to the compiled .class file to execute
  [method]                   Optional name of the method to execute (default: main)

Options:
  -m, --method <name>        Target method name to execute (default: main)
  --mode <run|step>          Execution mode: continuous 'run' or interactive 'step'
  -s, --step                 Shortcut for --mode step
  -r, --run                  Shortcut for --mode run
  -t, --trace                Enable deterministic instruction trace in run mode
  -l, --level <level>        Trace detail level: INSTRUCTIONS or DETAILED
  -h, --help                 Display help
```

#### Standard Execution

```bash
java -jar target/mini-jvm-runtime-0.1.0-SNAPSHOT.jar MyClass.class --method compute
```

#### Traced Execution

```bash
java -jar target/mini-jvm-runtime-0.1.0-SNAPSHOT.jar MyClass.class -m compute -t -l DETAILED
```

#### Interactive Step Debugger

```bash
java -jar target/mini-jvm-runtime-0.1.0-SNAPSHOT.jar MyClass.class -m compute --mode step
```

Inside the debugger:

```
Mini JVM Interactive Debugger
Class:  MyClass
Method: compute()I
Type 'help' or 'h' for commands.

[Initial State]
  Frame: compute()I  PC=0  Stack=[](0/2)  Locals=1

minijvm> step
Step -> iconst_2 @0
  Frame: compute()I  PC=1  Stack=[IntValue[2]](1/2)  Locals=1

minijvm> s
Step -> iconst_3 @1
  Frame: compute()I  PC=2  Stack=[IntValue[2], IntValue[3]](2/2)  Locals=1

minijvm> run
Continuing execution to completion...
Execution completed (RETURNED).
Return value: IntValue[5]
```

Debugger commands:

| Command | Description |
|---|---|
| `step` / `s` / `Enter` | Execute one bytecode instruction |
| `run` / `r` / `continue` / `c` | Continue to completion |
| `dump` / `d` | Dump current frame and call-stack state |
| `help` / `h` | Display command reference |
| `quit` / `q` | Terminate session |

### Programmatic API

You can embed the Mini JVM as a library. Below is a minimal example that loads a class file, resolves a method, and executes it:

```java
import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.interpreter.BytecodeDecoder;
import dev.ben.minijvm.interpreter.Interpreter;
import dev.ben.minijvm.runtime.*;

import java.nio.file.Path;

public class EmbeddedExample {
    public static void main(String[] args) throws Exception {
        // 1. Parse the .class file
        ClassFile classFile = ClassFileReader.read(Path.of("MyClass.class"));

        // 2. Set up the runtime
        ClassRepository repository = new ClassRepository();
        repository.register(classFile);

        Heap heap = new Heap(repository);
        MethodResolver resolver = new MethodResolver(repository);
        MethodSelector selector = new MethodSelector(repository);
        FieldResolver fieldResolver = new FieldResolver(repository);
        ExceptionTableResolver exResolver = new ExceptionTableResolver(repository);

        Interpreter interpreter = new Interpreter(
                new BytecodeDecoder(), resolver, selector,
                fieldResolver, heap, exResolver
        );

        // 3. Locate the target method
        MethodInfo method = classFile.findMethod("compute", "()I")
                .orElseThrow(() -> new RuntimeException("Method not found"));

        // 4. Create the root frame and execute
        Frame rootFrame = new Frame(classFile, method);
        FrameStack frameStack = new FrameStack();
        frameStack.push(rootFrame);

        interpreter.execute(frameStack);

        // 5. Retrieve the result
        rootFrame.returnValue().ifPresent(val ->
                System.out.println("Result: " + val)  // e.g. IntValue[5]
        );
    }
}
```

---

## Architecture & Memory Model

### Execution Model

Mini JVM follows an **interpreter-first** architecture. The `Interpreter` is the sole execution engine — there is no JIT compiler, no optimising backend. Each cycle:

1. **Fetch** — reads the next instruction from the `Frame`'s bytecode at the current PC.
2. **Decode** — the `BytecodeDecoder` translates raw bytes into a typed `Instruction` (opcode + operands).
3. **Dispatch** — the interpreter switches on the `Opcode` enum and applies the operation to the frame's operand stack and local variables.
4. **Advance** — the PC is updated deterministically (sequential increment or branch-target replacement).

```
┌──────────────────────────────────────────────────────┐
│                    Interpreter                       │
│  ┌──────┐   ┌────────┐   ┌──────────┐   ┌────────┐  │
│  │Fetch │──▶│ Decode │──▶│ Dispatch │──▶│Advance │  │
│  └──────┘   └────────┘   └──────────┘   └────────┘  │
│       ▲                       │                      │
│       └───────────────────────┘                      │
└───────────────┬──────────────────────────────────────┘
                │
     ┌──────────▼──────────┐
     │     FrameStack      │
     │  ┌───────────────┐  │
     │  │ Frame (top)   │  │
     │  │  ├─ locals[]  │  │
     │  │  ├─ opStack[] │  │
     │  │  └─ pc        │  │
     │  ├───────────────┤  │
     │  │ Frame (caller)│  │
     │  └───────────────┘  │
     └─────────────────────┘
```

### Guest Object Model

Guest-runtime objects are completely isolated from the host JVM:

- **`GuestObject`** — stores instance fields as a `Map<FieldKey, Value>` keyed by declaring class, field name, and descriptor.
- **`GuestArray`** — extends `GuestObject` with a typed element array (`Value[]`), supporting `int`, `byte`, `char`, `short`, and reference element types.
- **`ObjectReference`** — a runtime handle (monotonic `long`) paired with the object's runtime class name. This is what the operand stack holds — never a host-Java reference.
- **`NullReference`** — the singleton guest null.

### Exception Unwinding

When `athrow` executes:

1. The `ExceptionTableResolver` searches the current method's exception table for a matching handler, evaluating `[startPc, endPc)` ranges and subtype compatibility.
2. If found, the operand stack is cleared, the exception reference is pushed, and the PC is set to the handler.
3. If no handler matches, the frame is popped from the `FrameStack` and the search continues up the call chain — full stack unwinding.
4. If the root frame has no handler, execution terminates with a `GuestExecutionException`.

### Garbage Collection

The `GarbageCollector` implements deterministic, explicit mark-and-sweep:

1. **Root resolution** — scans all local-variable slots, operand-stack entries, frame return values, and class static fields for live `ObjectReference` handles.
2. **Mark** — performs a breadth-first reachability traversal across guest object fields and array elements.
3. **Sweep** — removes all unreachable handles from the `Heap`, returning a `GcResult` with counts and reclaimed handle sets.

GC is never triggered implicitly. It is invoked explicitly via `heap.collectGarbage(frameStack)` or `GarbageCollector.collect(heap, frames)`.

---

## Supported Opcodes

<details>
<summary><strong>All 85 opcodes</strong> (click to expand)</summary>

| Category | Opcodes |
|---|---|
| **Constants** | `nop`, `aconst_null`, `iconst_m1`..`iconst_5`, `bipush`, `sipush`, `ldc`, `ldc_w` |
| **Loads** | `iload`, `aload`, `iload_0`..`iload_3`, `aload_0`..`aload_3` |
| **Stores** | `istore`, `astore`, `istore_0`..`istore_3`, `astore_0`..`astore_3` |
| **Stack** | `pop`, `dup` |
| **Arithmetic** | `iadd`, `isub`, `imul`, `idiv`, `irem`, `ineg`, `iinc` |
| **Branching** | `ifeq`, `ifne`, `iflt`, `ifge`, `ifgt`, `ifle`, `if_icmpeq`, `if_icmpne`, `if_icmplt`, `if_icmpge`, `if_icmpgt`, `if_icmple`, `if_acmpeq`, `if_acmpne`, `ifnull`, `ifnonnull`, `goto` |
| **Returns** | `ireturn`, `areturn`, `return` |
| **Fields** | `getstatic`, `putstatic`, `getfield`, `putfield` |
| **Invocation** | `invokevirtual`, `invokespecial`, `invokestatic` |
| **Arrays** | `iaload`, `aaload`, `baload`, `caload`, `saload`, `iastore`, `aastore`, `bastore`, `castore`, `sastore`, `newarray`, `anewarray`, `arraylength`, `multianewarray` |
| **Objects** | `new` |
| **Exceptions** | `athrow` |

</details>

---

## Project Structure

```
mini-jvm-runtime/
├── src/main/java/dev/ben/minijvm/
│   ├── classfile/       # Class-file parser, constant pool, descriptors
│   ├── cli/             # Command-line interface and argument parsing
│   ├── debug/           # Trace logging, state dumper, execution snapshots
│   ├── exception/       # Domain exception hierarchy
│   ├── interpreter/     # Bytecode decoder and interpreter engine
│   ├── opcode/          # Opcode enum and Instruction model
│   └── runtime/         # Frame, Heap, GC, resolvers, guest objects
├── src/test/java/       # 43 test classes, 404 tests
└── pom.xml              # Maven build (Java 21, JUnit 5)
```

---

## Verification

All 404 tests pass with zero failures:

```bash
mvn -B clean verify
```

```
[INFO] Tests run: 404, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Test coverage spans:

| Layer | Example Test Classes |
|---|---|
| Parser | `ClassFileReaderTest`, `ConstantPoolTest`, `MethodDescriptorTest`, `BytecodeReaderTest` |
| Stack & Frame | `OperandStackTest`, `LocalVariablesTest`, `FrameTest`, `FrameStackTest` |
| Opcodes | `OpcodeCoverageTest`, `InterpreterTest`, `ControlFlowDecoderTest`, `ArrayInterpreterTest` |
| Invocation | `InvocationInterpreterTest`, `VirtualDispatchTest`, `CallDepthTest` |
| Exceptions | `ExceptionInterpreterTest`, `ExceptionTableResolverTest` |
| GC | `GarbageCollectorTest`, `GcIntegrationTest` |
| Heap & Objects | `HeapTest`, `GuestArrayTest`, `HeapInterpreterTest` |
| Fixtures | `ClassFileReaderFixtureTest`, `ControlFlowFixtureTest`, `InvocationFixtureTest`, `ExceptionFixtureTest`, `VirtualDispatchFixtureTest` |
| CLI & Debug | `CliTest`, `StateDumperTest`, `DeterministicTraceTest` |
| Properties | `ControlFlowPropertyTest`, `PcFailureAndProgressionTest` |

---

## Known Limitations (Educational Scope)

This project is deliberately scoped as an educational tool. The following are **intentional non-goals**:

| Category | Limitation |
|---|---|
| **Compilation** | No JIT compiler, no AOT compilation — interpreter only. |
| **Debugging protocol** | No JDWP (Java Debug Wire Protocol) support. |
| **Primitive types** | No `long`/`double` array types (`laload`, `daload`, etc.) — only `int`, `byte`, `char`, `short`, and reference arrays. |
| **Invocation** | No `invokedynamic` or `invokeinterface` support. |
| **Standard library** | No host JDK standard library execution (`System.out.println`, `java.util.*`, etc.). |
| **Threading** | No `Thread` support, no monitors, no `synchronized`. |
| **Reflection** | No `java.lang.reflect` support. |
| **Class loading** | No dynamic class loading from classpath or JARs; classes are registered explicitly via `ClassRepository`. |
| **Wide instructions** | No `wide` prefix support. |
| **Native methods** | No JNI, no native method binding. |
| **Annotations** | No runtime annotation processing. |
| **Modules** | No Java module system support. |

---

## License

This project is licensed under the [MIT License](LICENSE).

---

## Author

**Nguyễn Sỹ Ben**

Built with curiosity about what lies beneath `java -jar`.

---

<div align="center">
<sub>Mini JVM Runtime is an educational project. It does not claim JVM specification compliance.</sub>
</div>
