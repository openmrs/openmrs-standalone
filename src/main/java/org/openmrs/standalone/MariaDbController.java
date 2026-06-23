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
import java.net.InetSocketAddress;
import java.net.Socket;
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
    /** Fully-qualified MariaDB JDBC driver class, registered before any jdbc:mariadb connection. */
    public static final String MARIADB_DRIVER_CLASS = "org.mariadb.jdbc.Driver";

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
        String arch = System.getProperty("os.arch").toLowerCase();
        boolean isWindows = os.contains("win");
        boolean isMacIntel = os.contains("mac") && (arch.equals("x86_64") || arch.equals("amd64"));

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

            // Defense-in-depth: ensure the MariaDB JDBC driver is registered before the first
            // jdbc:mariadb connection. The assembled jar now merges META-INF/services/java.sql.Driver
            // (see src/main/assembly/jar-with-dependencies.xml), so the driver auto-registers via the
            // JDBC ServiceLoader. This explicit load is belt-and-suspenders for any classpath where
            // that merge is absent - it previously failed here on a Windows restart with
            // "No suitable driver found for jdbc:mariadb://..." because the merge was missing and
            // nothing else had Class.forName'd the driver first.
            Class.forName(MARIADB_DRIVER_CLASS);
            try (Connection conn = DriverManager.getConnection("jdbc:mariadb://localhost:" + port + "/", ROOT_USER, ROOT_PASSWORD);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + ROOT_PASSWORD + "';");
                stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;");
            }
        } else if (isMacIntel) {
            // Intel (x86_64) Macs have no bundled MariaDB: mariaDB4j ships only Linux x64, macOS
            // arm64 and Windows x64 binaries, and no maintained source publishes a modern x86_64
            // macOS build. Fall back to a MariaDB the user installed (e.g. `brew install mariadb`)
            // by pointing mariaDB4j at it instead of unpacking from the classpath. The data dir
            // stays the writable app dir set above; only the program files come from the system.
            File systemBaseDir = SystemMariaDb.locateBaseDir();
            if (systemBaseDir == null) {
                throw new IllegalStateException(
                        "No bundled MariaDB is available for Intel (x86_64) Macs, and none was found on this system. "
                        + "Install one with 'brew install mariadb' (or put mariadbd on the PATH) and restart. "
                        + "On Apple Silicon, the bundled database works out of the box - if you are on an Apple "
                        + "Silicon Mac, relaunch with a native arm64 Java instead of an x86_64 (Rosetta) one.");
            }
            mariaDBConfig.setUnpackingFromClasspath(false);
            mariaDBConfig.setBaseDir(systemBaseDir);
            // Avoid mariaDB4j creating its default <baseDir>/libs inside the (often read-only)
            // system prefix during prepareDirectories(); point it at the install's real lib dir.
            mariaDBConfig.setLibDir(SystemMariaDb.resolveLibDir(systemBaseDir));
            mariaDB = DB.newEmbeddedDB(mariaDBConfig.build());
            mariaDB.start();

            mariaDB.run("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + ROOT_PASSWORD + "';");
            mariaDB.run("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;");
        } else {
            // For Linux and Apple Silicon (arm64) macOS, we use the standard DB class with the
            // bundled binaries. (Intel macOS is handled above, since no x86_64 mac binary ships.)
            if (MacOsBinaryPatcher.isMacOsArm64()) {
                // The bundled arm64 binaries link to Homebrew dylibs (/opt/homebrew/.../libpcre2,
                // openssl). Extract and rewrite them to the bundled copies BEFORE building, then
                // disable mariaDB4j's own classpath unpacking so it cannot re-extract the pristine
                // (Homebrew-linked) binaries over the patched ones inside newEmbeddedDB. Its
                // "already extracted?" check is size-based, and codesign can change the patched
                // file's size on some macOS versions, which would otherwise silently restore the
                // broken binary and fail with "Library not loaded: /opt/homebrew/.../libpcre2" on
                // machines without Homebrew. (setUnpackingFromClasspath must be set before build()
                // - the builder is frozen afterwards.)
                // Read the classpath location mariaDB4j would unpack from via a throwaway config
                // (the builder's own getter is protected, and building freezes our real builder).
                // The location is platform/db-version derived, so it is independent of port/dirs.
                String binariesLocation = DBConfigurationBuilder.newBuilder().setPort(port).build()
                        .getBinariesClassPathLocation();
                MacOsBinaryPatcher.patch(binariesLocation, baseDir);
                mariaDBConfig.setUnpackingFromClasspath(false);
            }
            ch.vorburger.mariadb4j.DBConfiguration builtConfig = mariaDBConfig.build();
            // On a quarantined install (zip downloaded with a browser) the extracted/copied
            // binaries and dylibs can carry the com.apple.quarantine attribute, which makes dyld
            // refuse to load them. Strip it from baseDir before the binaries are first executed
            // (newEmbeddedDB runs mariadb-install-db).
            StandaloneUtil.stripQuarantineAttributes(baseDir);
            mariaDB = DB.newEmbeddedDB(builtConfig);
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
            int port = mariaDB.getConfiguration().getPort();
            mariaDB.stop();
            mariaDB = null;
            // DB.stop() can return while mariadbd is still shutting down. A caller that
            // immediately restarts (e.g. the password-reset flow) then races the dying
            // process for the port and the data directory locks - a coin flip that shows
            // up as 'Address already in use' or an instant start failure. Wait until the
            // port is actually released so that stop is synchronous.
            waitForPortToBeReleased(port, 30000);
        } else {
            System.out.println("MariaDB has already been stopped");
        }
    }

    private static void waitForPortToBeReleased(int port, long timeoutMillis) {
        if (port <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 250);
                // Still accepting connections - the old process is not gone yet.
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (IOException expected) {
                // Connection refused - the port has been released.
                return;
            }
        }
        System.err.println("Timed out waiting for MariaDB to release port " + port);
    }

    public static String getRootPassword() {
        return ROOT_PASSWORD;
    }
}