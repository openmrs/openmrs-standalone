package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link StandaloneUtil#stripQuarantineAttributes(File)} against the real macOS
 * quarantine attribute as written by the system {@code xattr} tool - the same form browsers
 * and Archive Utility produce. The JDK's UserDefinedFileAttributeView cannot even see this
 * attribute on macOS, so testing through Java file APIs would silently validate a no-op;
 * both the setup and the assertions therefore go through {@code /usr/bin/xattr}.
 */
class QuarantineStripTest {

    private static final String ATTRIBUTE = "com.apple.quarantine";

    @TempDir
    Path tempDir;

    @Test
    void stripQuarantineAttributes_shouldRemoveTheRealAttributeFromAllFilesInTheTree() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac"),
            "macOS-specific behavior");
        assumeTrue(new File("/usr/bin/xattr").exists(), "xattr tool required");

        Path bin = Files.createDirectories(tempDir.resolve("database").resolve("bin"));
        Path binary = Files.createFile(bin.resolve("mariadbd"));
        Path dylib = Files.createFile(
            Files.createDirectories(tempDir.resolve("database").resolve("lib")).resolve("libpcre2-8.0.dylib"));

        setQuarantine(binary);
        setQuarantine(dylib);
        assertEquals(0, xattrCheck(binary), "precondition: attribute must be set");
        assertEquals(0, xattrCheck(dylib), "precondition: attribute must be set");

        StandaloneUtil.stripQuarantineAttributes(tempDir.toFile());

        assertEquals(1, xattrCheck(binary), "attribute should be gone from binaries");
        assertEquals(1, xattrCheck(dylib), "attribute should be gone from dylibs");
    }

    private void setQuarantine(Path path) throws IOException, InterruptedException {
        run("/usr/bin/xattr", "-w", ATTRIBUTE, "0083;00000000;Safari;TEST", path.toString());
    }

    /** @return xattr -p exit code: 0 when the attribute exists, non-zero when absent. */
    private int xattrCheck(Path path) throws IOException, InterruptedException {
        return run("/usr/bin/xattr", "-p", ATTRIBUTE, path.toString());
    }

    private int run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (java.io.InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096];
            while (in.read(b) != -1) { /* drain */ }
        }
        return p.waitFor();
    }
}
