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
import java.util.List;

import static org.openmrs.standalone.OpenmrsUtil.findDumpExecutable;


public class DatabaseDumper {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java DatabaseDumper <baseDir> [mariadb-dump args...]");
            System.exit(1);
        }

        String baseDir = args[0];
        String dbDir = args[1];
        String executable = findDumpExecutable(baseDir, dbDir);

        List<String> command = new ArrayList<>();
        command.add(executable);

        // Add all remaining args starting from args[2], which are the dump flags and db name
        for (int i = 2; i < args.length; i++) {
            command.add(args[i]);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File("."));
        pb.inheritIO();

        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Dump command failed with exit code: " + exitCode);
        }

        System.out.println("✅ Dump completed successfully.");
    }
}
