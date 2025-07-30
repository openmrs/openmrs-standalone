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

import ch.vorburger.exec.ManagedProcess;
import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;

import java.io.File;

/**
 * A reusable extension of the MariaDB4j `DB` class that avoids deleting the data directory
 * if the OpenMRS database already exists. This is useful for stable restarts and Windows compatibility.
 */
public class ReusableDB extends DB {

    /**
     * Constructor that initializes the embedded DB, prepares directories, unpacks binaries,
     * and installs the database only if it doesn't already exist.
     *
     * @param config the DB configuration to use
     * @throws ManagedProcessException if something goes wrong during setup
     */
    public ReusableDB(DBConfiguration config) throws ManagedProcessException {
        super(config);
        // Ensure necessary folders are created (base dir, lib dir, etc.)
        this.prepareDirectories();
        this.unpackEmbeddedDb();

        // Install DB only if "openmrs" database directory does not exist
        if (!databaseExists()) {
            runInstallProcess(); // install() without deleting data dir
        }

    }

    /**
     * Checks whether the OpenMRS database folder already exists inside the data directory.
     *
     * @return true if "openmrs" database folder exists, false otherwise
     */
    private boolean databaseExists() {
        File openmrsDbDir = new File(configuration.getDataDir(), "openmrs");
        return openmrsDbDir.exists() && openmrsDbDir.isDirectory();
    }

    /**
     * Installs the MariaDB instance into the data directory (without deleting it).
     * This step is skipped if the database already exists.
     *
     * @throws ManagedProcessException if an error occurs during the install process
     */
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
     * Opens or reuses an existing embedded MariaDB instance using full configuration.
     * This assumes the data directory is already initialized or will be initialized as needed.
     *
     * @param config the DB configuration
     * @return a ReusableDB instance
     * @throws ManagedProcessException if DB setup fails
     */
    public static ReusableDB openEmbeddedDB(DBConfiguration config) throws ManagedProcessException {
        return new ReusableDB(config);
    }

    /**
     * Convenience method to open the embedded DB with just a port.
     * Other configuration values are set to defaults.
     *
     * @param port the port on which to run MariaDB
     * @return a ReusableDB instance
     * @throws ManagedProcessException if DB setup fails
     */
    public static ReusableDB openEmbeddedDB(int port) throws ManagedProcessException {
        DBConfigurationBuilder config = DBConfigurationBuilder.newBuilder();
        config.setPort(port);
        return openEmbeddedDB(config.build());
    }
}
