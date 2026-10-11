package io.github.ibcmanager.workaround;

import io.github.ibcmanager.tests.TestSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.tools.ToolProvider;

/** Compiler-generated module descriptors; synthetic, not downloaded JavaFX code. */
final class JavafxModuleFixture {
    private static final Map<String, byte[]> DESCRIPTORS = new ConcurrentHashMap<>();
    private JavafxModuleFixture() { }

    static byte[] descriptor(String module, String version) throws IOException {
        String name = RuntimeCatalog.moduleName(module);
        return namedDescriptor(name, version, module.equals("web") ? "requires jdk.jsobject;" : "");
    }

    static synchronized byte[] namedDescriptor(String name, String version, String requires) throws IOException {
        String key = name + ":" + version + ":" + requires;
        byte[] cached = DESCRIPTORS.get(key);
        if (cached != null) return cached.clone();
        Path root = TestSupport.tempDirectory("module-descriptor");
        try {
            Path input = root.resolve("module-info.java");
            Files.writeString(input, "module " + name + " { " + requires + " }\n");
            Path output = Files.createDirectory(root.resolve("classes"));
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new IOException("The test fixture requires a JDK compiler");
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            int result = compiler.run(null, diagnostics, diagnostics, "--release", "17", "--module-version", version,
                    "-d", output.toString(), input.toString());
            if (result != 0) throw new IOException("Fixture compilation failed: " + diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = Files.readAllBytes(output.resolve("module-info.class"));
            DESCRIPTORS.put(key, bytes);
            return bytes.clone();
        } finally { TestSupport.deleteTree(root); }
    }
}
