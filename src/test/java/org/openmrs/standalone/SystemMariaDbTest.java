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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link SystemMariaDb#locateBaseDir(java.util.List)}, the pure core that picks a
 * system MariaDB install for Intel Macs (which have no bundled binary). The candidate-directory
 * assembly reads {@code PATH} / Homebrew locations and is environment-specific; the resolution
 * logic that turns candidate bin dirs into a usable baseDir is platform independent and is
 * verified here against real files.
 */
class SystemMariaDbTest {

    @TempDir
    Path tempDir;

    /** Creates {@code <prefix>/bin/<name>} as an executable file and returns the bin dir. */
    private File executableServer(String prefix, String name) throws Exception {
        File bin = tempDir.resolve(prefix).resolve("bin").toFile();
        bin.mkdirs();
        File server = new File(bin, name);
        Files.createFile(server.toPath());
        server.setExecutable(true, false);
        return bin;
    }

    @Test
    void locateBaseDir_returnsParentOfBinDirHoldingTheServerBinary() throws Exception {
        File bin = executableServer("usr-local-opt-mariadb", "mariadbd");

        File baseDir = SystemMariaDb.locateBaseDir(Collections.singletonList(bin));

        assertEquals(bin.getParentFile(), baseDir,
            "baseDir must be the parent of the bin dir, so mariaDB4j resolves bin/, share/, scripts/ under it");
    }

    @Test
    void locateBaseDir_acceptsMysqldWhenMariadbdAbsent() throws Exception {
        File bin = executableServer("legacy", "mysqld");
        assertEquals(bin.getParentFile(), SystemMariaDb.locateBaseDir(Collections.singletonList(bin)),
            "an older system install exposing mysqld must still be accepted");
    }

    @Test
    void locateBaseDir_returnsFirstCandidateInPreferenceOrder() throws Exception {
        File first = executableServer("keg", "mariadbd");
        File second = executableServer("path-bin", "mariadbd");

        File baseDir = SystemMariaDb.locateBaseDir(Arrays.asList(first, second));

        assertEquals(first.getParentFile(), baseDir,
            "the first candidate dir that has a server binary wins");
    }

    @Test
    void kegOrder_putsUnversionedMariadbFirstThenVersionedDeterministically() {
        File[] kegs = {
            new File("/opt/homebrew/opt/mariadb@10.11"),
            new File("/opt/homebrew/opt/mariadb@11.4"),
            new File("/opt/homebrew/opt/mariadb"),
        };
        Arrays.sort(kegs, SystemMariaDb.KEG_ORDER);

        assertEquals(Arrays.asList("mariadb", "mariadb@10.11", "mariadb@11.4"),
            Arrays.stream(kegs).map(File::getName).collect(java.util.stream.Collectors.toList()),
            "the unversioned 'mariadb' keg (brew install mariadb) must win; versioned kegs follow deterministically");
    }

    @Test
    void candidateBinDirs_andNoArgLocate_runWithoutThrowingOnRealEnvironment() {
        // The candidate assembly reads PATH + Homebrew dirs and the no-arg locate consumes it.
        // The result is environment-dependent (a dev box may or may not have MariaDB installed),
        // so we assert the contract that is invariant: it executes, returns a clean list, and any
        // baseDir it finds actually exists.
        List<File> dirs = SystemMariaDb.candidateBinDirs();
        assertNotNull(dirs, "candidate list must never be null");
        assertFalse(dirs.contains(null), "candidate list must not contain null entries");

        File baseDir = SystemMariaDb.locateBaseDir();
        if (baseDir != null) {
            assertTrue(baseDir.isDirectory(), "a located baseDir must be a real directory");
        }
    }

    @Test
    void resolveLibDir_prefersExistingLibSubdirOverBaseDir() throws Exception {
        File baseDir = tempDir.resolve("prefix").toFile();
        File lib = new File(baseDir, "lib");
        lib.mkdirs();

        assertEquals(lib, SystemMariaDb.resolveLibDir(baseDir),
            "the real lib dir already exists, so mariaDB4j must use it rather than create baseDir/libs");
    }

    @Test
    void resolveLibDir_fallsBackToBaseDirWhenNoLibSubdir() throws Exception {
        File baseDir = tempDir.resolve("nolib").toFile();
        baseDir.mkdirs(); // exists, but has no lib/ child

        assertEquals(baseDir, SystemMariaDb.resolveLibDir(baseDir),
            "with no lib dir, fall back to the (existing) baseDir so prepareDirectories never creates inside the system prefix");
    }

    @Test
    void locateBaseDir_skipsParentlessBinDirAndLetsAWellFormedCandidateWin() throws Exception {
        // A bare relative "bin" has a null parent; mariaDB4j can't anchor baseDir/bin/<tool> on it.
        // Even if such a dir existed with a server, it must be skipped, not returned as a null
        // baseDir (which the caller would misread as "no MariaDB installed").
        File parentless = new File("bin");
        File good = executableServer("good-prefix", "mariadbd");

        File baseDir = SystemMariaDb.locateBaseDir(Arrays.asList(parentless, good));

        assertEquals(good.getParentFile(), baseDir,
            "a parentless candidate must be skipped so a well-formed later candidate wins");
    }

    @Test
    void locateBaseDir_nullWhenNoCandidateHasAServerBinary() throws Exception {
        File emptyBin = tempDir.resolve("empty").resolve("bin").toFile();
        emptyBin.mkdirs();
        // a non-executable file named like the server must not count
        File notExec = new File(emptyBin, "mariadbd");
        Files.createFile(notExec.toPath());
        notExec.setExecutable(false, false);

        assertNull(SystemMariaDb.locateBaseDir(Arrays.asList(emptyBin, tempDir.resolve("missing/bin").toFile())),
            "no executable server binary anywhere -> null, so the caller can emit a clear 'install MariaDB' error");
    }
}
