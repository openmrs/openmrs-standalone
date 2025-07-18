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
    private static final String ROOT_USER = "root";
    private static final String ROOT_PASSWORD = "";

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

        mariaDB = ReusableDB.openEmbeddedDB(mariaDBConfig.build());
//        mariaDB = DB.newEmbeddedDB(mariaDBConfig.build());

        mariaDB.start();

        Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:" + port + "/", "root", "");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER USER 'root'@'localhost' IDENTIFIED BY '" + ROOT_PASSWORD + "';");
            stmt.execute("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;");
        }

        // Create the OpenMRS database schema if it doesn't exist
        mariaDB.createDB(DATABASE_NAME, ROOT_USER, ROOT_PASSWORD);

        // ✅ Create openmrs user and grant permissions
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://localhost:" + port + "/", ROOT_USER, ROOT_PASSWORD)) {
            try (Statement stmt = connection.createStatement()) {
                // Create user if not exists
                String createUserSQL = "CREATE USER IF NOT EXISTS 'openmrs'@'localhost' IDENTIFIED BY '" + userPassword + "';";
                stmt.executeUpdate(createUserSQL);
                System.out.println("✅ Created user `openmrs` with password" + userPassword);

                // Grant privileges on the openmrs DB
                String grantPrivilegesSQL = "GRANT ALL PRIVILEGES ON `" + DATABASE_NAME + "`.* TO 'openmrs'@'localhost' WITH GRANT OPTION;";
                stmt.executeUpdate(grantPrivilegesSQL);
                System.out.println("✅ Granted DB privileges to `openmrs`");

                // (Optional) Allow openmrs to create users
                String grantCreateUserSQL = "GRANT CREATE USER ON *.* TO 'openmrs'@'localhost';";
                stmt.executeUpdate(grantCreateUserSQL);
                System.out.println("✅ Granted CREATE USER to `openmrs`");
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