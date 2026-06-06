package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineStripTest {

    private static final String QUARANTINE_ATTRIBUTE = "com.apple.quarantine";

    @TempDir
    Path tempDir;

    @Test
    void stripQuarantineAttributes_shouldRemoveTheAttributeFromAllFilesInTheTree() throws IOException {
        Path bin = Files.createDirectories(tempDir.resolve("database").resolve("bin"));
        Path binary = Files.createFile(bin.resolve("mariadbd"));
        Path dylib = Files.createFile(
            Files.createDirectories(tempDir.resolve("database").resolve("lib")).resolve("libpcre2-8.0.dylib"));
        Path unrelated = Files.createFile(tempDir.resolve("README.txt"));

        assumeTrue(setQuarantine(binary) && setQuarantine(dylib),
            "file system does not support user-defined extended attributes");

        StandaloneUtil.stripQuarantineAttributes(tempDir.toFile());

        assertFalse(hasQuarantine(binary), "quarantine attribute should be stripped from binaries");
        assertFalse(hasQuarantine(dylib), "quarantine attribute should be stripped from dylibs");
        assertTrue(Files.exists(unrelated), "files without the attribute are untouched");
    }

    private boolean setQuarantine(Path path) {
        try {
            UserDefinedFileAttributeView view =
                Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
            if (view == null) {
                return false;
            }
            view.write(QUARANTINE_ATTRIBUTE, ByteBuffer.wrap("0083;0;Safari;".getBytes(StandardCharsets.UTF_8)));
            return view.list().contains(QUARANTINE_ATTRIBUTE);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasQuarantine(Path path) throws IOException {
        UserDefinedFileAttributeView view =
            Files.getFileAttributeView(path, UserDefinedFileAttributeView.class);
        return view != null && view.list().contains(QUARANTINE_ATTRIBUTE);
    }
}
