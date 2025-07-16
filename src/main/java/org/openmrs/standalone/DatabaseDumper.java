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
