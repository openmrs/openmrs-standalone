package org.openmrs.standalone;

import java.io.File;
import java.nio.file.Paths;
import java.util.Properties;

import ch.vorburger.exec.ManagedProcessException;
import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;

public class MariaDbController {

    public static final String DATABASE_NAME = "openmrs";
    private static final String MARIA_DB_BASE_DIR = "database";
    private static final String MARIA_DB_DATA_DIR = Paths.get(MARIA_DB_BASE_DIR, "data").toString();
    private static final String DATABASE_USER_NAME = "openmrs";
    private static final String DEFAULT_ROOT_PASSWORD = "";

    private static DB mariaDB;
    private static DBConfigurationBuilder mariaDBConfig;

    public static String KEY_MARIADB_BASE_DIR = "mariadb.basedir";
    public static String KEY_MARIADB_DATA_DIR = "mariadb.datadir";

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

        String baseDir = safeResolveProperty(properties, KEY_MARIADB_BASE_DIR, MARIA_DB_BASE_DIR);
        String dataDir = safeResolveProperty(properties, KEY_MARIADB_DATA_DIR, MARIA_DB_DATA_DIR);

        mariaDBConfig.setBaseDir(new File(Paths.get(baseDir).toAbsolutePath().toString()));
        mariaDBConfig.setDataDir(new File(Paths.get(dataDir).toAbsolutePath().toString()));

        mariaDBConfig.addArg("--max_allowed_packet=96M");
        mariaDBConfig.addArg("--collation-server=utf8_general_ci");
        mariaDBConfig.addArg("--character-set-server=utf8");


        mariaDB = DB.newEmbeddedDB(mariaDBConfig.build());

        mariaDB.start();

        // Create or update the 'openmrs' user with the configured password
        mariaDB.run("CREATE USER IF NOT EXISTS '" + DATABASE_USER_NAME + "'@'localhost' IDENTIFIED BY '" + userPassword + "';");
        mariaDB.run("ALTER USER '" + DATABASE_USER_NAME + "'@'localhost' IDENTIFIED BY '" + userPassword + "';");

        // Ensure root user exists and has correct password and privileges
        mariaDB.run("SET PASSWORD FOR 'root'@'localhost' = PASSWORD('" + DEFAULT_ROOT_PASSWORD + "');");
        mariaDB.run("GRANT ALL PRIVILEGES ON *.* TO 'root'@'localhost' WITH GRANT OPTION;");

        // Grant privileges to openmrs user
        mariaDB.run("GRANT ALL PRIVILEGES ON *.* TO '" + DATABASE_USER_NAME + "'@'localhost';");
        mariaDB.run("FLUSH PRIVILEGES;");

        // Create the OpenMRS database schema if it doesn't exist
        mariaDB.createDB(DATABASE_NAME, DATABASE_USER_NAME, userPassword);
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
        Properties props = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
        return props.getProperty("connection.root.password", DEFAULT_ROOT_PASSWORD); // fallback to default
    }
}