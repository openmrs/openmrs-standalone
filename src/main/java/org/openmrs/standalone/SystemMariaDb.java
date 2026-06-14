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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Locates a system-installed MariaDB for Intel (x86_64) Macs.
 *
 * <p>mariaDB4j bundles MariaDB binaries for Linux x64, macOS arm64 and Windows x64 only, and no
 * maintained source publishes a modern x86_64 macOS build (the newest mariaDB4j-db-mac64 is
 * 10.2.11 from 2017, and Homebrew no longer ships x86_64 mariadb bottles). So on an Intel Mac the
 * standalone cannot ship an embedded database; instead it points mariaDB4j at a MariaDB the user
 * installed themselves (e.g. {@code brew install mariadb}), via
 * {@code DBConfigurationBuilder.setUnpackingFromClasspath(false)} + {@code setBaseDir(baseDir)} -
 * mariaDB4j then resolves {@code bin/mariadbd}, {@code bin/mariadb-install-db}, {@code share/}
 * etc. under that baseDir instead of unpacking from the classpath.
 *
 * <p>The data directory is unaffected: it stays the writable app data dir, so only the read-only
 * program files come from the system install. MariaDB's {@code --basedir} resolution finds the
 * Homebrew keg's data files under {@code <baseDir>/share/mysql/} (verified: {@code
 * mariadb-install-db --basedir=<keg>} succeeds), so no explicit share/messages dir is needed.
 */
public final class SystemMariaDb {

    /** Homebrew install prefixes: Intel at {@code /usr/local}, Apple Silicon at {@code /opt/homebrew}. */
    private static final String[] HOMEBREW_PREFIXES = { "/usr/local", "/opt/homebrew" };

    /** Server binary names, in preference order (modern MariaDB, then legacy). */
    static final List<String> SERVER_BINARIES = Arrays.asList("mariadbd", "mysqld");

    /**
     * Orders Homebrew keg dirs: the unversioned {@code mariadb} keg first (what
     * {@code brew install mariadb} produces and keeps current), then versioned kegs
     * ({@code mariadb@11.4}, ...) in deterministic lexical order. Not semver-ordered - just
     * stable across runs so a multi-version machine resolves the same keg every time.
     */
    static final Comparator<File> KEG_ORDER = (a, b) -> {
        boolean au = a.getName().equals("mariadb");
        boolean bu = b.getName().equals("mariadb");
        if (au != bu) {
            return au ? -1 : 1;
        }
        return a.getName().compareTo(b.getName());
    };

    private SystemMariaDb() {}

    /**
     * The resolved system MariaDB baseDir, or {@code null} if none is installed. Searches Homebrew
     * keg locations first (their layout matches what mariaDB4j expects most closely), then the
     * {@code PATH}, then common prefixes.
     */
    static File locateBaseDir() {
        return locateBaseDir(candidateBinDirs());
    }

    /**
     * The lib directory to hand mariaDB4j for a system install at {@code baseDir}.
     *
     * <p>mariaDB4j defaults {@code libDir} to {@code baseDir/libs} and unconditionally creates it
     * in {@code prepareDirectories()}. Against a system prefix that either fails (read-only) or
     * litters the install (e.g. a Homebrew keg) with an empty {@code libs} dir. We instead point
     * it at the install's real {@code lib} dir when present (so the dir already exists - no
     * creation - and the DYLD fallback path is correct), falling back to {@code baseDir} itself,
     * which is guaranteed to exist.
     */
    static File resolveLibDir(File baseDir) {
        File lib = new File(baseDir, "lib");
        return lib.isDirectory() ? lib : baseDir;
    }

    /**
     * Returns the baseDir (the parent of the first candidate {@code bin} directory that holds an
     * executable server binary), or {@code null} if none qualifies. baseDir is the parent of the
     * bin dir because mariaDB4j resolves executables as {@code baseDir/bin/<tool>}.
     */
    static File locateBaseDir(List<File> binDirs) {
        for (File bin : binDirs) {
            File baseDir = bin.getParentFile();
            if (baseDir == null) {
                // A bare/relative bin dir (e.g. a "bin" entry on PATH, or filesystem root) cannot
                // anchor the baseDir/bin/<tool> layout mariaDB4j needs; skip it so a well-formed
                // later candidate can still win rather than returning a null baseDir.
                continue;
            }
            for (String name : SERVER_BINARIES) {
                File server = new File(bin, name);
                if (server.isFile() && server.canExecute()) {
                    return baseDir;
                }
            }
        }
        return null;
    }

    /**
     * Candidate {@code bin} directories to probe, in preference order: Homebrew kegs (versioned
     * and unversioned) under each prefix, then {@code PATH} entries, then the prefix {@code bin}
     * and {@code sbin}. Intel Homebrew lives at {@code /usr/local}; {@code /opt/homebrew} is
     * included for completeness.
     */
    static List<File> candidateBinDirs() {
        List<File> dirs = new ArrayList<>();
        for (String prefix : HOMEBREW_PREFIXES) {
            File opt = new File(prefix, "opt");
            File[] kegs = opt.listFiles((dir, name) -> name.startsWith("mariadb"));
            if (kegs != null) {
                Arrays.sort(kegs, KEG_ORDER);
                for (File keg : kegs) {
                    dirs.add(new File(keg, "bin"));
                }
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    dirs.add(new File(entry));
                }
            }
        }
        for (String prefix : HOMEBREW_PREFIXES) {
            dirs.add(new File(prefix, "bin"));
            dirs.add(new File(prefix, "sbin"));
        }
        return dirs;
    }
}
