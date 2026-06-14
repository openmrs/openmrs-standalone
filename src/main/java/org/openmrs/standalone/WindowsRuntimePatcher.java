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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Makes ONNX Runtime (used by the chartsearchai / querystore embedding provider) load on
 * Windows x64 machines that do not have the Microsoft Visual C++ Redistributable installed.
 *
 * <p>The {@code com.microsoft.onnxruntime} JAR extracts {@code onnxruntime.dll} into a temp
 * directory and loads it via {@code System.load()} on first use. That DLL links against the
 * MSVC runtime ({@code vcruntime140.dll}, {@code vcruntime140_1.dll}, {@code msvcp140.dll}),
 * which is NOT part of a clean Windows install. When those are absent the loader fails with
 * <pre>java.lang.UnsatisfiedLinkError: ...\onnxruntime.dll: A dynamic link library (DLL)
 * initialization routine failed</pre>
 * and every chartsearchai query then dies with {@code NoClassDefFoundError: Could not
 * initialize class ai.onnxruntime.OrtEnvironment}.
 *
 * <p>We work around this by shipping the three MSVC runtime DLLs under
 * {@code native/windows-x64/lib/} (alongside the macOS dylibs handled by
 * {@link MacOsBinaryPatcher}) and pre-loading them by absolute path into this JVM before the
 * embedded Tomcat webapp starts. Tomcat runs in the same process, so once these modules are
 * resident the Windows loader satisfies {@code onnxruntime.dll}'s imports from the
 * already-loaded modules (matched by base name) regardless of where the DLL was extracted.
 *
 * <p>Load order matters: {@code msvcp140.dll} depends on the {@code vcruntime140} pair, so
 * those are loaded first.
 *
 * <p>The bundled DLLs are a <em>fallback</em>, not an override: if the machine already has the
 * Visual C++ Redistributable installed (its DLLs live in {@code %SystemRoot%\System32}), this
 * does nothing and lets ONNX use that system copy. Force-loading our bundled copy would shadow
 * the system one for {@code onnxruntime.dll}'s imports (the loader binds an import to whatever
 * module of that base name is already resident), which could break ONNX on a machine whose
 * system runtime is newer than ours. So we only inject the bundle when the runtime is absent -
 * the clean-Windows case this exists to fix.
 *
 * <p>Best effort and a no-op on every platform other than Windows x64. If the bundled DLLs are
 * absent (e.g. a build that did not ship them), any failure to pre-load is logged and ignored -
 * ONNX will simply fail the same way it would have without this class.
 */
public final class WindowsRuntimePatcher {

    static final String BUNDLED_LIBS_DIR = "native/windows-x64/lib";

    /**
     * MSVC runtime DLLs to pre-load, in dependency order: the {@code vcruntime140} pair must be
     * resident before {@code msvcp140.dll}, which links against them.
     */
    static final List<String> BUNDLED_DLLS = Arrays.asList(
            "vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll");

    private WindowsRuntimePatcher() {}

    /**
     * Pre-loads the bundled MSVC runtime DLLs into this JVM if running on Windows x64.
     * A no-op on any other platform. Never throws: a missing bundle or a failed load is logged
     * and execution continues.
     */
    public static void preloadIfNeeded() {
        if (!isWindowsX64()) {
            return;
        }

        if (systemRuntimePresent(windowsSystemDir())) {
            // The machine already has the Visual C++ Redistributable; use it rather than
            // shadowing it with our (possibly older) bundled copy.
            return;
        }

        File dir;
        try {
            dir = locateBundledLibsDir();
        }
        catch (IOException e) {
            System.out.println("ChartSearchAI: no bundled MSVC runtime found (" + e.getMessage()
                    + "); relying on the system Visual C++ Redistributable for ONNX Runtime.");
            return;
        }

        List<File> libraries = resolveLibraries(dir);
        if (libraries.size() < BUNDLED_DLLS.size()) {
            System.out.println("ChartSearchAI: only " + libraries.size() + " of "
                    + BUNDLED_DLLS.size() + " bundled MSVC runtime DLLs present in " + dir
                    + "; ONNX Runtime may fail to load if the system lacks the Visual C++ Redistributable.");
        }

        for (File dll : libraries) {
            try {
                System.load(dll.getAbsolutePath());
            }
            catch (Throwable t) {
                // Throwable (not Exception) is deliberate: System.load reports failure via
                // UnsatisfiedLinkError, which is an Error - an already-resident system copy, an
                // arch mismatch, or a corrupt file. ONNX can still succeed via the system runtime,
                // so log and keep going rather than abort startup.
                System.out.println("ChartSearchAI: could not pre-load " + dll.getAbsolutePath()
                        + " (" + t + "); continuing.");
            }
        }
    }

    /**
     * Returns, in declared dependency order, the bundled DLLs that actually exist under
     * {@code dir}. Missing entries are skipped (not an error) so the method degrades gracefully
     * when a build ships only some of the runtime files.
     */
    static List<File> resolveLibraries(File dir) {
        List<File> resolved = new ArrayList<>();
        for (String name : BUNDLED_DLLS) {
            File f = new File(dir, name);
            if (f.isFile()) {
                resolved.add(f);
            }
        }
        return resolved;
    }

    /**
     * Locates the directory containing the bundled DLLs. In the packaged standalone these ship
     * at {@code <install-root>/native/windows-x64/lib/}. For tests / dev runs from the source
     * tree, fall back to {@code src/main/native/windows-x64/lib/}.
     */
    static File locateBundledLibsDir() throws IOException {
        File primary = new File(BUNDLED_LIBS_DIR).getAbsoluteFile();
        if (primary.isDirectory()) {
            return primary;
        }
        File fallback = new File("src/main/" + BUNDLED_LIBS_DIR).getAbsoluteFile();
        if (fallback.isDirectory()) {
            return fallback;
        }
        throw new IOException("could not locate bundled Windows x64 runtime DLLs; tried "
                + primary + " and " + fallback);
    }

    static boolean isWindowsX64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    /**
     * Whether the Windows system directory already holds the Visual C++ runtime. The
     * redistributable installs {@code vcruntime140.dll} / {@code msvcp140.dll} directly into
     * {@code System32}; the presence of {@code msvcp140.dll} (the C++ standard library, the
     * heaviest of the set) is a reliable proxy for "the runtime is installed system-wide".
     */
    static boolean systemRuntimePresent(File systemDir) {
        return new File(systemDir, "msvcp140.dll").isFile();
    }

    /** The 64-bit Windows system directory, e.g. {@code C:\Windows\System32}. */
    private static File windowsSystemDir() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isEmpty()) {
            root = "C:\\Windows";
        }
        return new File(root, "System32");
    }
}
