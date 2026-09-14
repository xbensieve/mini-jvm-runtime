package dev.ben.minijvm.testutil;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test utility that compiles Java source strings in-memory using the host JDK compiler.
 * Allows tests to generate real, verified Java 21 .class byte arrays without committing binaries.
 */
public final class CompilerTestUtils {

    private CompilerTestUtils() {}

    public static byte[] compile(String className, String sourceCode) {
        Map<String, byte[]> result = compileAll(Map.of(className, sourceCode));
        byte[] bytes = result.get(className);
        if (bytes == null) {
            throw new IllegalStateException("No class bytes generated for " + className);
        }
        return bytes;
    }

    public static Map<String, byte[]> compileAll(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("System Java compiler not available. Ensure JDK is used, not JRE.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdFileManager = compiler.getStandardFileManager(diagnostics, null, null);

        Map<String, ByteArrayOutputStream> byteOutputMap = new HashMap<>();

        JavaFileManager fileManager = new ForwardingJavaFileManager<>(stdFileManager) {
            @Override
            public JavaFileObject getJavaFileForOutput(
                    Location location,
                    String className,
                    JavaFileObject.Kind kind,
                    FileObject sibling
            ) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byteOutputMap.put(className, baos);
                return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                    @Override
                    public OutputStream openOutputStream() {
                        return baos;
                    }
                };
            }
        };

        List<JavaFileObject> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String className = entry.getKey();
            String code = entry.getValue();
            sourceFiles.add(new SimpleJavaFileObject(
                    URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE
            ) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return code;
                }
            });
        }

        JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                List.of("-g"), // include debug information/attributes
                null,
                sourceFiles
        );

        boolean success = task.call();
        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed for sources:\n");
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                sb.append(diagnostic.toString()).append("\n");
            }
            throw new IllegalArgumentException(sb.toString());
        }

        Map<String, byte[]> resultMap = new HashMap<>();
        for (Map.Entry<String, ByteArrayOutputStream> entry : byteOutputMap.entrySet()) {
            resultMap.put(entry.getKey(), entry.getValue().toByteArray());
        }
        return resultMap;
    }
}
