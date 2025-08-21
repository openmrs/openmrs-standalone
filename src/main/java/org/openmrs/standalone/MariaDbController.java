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
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;

public class MariaDbController {

    public static final String DATABASE_NAME = "openmrs";
    private static final String MARIA_DB_BASE_DIR = "database";
    private static final String MARIA_DB_DATA_DIR = Paths.get(MARIA_DB_BASE_DIR, "data").toString();
    public static final String ROOT_USER = "root";
    public static final String ROOT_PASSWORD = "";

    private static DB mariaDB;
    private static DBConfigurationBuilder mariaDBConfig;

    public static String KEY_MARIADB_BASE_DIR = "connection.database.base_dir";
    public static String KEY_MARIADB_DATA_DIR = "connection.database.data_dir";

    public static void startMariaDB(String port, String userPassword) throws Exception {
        startMariaDB(Integer.parseInt(port), userPassword);
    }

    /**
     * Starts MariaDB with the given port and user password. If password is null or blank, defaults to empty string.
     */
    public static void startMariaDB(int port, String userPassword) throws Exception {
        if (userPassword == null) {
            userPassword = "";
        }

        String os = System.getProperty("os.name").toLowerCase();
        boolean isWindows = os.contains("win");

        // Build DB configuration
        mariaDBConfig = DBConfigurationBuilder.newBuilder();
        mariaDBConfig.setPort(port);
        mariaDBConfig.setSecurityDisabled(false);

        Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());

        String baseDirPath = safeResolveProperty(properties, KEY_MARIADB_BASE_DIR, MARIA_DB_BASE_DIR);
        String dataDirPath = safeResolveProperty(properties, KEY_MARIADB_DATA_DIR, MARIA_DB_DATA_DIR);

        File baseDir = new File(Paths.get(baseDirPath).toAbsolutePath().toString());
        File dataDir = new File(Paths.get(dataDirPath).toAbsolutePath().toString());

        mariaDBConfig.setBaseDir(baseDir);
        mariaDBConfig.setDataDir(dataDir);

        mariaDBConfig.addArg("--max_allowed_packet=96M");
        mariaDBConfig.addArg("--collation-server=utf8_general_ci");
        mariaDBConfig.addArg("--character-set-server=utf8");

        if(isWindows){
            // For Windows, we use the ReusableDB class
            mariaDB = ReusableDB.openEmbeddedDB(mariaDBConfig.build());
            mariaDB.start();

            Connection conn = DriverManager.getConnection("jdbc:mariadb://localhost:" + port + "/", ROOT_USER, ROOT_PASSWORD);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + ROOT_PASSWORD + "';");
                stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;");
            }
        } else {
            // For Linux and macOS, we use the standard DB class
            mariaDB = DB.newEmbeddedDB(mariaDBConfig.build());
            mariaDB.start();

            // Ensure root user exists and has correct password and privileges
            mariaDB.run("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + ROOT_PASSWORD + "';");
            mariaDB.run("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;");
        }

        // Create the OpenMRS database schema if it doesn't exist
        mariaDB.createDB(DATABASE_NAME, ROOT_USER, ROOT_PASSWORD);

        // ✅ Create openmrs user and grant permissions
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://localhost:" + port + "/", ROOT_USER, ROOT_PASSWORD)) {
            try (Statement stmt = connection.createStatement()) {
                // Create user if not exists
                String createUserSQL = "CREATE USER IF NOT EXISTS 'openmrs'@'localhost' IDENTIFIED BY '" + userPassword + "';";
                stmt.executeUpdate(createUserSQL);

                // Grant privileges on the openmrs DB
                String grantPrivilegesSQL = "GRANT ALL PRIVILEGES ON `" + DATABASE_NAME + "`.* TO 'openmrs'@'localhost' WITH GRANT OPTION;";
                stmt.executeUpdate(grantPrivilegesSQL);

                // (Optional) Allow openmrs to create users
                String grantCreateUserSQL = "GRANT CREATE USER ON *.* TO 'openmrs'@'localhost';";
                stmt.executeUpdate(grantCreateUserSQL);
            }
        }
    }

    private static String safeResolveProperty(Properties properties, String key, String defaultValue) {
        if (properties == null || !properties.containsKey(key)) {
            return defaultValue;
        }
        return properties.getProperty(key, defaultValue);
    }

    public static void stopMariaDB() throws ManagedProcessException {
        if (mariaDB != null) {
            mariaDB.stop();
            mariaDB = null;
        } else {
            System.out.println("MariaDB has already been stopped");
        }
    }

    public static String getRootPassword() {
        return ROOT_PASSWORD;
    }
}