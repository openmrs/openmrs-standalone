package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Regression test for the stop/start race: {@code DB.stop()} used to return while mariadbd
 * was still shutting down, so an immediate restart (the password-reset flow in
 * {@link StandaloneUtil#setPortsAndMySqlPassword}) raced the dying process for the port and
 * the data directory locks. {@link MariaDbController#stopMariaDB()} now waits for the port
 * to be released before returning, making an immediate restart deterministic.
 */
class MariaDbRestartTest {

    private static final int PORT = 33127;

    private Properties properties;

    @BeforeEach
    void setUp() throws IOException {
        properties = new Properties();
        Path baseDir = Paths.get("target", "mariadb-restart-test");
        Files.createDirectories(baseDir);
        properties.setProperty(MariaDbController.KEY_MARIADB_BASE_DIR, baseDir.toString());
        properties.setProperty(MariaDbController.KEY_MARIADB_DATA_DIR,
            Paths.get(baseDir.toString(), "data").toString());
    }

    @Test
    void stopMariaDB_shouldReleaseThePortSoAnImmediateRestartSucceeds() throws Exception {
        try (MockedStatic<OpenmrsUtil> ignored = Mockito.mockStatic(OpenmrsUtil.class)) {
            when(OpenmrsUtil.getRuntimeProperties(anyString())).thenReturn(properties);
            when(OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class))).thenReturn(properties);

            MariaDbController.startMariaDB(PORT, "");
            MariaDbController.stopMariaDB();

            // Without the synchronous stop this immediate restart intermittently failed
            // with 'Address already in use' or an instant ManagedProcessException.
            MariaDbController.startMariaDB(PORT, "");
            try (Connection connection = DriverManager.getConnection(
                "jdbc:mariadb://127.0.0.1:" + PORT + "/", "root", "")) {
                assertNotNull(connection);
                assertFalse(connection.isClosed());
            } finally {
                MariaDbController.stopMariaDB();
            }
        }
    }
}
