package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class StandaloneUtilTest {


    private static final String KEY_CONNECTION_PASSWORD = "connection.password";
    private static final String KEY_RESET_CONNECTION_PASSWORD = "reset_connection_password";
    private static final String KEY_CONNECTION_URL = "connection.url";
    private static final String KEY_CONNECTION_USERNAME = "connection.username";
    private static final String TEST_PASSWORD = "test";

    private static final String USERNAME = "openmrs";
    private static final String MARIADB_PORT = "33126";
    private static final String DEFAULT_URL = "jdbc:mysql://127.0.0.1:" + MARIADB_PORT + "/" + MariaDbController.DATABASE_NAME;

    private static final String MARIADB_BASEDIR_NAME = "mariadb-base-dir";
    private static final String DATA_DIR_NAME = "data";

    private Properties properties;
    private Path tempBaseDir;

    @BeforeEach
    public void setUp() throws IOException {
        properties = new Properties();
        properties.setProperty(KEY_CONNECTION_USERNAME, USERNAME);
        properties.setProperty(KEY_CONNECTION_PASSWORD, TEST_PASSWORD);
        properties.setProperty(KEY_CONNECTION_URL, DEFAULT_URL);
        properties.setProperty(KEY_RESET_CONNECTION_PASSWORD, "false");

        tempBaseDir = Paths.get("target", MARIADB_BASEDIR_NAME);
        Files.createDirectories(tempBaseDir);

        String dataDir = Paths.get(tempBaseDir.toString(), DATA_DIR_NAME).toString();

        properties.setProperty(MariaDbController.KEY_MARIADB_BASE_DIR, tempBaseDir.toString());
        properties.setProperty(MariaDbController.KEY_MARIADB_DATA_DIR, dataDir);
    }

    @AfterEach
    public void tearDown() throws IOException {
        FileUtils.deleteDirectory(tempBaseDir.toFile());

        if (Files.exists(tempBaseDir)) {
            fail("Failed to delete temp base directory");
        }
    }

    @Test
    public void shouldCreateDefaultUser() throws Exception {
        try (MockedStatic<OpenmrsUtil> mockUtil = Mockito.mockStatic(OpenmrsUtil.class)) {
            when(OpenmrsUtil.getRuntimeProperties(anyString())).thenReturn(properties);
            when(OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class))).thenReturn(properties);

            StandaloneUtil.startupDatabaseToCreateDefaultUser(MARIADB_PORT);

            MariaDbController.startMariaDB(MARIADB_PORT);
            try (Connection connection = DriverManager.getConnection(DEFAULT_URL, "root", MariaDbController.getRootPassword())) {

                assertNotNull(connection, "Connection to MariaDB with 'root' user should not be null");
                assertFalse(connection.isClosed(), "Connection to MariaDB should be open");

                try (Statement stmt = connection.createStatement()) {
                    ResultSet resultSet = stmt.executeQuery(
                            "SELECT EXISTS(SELECT 1 FROM mysql.user WHERE user = '" + USERNAME + "')"
                    );
                    if (resultSet.next()) {
                        assertTrue(resultSet.getInt(1) == 1, "'openmrs' user should be created");
                    } else {
                        fail("'openmrs' user does not exist in the database");
                    }

                    // Verify that the 'openmrs' user has the required privileges
                    resultSet = stmt.executeQuery(
                            "SHOW GRANTS FOR 'openmrs'@'localhost';"
                    );
                    boolean privilegesCorrect = false;
                    while (resultSet.next()) {
                        String grant = resultSet.getString(1);
                        if (grant.contains("GRANT ALL PRIVILEGES ON `openmrs`.* TO 'openmrs'@'localhost'")) {
                            privilegesCorrect = true;
                            break;
                        }
                    }
                    assertTrue(privilegesCorrect, "'openmrs' user should have the correct privileges");
                }
            } catch (
                    SQLException e) {
                fail("Could not connect to MariaDB with 'root' user or verify user creation: " + e.getMessage());
            } finally {
                MariaDbController.stopMariaDB();
            }
        }
    }

    @Test
    public void shouldKeepTestPasswordWhenResetFlagIsFalse() throws Exception {
        assertEquals("false", properties.getProperty(KEY_RESET_CONNECTION_PASSWORD));
        String resultPort;
        try (MockedStatic<OpenmrsUtil> mockUtil = Mockito.mockStatic(OpenmrsUtil.class)) {
            when(OpenmrsUtil.getRuntimeProperties(anyString())).thenReturn(properties);
            when(OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class))).thenReturn(properties);

            StandaloneUtil.startupDatabaseToCreateDefaultUser(MARIADB_PORT);
            resultPort = StandaloneUtil.setPortsAndMySqlPassword(null, null);


            assertEquals(MARIADB_PORT, resultPort);

            MariaDbController.startMariaDB(resultPort);
            try (Connection connection = DriverManager.getConnection(
                    DEFAULT_URL, USERNAME, TEST_PASSWORD)) {

                assertNotNull(connection, "Connection to MariaDB with '" + USERNAME + "' user should not be null");
                assertFalse(connection.isClosed(), "Connection to MariaDB should be open");

            } catch (SQLException e) {
                fail("Could not connect to MariaDB with user '" + USERNAME + "' and password 'test'");
            } finally {
                MariaDbController.stopMariaDB();
            }
        }
    }

    @Test
    public void shouldChangePasswordWhenResetFlagIsTrue() throws Exception {
        properties.setProperty(KEY_RESET_CONNECTION_PASSWORD, "true");
        assertEquals("true", properties.getProperty(KEY_RESET_CONNECTION_PASSWORD));
        Path tempDirectory = Files.createTempDirectory("openmrsTest");
        String resultPort;
        try (MockedStatic<OpenmrsUtil> mockUtil = Mockito.mockStatic(OpenmrsUtil.class)) {
            Path propertiesFile = tempDirectory.resolve("runtime.properties");

            when(OpenmrsUtil.getRuntimeProperties(anyString())).thenReturn(properties);
            when(OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class))).thenReturn(properties);
            when(OpenmrsUtil.getRuntimePropertiesPathName())
                    .thenReturn(tempDirectory.resolve(propertiesFile.getFileName()).toString());

            StandaloneUtil.startupDatabaseToCreateDefaultUser(MARIADB_PORT);
            resultPort = StandaloneUtil.setPortsAndMySqlPassword(null, null);

            Files.deleteIfExists(propertiesFile);
            Files.deleteIfExists(tempDirectory);

            assertEquals(MARIADB_PORT, resultPort);

            MariaDbController.startMariaDB(resultPort);
            String newPassword = properties.getProperty(KEY_CONNECTION_PASSWORD);
            assertNotEquals(TEST_PASSWORD, newPassword);
            try (Connection connection = DriverManager.getConnection(
                    DEFAULT_URL, USERNAME, newPassword)) {

                assertNotNull(connection, "Connection to MariaDB with 'openmrs' user should not be null");
                assertFalse(connection.isClosed(), "Connection to MariaDB should be open");

            } catch (SQLException e) {
                fail("Could not connect to MariaDB with user 'openmrs' and password '" + newPassword + "'");
            } finally {
                MariaDbController.stopMariaDB();
            }
        }
    }
}