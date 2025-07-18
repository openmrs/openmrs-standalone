package org.openmrs.standalone;

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;

import java.io.File;

public class ReusableDB extends DB {

    public ReusableDB(DBConfiguration config) throws ManagedProcessException {
        super(config);
        this.prepareDirectories();
        this.unpackEmbeddedDb();

        if (!databaseExists()) {
            runInstallProcess(); // install() without deleting data dir
        }

    }

    private boolean databaseExists() {
        File openmrsDbDir = new File(configuration.getDataDir(), "openmrs");
        return openmrsDbDir.exists() && openmrsDbDir.isDirectory();
    }

    private void runInstallProcess() throws ManagedProcessException {
        try {
            ManagedProcess mysqlInstallProcess = this.createDBInstallProcess();
            mysqlInstallProcess.start();
            mysqlInstallProcess.waitForExit();
        } catch (Exception e) {
            throw new ManagedProcessException("An error occurred while installing the database", e);
        }

    }

    /**
     * Open an existing embedded DB with full config.
     * Assumes data directory is already initialized.
     */
    public static ReusableDB openEmbeddedDB(DBConfiguration config) throws ManagedProcessException {
        return new ReusableDB(config);
    }

    /**
     * Open an existing embedded DB by just specifying port.
     * Uses default configuration otherwise.
     */
    public static ReusableDB openEmbeddedDB(int port) throws ManagedProcessException {
        DBConfigurationBuilder config = DBConfigurationBuilder.newBuilder();
        config.setPort(port);
        return openEmbeddedDB(config.build());
    }
}