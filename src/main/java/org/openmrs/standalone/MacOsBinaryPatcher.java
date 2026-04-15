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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.Util;

/**
 * Makes the embedded MariaDB binaries shipped by mariaDB4j-db-macos-arm64 work on
 * macOS arm64 machines that do not have Homebrew installed.
 *
 * <p>The upstream binaries (mariadbd, mariadb, mariadb-check, mariadb-dump,
 * libmariadb.3.dylib, libmysqlclient.dylib) link against absolute Homebrew paths
 * (/opt/homebrew/opt/pcre2/..., /opt/homebrew/opt/openssl@3/...). Without those
 * formulas installed the dynamic loader fails with "Library not loaded" and the
 * standalone never starts. We work around this by:
 *
 * <ol>
 *   <li>Pre-extracting the MariaDB binaries from the classpath into the configured
 *       base directory (mariaDB4j's own unpacker will then skip them).</li>
 *   <li>Copying the bundled pcre2 / openssl@3 dylibs from
 *       {@code native/macos-arm64/lib/} (shipped alongside the standalone) into
 *       {@code <baseDir>/lib/}.</li>
 *   <li>Rewriting every offending {@code LC_LOAD_DYLIB} entry in the extracted
 *       binaries via {@code install_name_tool -change} so they resolve to the
 *       bundled copies via {@code @loader_path/../lib/<name>}.</li>
 * </ol>
 *
 * <p>{@code install_name_tool} preserves file size when the new path is shorter
 * than the original, so mariaDB4j's later size-based "is this already extracted?"
 * check stays valid and the patched binaries persist.
 *
 * <p>Idempotent and a no-op on every platform other than macOS arm64.
 */
public final class MacOsBinaryPatcher {

    private static final String BUNDLED_LIBS_DIR = "native/macos-arm64/lib";

    /** Homebrew load-command path -> basename used in @loader_path/../lib/<basename>. */
    private static final Map<String, String> PATH_REWRITES = new LinkedHashMap<>();
    static {
        PATH_REWRITES.put("/opt/homebrew/opt/pcre2/lib/libpcre2-8.0.dylib",     "libpcre2-8.0.dylib");
        PATH_REWRITES.put("/opt/homebrew/opt/openssl@3/lib/libssl.3.dylib",     "libssl.3.dylib");
        PATH_REWRITES.put("/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib",  "libcrypto.3.dylib");
    }

    private static final List<String> BUNDLED_DYLIBS = Arrays.asList(
            "libpcre2-8.0.dylib", "libssl.3.dylib", "libcrypto.3.dylib");

    private MacOsBinaryPatcher() {}

    /**
     * Performs the macOS-arm64 binary patching if the current platform requires it.
     * On any other platform this is a no-op.
     */
    public static void patchIfNeeded(DBConfiguration config, File baseDir) throws IOException, InterruptedException {
        if (!isMacOsArm64()) {
            return;
        }

        String binariesClassPathLocation = config.getBinariesClassPathLocation();
        if (binariesClassPathLocation == null || binariesClassPathLocation.isEmpty()) {
            // mariaDB4j is configured not to unpack from classpath; nothing to patch.
            return;
        }

        // 1. Pre-extract the MariaDB binaries so we can patch them before mariaDB4j's
        //    install step launches mariadb-install-db / mariadbd.
        Util.extractFromClasspathToFile(binariesClassPathLocation, baseDir);

        // 2. Copy bundled dylibs into <baseDir>/lib/.
        File targetLibDir = new File(baseDir, "lib");
        if (!targetLibDir.exists() && !targetLibDir.mkdirs()) {
            throw new IOException("Could not create " + targetLibDir);
        }
        File bundledSrcDir = locateBundledLibsDir();
        for (String name : BUNDLED_DYLIBS) {
            File src = new File(bundledSrcDir, name);
            if (!src.isFile()) {
                throw new IOException("Bundled dylib missing: " + src.getAbsolutePath()
                        + " (expected to ship at " + BUNDLED_LIBS_DIR + "/ inside the standalone)");
            }
            File dst = new File(targetLibDir, name);
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // ensure executable bit unchanged but writable for any later re-runs
            dst.setReadable(true, false);
        }

        // 3. Rewrite load commands in every extracted binary that has a broken path.
        patchAllUnder(new File(baseDir, "bin"));
        patchAllUnder(new File(baseDir, "libs"));
    }

    private static void patchAllUnder(File dir) throws IOException, InterruptedException {
        if (!dir.isDirectory()) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir.toPath())) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                File f = p.toFile();
                if (!f.isFile()) {
                    continue;
                }
                patchBinary(f);
            }
        }
    }

    private static void patchBinary(File file) throws IOException, InterruptedException {
        String otool = run("otool", "-L", file.getAbsolutePath());
        if (otool == null) {
            // Not a Mach-O file; otool failed harmlessly.
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : PATH_REWRITES.entrySet()) {
            if (!otool.contains(entry.getKey())) {
                continue;
            }
            String newPath = "@loader_path/../lib/" + entry.getValue();
            int rc = runReturningExitCode("install_name_tool", "-change",
                    entry.getKey(), newPath, file.getAbsolutePath());
            if (rc != 0) {
                throw new IOException("install_name_tool -change failed for "
                        + file.getAbsolutePath() + " (" + entry.getKey() + ")");
            }
            changed = true;
        }
        if (changed) {
            // Re-sign ad-hoc; rewriting load commands invalidates any existing signature.
            runReturningExitCode("codesign", "--force", "--sign", "-", file.getAbsolutePath());
        }
    }

    /**
     * Runs a command and returns stdout, or {@code null} if it exited non-zero.
     * Used for read-only inspection commands like {@code otool -L}.
     */
    private static String run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        int rc = p.waitFor();
        return rc == 0 ? new String(out) : null;
    }

    /** Runs a command, inheriting stdio, and returns its exit code. */
    private static int runReturningExitCode(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        // drain output to avoid blocking
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    /**
     * Locates the directory containing the bundled dylibs. In the packaged
     * standalone these ship at {@code <install-root>/native/macos-arm64/lib/}.
     * For tests / dev runs from the source tree, fall back to
     * {@code src/main/native/macos-arm64/lib/}.
     */
    private static File locateBundledLibsDir() throws IOException {
        File primary = new File(BUNDLED_LIBS_DIR).getAbsoluteFile();
        if (primary.isDirectory()) {
            return primary;
        }
        File fallback = new File("src/main/" + BUNDLED_LIBS_DIR).getAbsoluteFile();
        if (fallback.isDirectory()) {
            return fallback;
        }
        throw new IOException("Could not locate bundled macOS arm64 dylibs. Tried: "
                + primary + " and " + fallback);
    }

    private static boolean isMacOsArm64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"));
    }
}
