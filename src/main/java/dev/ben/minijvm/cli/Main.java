package dev.ben.minijvm.cli;

import dev.ben.minijvm.classfile.AccessFlags;
import dev.ben.minijvm.classfile.ClassFile;
import dev.ben.minijvm.classfile.ClassFileReader;
import dev.ben.minijvm.classfile.MethodInfo;
import dev.ben.minijvm.debug.StateDumper;
import dev.ben.minijvm.debug.TraceLevel;
import dev.ben.minijvm.debug.TraceLogger;
import dev.ben.minijvm.interpreter.BytecodeDecoder;
import dev.ben.minijvm.interpreter.Interpreter;
import dev.ben.minijvm.opcode.Instruction;
import dev.ben.minijvm.runtime.ClassRepository;
import dev.ben.minijvm.runtime.ExceptionTableResolver;
import dev.ben.minijvm.runtime.FieldResolver;
import dev.ben.minijvm.runtime.Frame;
import dev.ben.minijvm.runtime.FrameStack;
import dev.ben.minijvm.runtime.Heap;
import dev.ben.minijvm.runtime.MethodResolver;
import dev.ben.minijvm.runtime.MethodSelector;
import dev.ben.minijvm.runtime.ObjectReference;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Command Line Interface (CLI) entry point for the Mini JVM Runtime.
 * Accepts a compiled .class file and target method, supporting both continuous "run"
 * and interactive "step" debugging modes.
 */
public final class Main {

    public static final String DEFAULT_METHOD = "main";

    public static void main(String[] args) {
        int exitCode = run(args, System.in, System.out, System.err);
        System.exit(exitCode);
    }

    /**
     * Testable execution runner for the Mini JVM CLI.
     *
     * @param args Command-line arguments.
     * @param in   Input stream for interactive step mode.
     * @param out  Standard output stream.
     * @param err  Standard error stream.
     * @return Exit code (0 for success, non-zero for failure).
     */
    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Objects.requireNonNull(in, "in cannot be null");
        Objects.requireNonNull(out, "out cannot be null");
        Objects.requireNonNull(err, "err cannot be null");

        CliConfig config;
        try {
            config = parseArgs(args);
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            printUsage(err);
            return 1;
        }

        if (config.help()) {
            printUsage(out);
            return 0;
        }

        if (config.classFilePath() == null) {
            err.println("Error: No class file specified.");
            printUsage(err);
            return 1;
        }

        Path path = Path.of(config.classFilePath());
        if (!Files.exists(path)) {
            err.println("Error: Class file not found: " + path.toAbsolutePath());
            return 1;
        }

        ClassFile classFile;
        try {
            classFile = ClassFileReader.read(path);
        } catch (Exception e) {
            err.println("Error reading class file: " + e.getMessage());
            return 1;
        }

        ClassRepository repository = new ClassRepository();
        repository.register(classFile);

        MethodInfo method = findMethod(classFile, config.methodName());
        if (method == null) {
            err.println(String.format("Error: Method '%s' with Code attribute not found in class '%s'.",
                    config.methodName(), classFile.thisClassName()));
            err.println("Available executable methods: " + listExecutableMethods(classFile));
            return 1;
        }

        Heap heap = new Heap(repository);
        MethodResolver methodResolver = new MethodResolver(repository);
        MethodSelector methodSelector = new MethodSelector(repository);
        FieldResolver fieldResolver = new FieldResolver(repository);
        ExceptionTableResolver exceptionTableResolver = new ExceptionTableResolver(repository);
        Interpreter interpreter = new Interpreter(
                new BytecodeDecoder(),
                methodResolver,
                methodSelector,
                fieldResolver,
                heap,
                exceptionTableResolver
        );

        Frame rootFrame = new Frame(classFile, method);
        prepareMethodArguments(rootFrame, classFile, method, heap);

        FrameStack frameStack = new FrameStack();
        frameStack.push(rootFrame);

        if (config.isStepMode()) {
            return runStepMode(interpreter, rootFrame, frameStack, in, out);
        } else {
            return runContinuousMode(interpreter, rootFrame, frameStack, config, out, err);
        }
    }

    private static int runContinuousMode(
            Interpreter interpreter,
            Frame rootFrame,
            FrameStack frameStack,
            CliConfig config,
            PrintStream out,
            PrintStream err
    ) {
        if (config.trace()) {
            TraceLogger logger = new TraceLogger(config.traceLevel(), out);
            interpreter.setListener(logger);
        }

        try {
            interpreter.execute(frameStack);
        } catch (Exception e) {
            err.println(String.format("Execution fault at PC %d (%s): %s",
                    rootFrame.lastInstructionPc(),
                    e.getClass().getSimpleName(),
                    e.getMessage() != null ? e.getMessage() : ""));
            return 1;
        }

        rootFrame.returnValue().ifPresent(val -> out.println("Result: " + val));
        out.println(String.format("Execution completed successfully (%s).", rootFrame.status()));
        return 0;
    }

    private static int runStepMode(
            Interpreter interpreter,
            Frame rootFrame,
            FrameStack frameStack,
            InputStream in,
            PrintStream out
    ) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));

        String className = rootFrame.classFile().map(ClassFile::thisClassName).orElse("<unknown>");
        String methodName = rootFrame.method().name(rootFrame.constantPool());
        String descriptor = rootFrame.method().descriptor(rootFrame.constantPool());

        out.println("Mini JVM Interactive Debugger");
        out.println("Class:  " + className);
        out.println("Method: " + methodName + descriptor);
        out.println("Type 'help' or 'h' for commands.\n");

        out.println("[Initial State]");
        out.println(StateDumper.dump(rootFrame, frameStack));

        try {
            while (true) {
                out.print("minijvm> ");
                out.flush();

                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                String cmd = line.trim();
                if (cmd.isEmpty() || cmd.equalsIgnoreCase("s") || cmd.equalsIgnoreCase("step")) {
                    if (frameStack.isEmpty() || frameStack.current().isCompleted()) {
                        out.println("Execution already completed (" + rootFrame.status() + ").");
                        continue;
                    }

                    Frame current = frameStack.current();
                    Instruction ins;
                    try {
                        ins = interpreter.step(current, frameStack);
                    } catch (Exception e) {
                        out.println("Execution fault: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        continue;
                    }

                    out.println("Step -> " + ins);
                    if (!frameStack.isEmpty()) {
                        out.println(StateDumper.dump(frameStack.current(), frameStack));
                    }

                    if (rootFrame.isCompleted()) {
                        out.println("Method execution completed (" + rootFrame.status() + ").");
                        rootFrame.returnValue().ifPresent(v -> out.println("Return value: " + v));
                    }
                } else if (cmd.equalsIgnoreCase("r") || cmd.equalsIgnoreCase("run")
                        || cmd.equalsIgnoreCase("c") || cmd.equalsIgnoreCase("continue")) {
                    out.println("Continuing execution to completion...");
                    try {
                        interpreter.execute(frameStack);
                    } catch (Exception e) {
                        out.println("Execution fault: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        return 1;
                    }
                    out.println("Execution completed (" + rootFrame.status() + ").");
                    rootFrame.returnValue().ifPresent(v -> out.println("Return value: " + v));
                    return 0;
                } else if (cmd.equalsIgnoreCase("d") || cmd.equalsIgnoreCase("dump")) {
                    if (!frameStack.isEmpty()) {
                        out.println(StateDumper.dump(frameStack.current(), frameStack));
                    } else {
                        out.println(StateDumper.dump(rootFrame, null));
                    }
                } else if (cmd.equalsIgnoreCase("h") || cmd.equalsIgnoreCase("help")) {
                    printStepHelp(out);
                } else if (cmd.equalsIgnoreCase("q") || cmd.equalsIgnoreCase("quit") || cmd.equalsIgnoreCase("exit")) {
                    out.println("Exiting debugger.");
                    return 0;
                } else {
                    out.println("Unknown command: '" + cmd + "'. Type 'help' for available commands.");
                }
            }
        } catch (Exception e) {
            out.println("Error reading debugger input: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private static void printStepHelp(PrintStream out) {
        out.println("Available commands:");
        out.println("  step, s, <Enter>     - Execute one instruction and dump state");
        out.println("  run, r, c, continue  - Continue execution until completion");
        out.println("  dump, d              - Dump current call stack and frame state");
        out.println("  help, h              - Display this help message");
        out.println("  quit, q              - Terminate the debugging session");
    }

    private static void prepareMethodArguments(Frame rootFrame, ClassFile classFile, MethodInfo method, Heap heap) {
        String desc = method.descriptor(classFile.constantPool());
        boolean isStatic = AccessFlags.isStatic(method.accessFlags());

        int localIdx = 0;
        if (!isStatic) {
            // Non-static method receives 'this' reference at local slot 0
            ObjectReference receiver = heap.allocate(classFile);
            rootFrame.locals().set(localIdx++, receiver);
        }

        if (desc.startsWith("([Ljava/lang/String;)")) {
            // Standard Java main method main(String[] args) -> allocate empty string array
            ObjectReference stringArray = heap.allocateArray("[Ljava/lang/String;", 0);
            rootFrame.locals().set(localIdx, stringArray);
        }
    }

    private static MethodInfo findMethod(ClassFile classFile, String methodName) {
        for (MethodInfo method : classFile.methods()) {
            if (method.name(classFile.constantPool()).equals(methodName) && method.code().isPresent()) {
                return method;
            }
        }
        return null;
    }

    private static List<String> listExecutableMethods(ClassFile classFile) {
        List<String> names = new ArrayList<>();
        for (MethodInfo method : classFile.methods()) {
            if (method.code().isPresent()) {
                names.add(method.name(classFile.constantPool()) + method.descriptor(classFile.constantPool()));
            }
        }
        return names;
    }

    private static CliConfig parseArgs(String[] args) {
        String classFilePath = null;
        String methodName = DEFAULT_METHOD;
        boolean stepMode = false;
        boolean trace = false;
        TraceLevel traceLevel = TraceLevel.INSTRUCTIONS;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                help = true;
            } else if (arg.equals("-s") || arg.equals("--step")) {
                stepMode = true;
            } else if (arg.equals("-r") || arg.equals("--run")) {
                stepMode = false;
            } else if (arg.equals("-t") || arg.equals("--trace")) {
                trace = true;
            } else if (arg.equals("--mode")) {
                if (++i >= args.length) {
                    throw new IllegalArgumentException("Missing argument for --mode");
                }
                String mode = args[i].toLowerCase();
                if (mode.equals("step")) {
                    stepMode = true;
                } else if (mode.equals("run")) {
                    stepMode = false;
                } else {
                    throw new IllegalArgumentException("Invalid mode: " + mode + " (expected 'run' or 'step')");
                }
            } else if (arg.equals("-m") || arg.equals("--method")) {
                if (++i >= args.length) {
                    throw new IllegalArgumentException("Missing argument for --method");
                }
                methodName = args[i];
            } else if (arg.equals("-l") || arg.equals("--level")) {
                if (++i >= args.length) {
                    throw new IllegalArgumentException("Missing argument for --level");
                }
                String levelStr = args[i].toUpperCase();
                try {
                    traceLevel = TraceLevel.valueOf(levelStr);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid trace level: " + args[i] + " (expected 'INSTRUCTIONS' or 'DETAILED')");
                }
            } else if (!arg.startsWith("-")) {
                if (classFilePath == null) {
                    classFilePath = arg;
                } else if (methodName.equals(DEFAULT_METHOD)) {
                    // Optional positional method name: minijvm <classFile> <methodName>
                    methodName = arg;
                } else {
                    throw new IllegalArgumentException("Unexpected positional argument: " + arg);
                }
            } else {
                throw new IllegalArgumentException("Unrecognized option: " + arg);
            }
        }

        return new CliConfig(classFilePath, methodName, stepMode, trace, traceLevel, help);
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: minijvm <class-file> [method] [options]");
        out.println();
        out.println("Arguments:");
        out.println("  <class-file>               Path to the compiled .class file to execute");
        out.println("  [method]                   Optional name of the method to execute (default: main)");
        out.println();
        out.println("Options:");
        out.println("  -m, --method <name>        Target method name to execute (default: main)");
        out.println("  --mode <run|step>          Execution mode: continuous 'run' or interactive 'step' (default: run)");
        out.println("  -s, --step                 Shortcut for --mode step");
        out.println("  -r, --run                  Shortcut for --mode run");
        out.println("  -t, --trace                Enable deterministic instruction trace in run mode");
        out.println("  -l, --level <level>        Trace detail level: INSTRUCTIONS or DETAILED (default: INSTRUCTIONS)");
        out.println("  -h, --help                 Display this help message");
    }

    private record CliConfig(
            String classFilePath,
            String methodName,
            boolean isStepMode,
            boolean trace,
            TraceLevel traceLevel,
            boolean help
    ) {}
}
