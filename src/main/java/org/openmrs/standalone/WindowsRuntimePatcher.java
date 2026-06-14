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
 * <p>The same 1114 error also occurs when the machine has an <em>older or partial</em> VC++
 * runtime: e.g. {@code msvcp140.dll} present but a stale version, or {@code vcruntime140_1.dll}
 * (added with VS2019) missing, so onnxruntime's C++ runtime init can't complete.
 *
 * <p>We work around both by shipping the three MSVC runtime DLLs under
 * {@code native/windows-x64/lib/} (alongside the macOS dylibs handled by
 * {@link MacOsBinaryPatcher}) and pre-loading them by absolute path into this JVM as early as
 * possible - always before the embedded Tomcat webapp (and therefore ONNX) starts. Tomcat runs
 * in the same process, so once these modules are resident the Windows loader satisfies
 * {@code onnxruntime.dll}'s imports from them (matched by base name) instead of from any
 * missing/older copy in {@code System32}, regardless of where ONNX extracted its DLL.
 *
 * <p>Load order matters: {@code msvcp140.dll} depends on the {@code vcruntime140} pair, so
 * those are loaded first.
 *
 * <p>We pre-load <em>unconditionally</em>. An earlier version skipped when
 * {@code System32\msvcp140.dll} already existed, to avoid shadowing a newer system runtime - but
 * that broke the common real case (a machine with an older/partial runtime present still failed
 * with 1114, because onnxruntime bound to the stale system copy and our bundle was never loaded).
 * The bundled DLLs come from the latest VC++ 2015-2022 redistributable, which is
 * forward-compatible with the onnxruntime build we ship, so loading them first is safe and is the
 * reliable fix.
 *
 * <p>Best effort and a no-op off Windows x64. Every decision and load result is logged, so a
 * residual failure is diagnosable from the log alone: if preload reports {@code 3/3 loaded} yet
 * ONNX still fails, the cause is <em>not</em> the VC++ runtime (look to CPU features, antivirus,
 * or a corrupt extraction).
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
     * and execution continues. Logs every step so a residual ONNX failure is diagnosable.
     */
    public static void preloadIfNeeded() {
        if (!isWindowsX64()) {
            return;
        }

        File dir;
        try {
            dir = locateBundledLibsDir();
        }
        catch (IOException e) {
            System.out.println("ChartSearchAI: no bundled MSVC runtime found (" + e.getMessage()
                    + "); ONNX Runtime will rely on the system Visual C++ runtime.");
            return;
        }

        List<File> libraries = resolveLibraries(dir);
        if (libraries.size() < BUNDLED_DLLS.size()) {
            System.out.println("ChartSearchAI: only " + libraries.size() + " of "
                    + BUNDLED_DLLS.size() + " bundled MSVC runtime DLLs present in " + dir);
        }

        int loaded = 0;
        for (File dll : libraries) {
            try {
                System.load(dll.getAbsolutePath());
                loaded++;
                System.out.println("ChartSearchAI: pre-loaded " + dll.getAbsolutePath());
            }
            catch (Throwable t) {
                // Throwable (not Exception) is deliberate: System.load reports failure via
                // UnsatisfiedLinkError, which is an Error (e.g. an already-resident copy of the
                // same base name, an arch mismatch, or a corrupt file). Keep going rather than
                // abort startup.
                System.out.println("ChartSearchAI: could not pre-load " + dll.getAbsolutePath()
                        + " (" + t + "); continuing.");
            }
        }
        System.out.println("ChartSearchAI: MSVC runtime preload complete - " + loaded + "/"
                + BUNDLED_DLLS.size() + " loaded from " + dir);
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
}
