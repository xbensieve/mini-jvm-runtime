package dev.ben.minijvm.cli;

import dev.ben.minijvm.testutil.CompilerTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliTest {

    private static Path fixtureClassPath;
    private static Path faultClassPath;

    @BeforeAll
    static void setup() throws Exception {
        String fixtureSrc = """
                package dev.ben.test;
                public class CliFixture {
                    public static void main(String[] args) {
                        int a = 10;
                        int b = 20;
                    }
                    public static int calculate() {
                        return 42;
                    }
                }
                """;
        byte[] fixtureBytes = CompilerTestUtils.compile("dev.ben.test.CliFixture", fixtureSrc);
        fixtureClassPath = Files.createTempFile("CliFixture", ".class");
        Files.write(fixtureClassPath, fixtureBytes);
        fixtureClassPath.toFile().deleteOnExit();

        String faultSrc = """
                package dev.ben.test;
                public class CliFaultFixture {
                    public static int fault() {
                        int a = 10;
                        int b = 0;
                        return a / b;
                    }
                }
                """;
        byte[] faultBytes = CompilerTestUtils.compile("dev.ben.test.CliFaultFixture", faultSrc);
        faultClassPath = Files.createTempFile("CliFaultFixture", ".class");
        Files.write(faultClassPath, faultBytes);
        faultClassPath.toFile().deleteOnExit();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (fixtureClassPath != null) {
            Files.deleteIfExists(fixtureClassPath);
        }
        if (faultClassPath != null) {
            Files.deleteIfExists(faultClassPath);
        }
    }

    private ExecutionResult runCli(String... args) {
        return runCliWithInput("", args);
    }

    private ExecutionResult runCliWithInput(String input, String... args) {
        InputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outBaos = new ByteArrayOutputStream();
        ByteArrayOutputStream errBaos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBaos, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBaos, true, StandardCharsets.UTF_8);

        int exitCode = Main.run(args, in, out, err);
        return new ExecutionResult(exitCode, outBaos.toString(StandardCharsets.UTF_8), errBaos.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Displays help information with -h / --help and returns 0")
    void testCliHelp() {
        ExecutionResult res1 = runCli("--help");
        assertEquals(0, res1.exitCode());
        assertTrue(res1.out().contains("Usage: minijvm <class-file>"));

        ExecutionResult res2 = runCli("-h");
        assertEquals(0, res2.exitCode());
        assertTrue(res2.out().contains("Usage: minijvm <class-file>"));
    }

    @Test
    @DisplayName("Executes default main method in continuous run mode successfully")
    void testCliRunDefaultMain() {
        ExecutionResult res = runCli(fixtureClassPath.toString());
        assertEquals(0, res.exitCode());
        assertTrue(res.out().contains("Execution completed successfully"));
    }

    @Test
    @DisplayName("Executes specified method in continuous run mode and prints return value")
    void testCliRunSpecifiedMethod() {
        ExecutionResult res = runCli(fixtureClassPath.toString(), "--method", "calculate");
        assertEquals(0, res.exitCode());
        assertTrue(res.out().contains("Result: IntValue[42]"));
        assertTrue(res.out().contains("Execution completed successfully"));
    }

    @Test
    @DisplayName("Emits deterministic instruction trace in run mode when --trace flag is passed")
    void testCliRunWithTrace() {
        ExecutionResult res = runCli(fixtureClassPath.toString(), "--method", "calculate", "--trace");
        assertEquals(0, res.exitCode());
        assertTrue(res.out().contains("bipush 42"));
        assertTrue(res.out().contains("ireturn"));
        assertTrue(res.out().contains("Result: IntValue[42]"));
    }

    @Test
    @DisplayName("Executes interactive step mode stepping through instructions to completion")
    void testCliStepMode() {
        // Feed step commands then continue
        String input = "s\ns\nc\n";
        ExecutionResult res = runCliWithInput(input, fixtureClassPath.toString(), "--method", "calculate", "--mode", "step");
        assertEquals(0, res.exitCode());
        assertTrue(res.out().contains("Mini JVM Interactive Debugger"));
        assertTrue(res.out().contains("[Initial State]"));
        assertTrue(res.out().contains("Step -> 0: bipush 42"));
        assertTrue(res.out().contains("Continuing execution to completion..."));
        assertTrue(res.out().contains("Return value: IntValue[42]"));
    }

    @Test
    @DisplayName("Executes interactive step mode dump and help commands")
    void testCliStepModeCommands() {
        String input = "help\ndump\nquit\n";
        ExecutionResult res = runCliWithInput(input, fixtureClassPath.toString(), "--method", "calculate", "-s");
        assertEquals(0, res.exitCode());
        assertTrue(res.out().contains("Available commands:"));
        assertTrue(res.out().contains("FrameStack depth: 1"));
        assertTrue(res.out().contains("Exiting debugger."));
    }

    @Test
    @DisplayName("Returns exit code 1 when no class file is specified")
    void testCliMissingClassFile() {
        ExecutionResult res = runCli();
        assertEquals(1, res.exitCode());
        assertTrue(res.err().contains("Error: No class file specified."));
    }

    @Test
    @DisplayName("Returns exit code 1 when class file does not exist")
    void testCliNonExistentClassFile() {
        ExecutionResult res = runCli("does-not-exist.class");
        assertEquals(1, res.exitCode());
        assertTrue(res.err().contains("Error: Class file not found:"));
    }

    @Test
    @DisplayName("Returns exit code 1 when target method does not exist")
    void testCliNonExistentMethod() {
        ExecutionResult res = runCli(fixtureClassPath.toString(), "--method", "missingMethod");
        assertEquals(1, res.exitCode());
        assertTrue(res.err().contains("Error: Method 'missingMethod' with Code attribute not found"));
    }

    @Test
    @DisplayName("Returns exit code 1 when runtime execution faults")
    void testCliExecutionFault() {
        ExecutionResult res = runCli(faultClassPath.toString(), "--method", "fault");
        assertEquals(1, res.exitCode());
        assertTrue(res.err().contains("Execution fault at PC 7"));
        assertTrue(res.err().contains("ArithmeticFaultException"));
        assertTrue(res.err().contains("/ by zero"));
    }

    @Test
    @DisplayName("Handles invalid CLI flags gracefully")
    void testCliInvalidFlags() {
        ExecutionResult res = runCli("--unknown-flag");
        assertEquals(1, res.exitCode());
        assertTrue(res.err().contains("Error: Unrecognized option: --unknown-flag"));
    }

    private record ExecutionResult(int exitCode, String out, String err) {}
}
