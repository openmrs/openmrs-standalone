package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class MariaDbControllerTest {

    private static final int MARIADB_PORT = 33126;
    private static final String JDBC_URL = "jdbc:mysql://127.0.0.1:" + MARIADB_PORT + "/" + MariaDbController.DATABASE_NAME;
    private static final String ROOT_USER = "root";

    private static final String MARIADB_BASEDIR_NAME = "mariadb-base-dir";

    private Properties properties;
    private Path tempBaseDir;
    private final String dataDirName = "data";

    @BeforeEach
    public void setUp() throws IOException {
        properties = new Properties();

        tempBaseDir = Paths.get("target", MARIADB_BASEDIR_NAME);
        Files.createDirectories(tempBaseDir);

        String dataDir = Paths.get(tempBaseDir.toString(), dataDirName).toString();

        properties.setProperty(MariaDbController.KEY_MARIADB_BASE_DIR, tempBaseDir.toString());
        properties.setProperty(MariaDbController.KEY_MARIADB_DATA_DIR, dataDir);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (Files.exists(tempBaseDir)) {
            FileUtils.deleteDirectory(tempBaseDir.toFile());
        }

        if (Files.exists(tempBaseDir)) {
            fail("Failed to delete temp base directory");
        }
    }

    @Test
    public void shouldStartAndStopMariaDB() throws Exception {
        try (MockedStatic<OpenmrsUtil> ignored = Mockito.mockStatic(OpenmrsUtil.class)) {
            when(OpenmrsUtil.getRuntimeProperties(anyString())).thenReturn(properties);
            when(OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class))).thenReturn(properties);

            MariaDbController.startMariaDB(MARIADB_PORT);

            validateMariaDBRunning();

            MariaDbController.stopMariaDB();

            validateMariaDBStopped();
        }
    }

    @Test
    void shouldIgnoreStopMariaDBWhenNotStartedAndNotThrow() {
        try (MockedStatic<OpenmrsUtil> ignored = Mockito.mockStatic(OpenmrsUtil.class)) {
            when(OpenmrsUtil.getRuntimeProperties(anyString())).thenReturn(properties);
            when(OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class))).thenReturn(properties);

            validateMariaDBStopped();
            try {
                MariaDbController.stopMariaDB();
            } catch (Exception e) {
                fail(e);
            }
        }
    }

    private void validateMariaDBRunning() {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, ROOT_USER, MariaDbController.getRootPassword())) {
            assertNotNull(conn, "Connection to MariaDB should not be null");
            assertFalse(conn.isClosed(), "Connection to MariaDB should be open");
        } catch (SQLException e) {
            fail("MariaDB should start and accept connections. Exception: " + e.getMessage());
        }
    }

    private void validateMariaDBStopped() {
        SQLException exception = assertThrows(SQLException.class, () -> {
            try (Connection ignored = DriverManager.getConnection(JDBC_URL, ROOT_USER, MariaDbController.getRootPassword())) {
                // Attempt to connect
            }
        });
        assertNotNull(exception.getMessage(), "Expected a SQLException when MariaDB is stopped");
    }
}
