/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link WindowsRuntimePatcher}. The native preload itself can only run on Windows
 * x64, so on every other platform the public entry point must be a clean no-op. The library
 * resolution logic (which DLLs are picked up, in what order, and how missing ones are handled)
 * is platform independent and is verified directly against the real production method.
 */
class WindowsRuntimePatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void preloadIfNeeded_isNoOpAndDoesNotThrowOnNonWindows() {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"),
            "preload is a no-op only off Windows");
        assertDoesNotThrow(WindowsRuntimePatcher::preloadIfNeeded);
    }

    @Test
    void resolveLibraries_returnsEveryBundledDllInDependencyLoadOrder() throws Exception {
        for (String name : WindowsRuntimePatcher.BUNDLED_DLLS) {
            Files.createFile(tempDir.resolve(name));
        }

        List<File> resolved = WindowsRuntimePatcher.resolveLibraries(tempDir.toFile());

        assertEquals(WindowsRuntimePatcher.BUNDLED_DLLS,
            resolved.stream().map(File::getName).collect(Collectors.toList()),
            "all bundled DLLs must resolve in the declared dependency order");
    }

    @Test
    void resolveLibraries_skipsMissingDllsButPreservesOrder() throws Exception {
        // vcruntime140_1.dll is deliberately absent; the other two must still resolve in order.
        Files.createFile(tempDir.resolve("vcruntime140.dll"));
        Files.createFile(tempDir.resolve("msvcp140.dll"));

        List<File> resolved = WindowsRuntimePatcher.resolveLibraries(tempDir.toFile());

        assertEquals(Arrays.asList("vcruntime140.dll", "msvcp140.dll"),
            resolved.stream().map(File::getName).collect(Collectors.toList()),
            "missing DLLs are skipped while declared order is preserved");
    }

    @Test
    void systemRuntimePresent_trueWhenSystemDirHasMsvcp140() throws Exception {
        Files.createFile(tempDir.resolve("msvcp140.dll"));
        assertTrue(WindowsRuntimePatcher.systemRuntimePresent(tempDir.toFile()),
            "an installed system runtime must be detected so the bundled copy does not shadow it");
    }

    @Test
    void systemRuntimePresent_falseWhenSystemDirLacksMsvcp140() {
        // tempDir exists but is empty: this is the clean-Windows case the bundle exists to fix.
        assertFalse(WindowsRuntimePatcher.systemRuntimePresent(tempDir.toFile()),
            "a machine without the runtime must NOT be considered to have it");
    }

    @Test
    void resolveLibraries_returnsEmptyWhenNoDllsPresent() {
        List<File> resolved = WindowsRuntimePatcher.resolveLibraries(tempDir.toFile());
        assertEquals(0, resolved.size(), "an empty bundle dir resolves to no libraries");
    }

    /**
     * Guards the "it just runs on Windows" guarantee: the actual MSVC runtime DLLs must be
     * committed in the source tree so the assembly ships them. Exercises the real production
     * locate + resolve path against the real bundle, so deleting or forgetting a DLL fails the
     * build instead of silently shipping a standalone that breaks on a clean Windows box.
     *
     * <p>Asserts the full SHA-256 of each DLL (not just a PE header): a byte-corrupted but
     * still-{@code MZ}-headed DLL would pass a header check yet fail at runtime on Windows with
     * the same UnsatisfiedLinkError this slice exists to prevent. The expected hashes are the
     * ones pinned in this directory's README.md (extracted from the official, hash-verified
     * VC_redist.x64.exe); refreshing the DLLs means updating both.
     */
    @Test
    void bundledDir_shipsEveryRequiredDllWithExpectedContent() throws Exception {
        Map<String, String> expectedSha256 = new HashMap<>();
        expectedSha256.put("vcruntime140.dll",   "d5e4d9a3e835fa679450145d6a7d94e36573a509317111904d9b3712c30d9066");
        expectedSha256.put("vcruntime140_1.dll", "1f2d41c4aa5db0bc33ebf7b66d72943a817d7ce6cbe880502a9403823633093f");
        expectedSha256.put("msvcp140.dll",       "0f885b509a685d2bbfa652fed26b5fb31d88fbdab0a978c641d1c7b8aa460aa9");
        assertEquals(WindowsRuntimePatcher.BUNDLED_DLLS.size(), expectedSha256.size(),
            "this test must pin a hash for every bundled DLL");

        List<File> resolved = WindowsRuntimePatcher.resolveLibraries(
            WindowsRuntimePatcher.locateBundledLibsDir());

        assertEquals(WindowsRuntimePatcher.BUNDLED_DLLS,
            resolved.stream().map(File::getName).collect(Collectors.toList()),
            "every required MSVC runtime DLL must be committed under " + WindowsRuntimePatcher.BUNDLED_LIBS_DIR);

        for (File dll : resolved) {
            assertEquals(expectedSha256.get(dll.getName()), sha256(dll),
                dll.getName() + " content must match the hash pinned in README.md (no corruption / right version)");
        }
    }

    private static String sha256(File file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
