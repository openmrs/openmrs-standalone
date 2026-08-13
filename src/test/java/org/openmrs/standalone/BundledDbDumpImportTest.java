package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Imports the bundled Starter ("empty") database dump into a real embedded MariaDB through the
 * standalone's own import path, then asserts the dump still meets the Starter contract.
 * <p>
 * Why this exists: the Starter dump is the one shipped artifact nothing else ever executes. CI boots
 * the <em>demo</em> dump when it bakes the Lucene index, and {@link OpenmrsUtil#importSqlFile(File)}
 * reports a failed import by printing it rather than throwing — so a malformed Starter dump would
 * first surface as a user choosing "Starter Implementation" and landing in a half-imported database.
 * The content checks in {@code scripts/verify-no-demo-fixtures.sh} guard the dump's <em>shape</em> at
 * publish time; this guards that it actually loads.
 */
class BundledDbDumpImportTest {

    private static final String PORT = "33128"; // distinct from the other MariaDB-backed tests
    private static final String URL =
            "jdbc:mariadb://127.0.0.1:" + PORT + "/" + MariaDbController.DATABASE_NAME;

    /** A converged dump grants this role ~307 privileges; an un-converged first-boot dump only ~215. */
    private static final int CONVERGED_FULL_PRIVILEGE_FLOOR = 250;

    private Properties properties;
    private Path tempBaseDir;

    @BeforeEach
    public void setUp() throws IOException {
        tempBaseDir = Paths.get("target", "mariadb-dump-import-test");
        FileUtils.deleteDirectory(tempBaseDir.toFile());
        Files.createDirectories(tempBaseDir);

        properties = new Properties();
        properties.setProperty("connection.username", "openmrs");
        properties.setProperty("connection.password", "test");
        properties.setProperty("connection.url", URL);
        properties.setProperty(MariaDbController.KEY_MARIADB_BASE_DIR, tempBaseDir.toString());
        properties.setProperty(MariaDbController.KEY_MARIADB_DATA_DIR,
                tempBaseDir.resolve("data").toString());
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            MariaDbController.stopMariaDB();
        } catch (Exception ignored) {
            // already stopped by the test
        }
        FileUtils.deleteDirectory(tempBaseDir.toFile());
    }

    @Test
    public void starterDumpShouldImportAndMeetTheStarterContract() throws Exception {
        File dump = findBundledDump("empty-db-");

        // CALLS_REAL_METHODS, not the default: only getRuntimeProperties is stubbed (so the embedded
        // server writes under target/), while importSqlFile runs for real — which is the whole point.
        try (MockedStatic<OpenmrsUtil> mockUtil =
                     Mockito.mockStatic(OpenmrsUtil.class, Mockito.CALLS_REAL_METHODS)) {
            mockUtil.when(() -> OpenmrsUtil.getRuntimeProperties(Mockito.nullable(String.class)))
                    .thenReturn(properties);

            MariaDbController.startMariaDB(PORT, properties.getProperty("connection.password"));
            OpenmrsUtil.importSqlFile(dump);

            try (Connection connection =
                         DriverManager.getConnection(URL, "root", MariaDbController.getRootPassword());
                 Statement stmt = connection.createStatement()) {

                assertTrue(count(stmt, "SELECT COUNT(*) FROM information_schema.tables"
                                + " WHERE table_schema = '" + MariaDbController.DATABASE_NAME + "'") > 200,
                        "the dump should have created the full OpenMRS schema");

                // The Starter option promises a configured system with no patient data.
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM patient"),
                        "Starter database must ship no patients");
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM obs"),
                        "Starter database must ship no observations");
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM visit"),
                        "Starter database must ship no visits");

                // ...but it must still be a usable implementation to start from.
                assertTrue(count(stmt, "SELECT COUNT(*) FROM concept") > 4000,
                        "Starter database should carry the reference concept dictionary");
                assertTrue(loginLocations(stmt) >= 1,
                        "Starter database needs at least one Login Location or nobody can sign in");

                // /home resolves to the Service Queues dashboard, and esm-service-queues-app throws
                // `Cannot read properties of undefined (reading 'id')` when no location carries this
                // tag — so without it the first screen after login is an error page. Measured, and
                // invisible to every other check here: the dump imports, login succeeds, and every
                // other route renders.
                assertTrue(locationsTagged(stmt, "Queue Location") >= 1,
                        "Starter database needs a Queue Location or /home (Service queues) throws on load");

                // A clinician has to be able to work on day one, without configuring anything.
                assertTrue(count(stmt, "SELECT COUNT(*) FROM visit_type") >= 1,
                        "Starter database needs a visit type or no visit can be started");
                assertTrue(count(stmt, "SELECT COUNT(*) FROM idgen_identifier_source") >= 1,
                        "Starter database needs an identifier source or no patient can be registered");
                assertEquals(1, count(stmt, "SELECT COUNT(*) FROM metadatamapping_metadata_term_mapping"
                                + " WHERE code = 'emr.primaryIdentifierType'"),
                        "O3 resolves the primary identifier through this emrapi mapping — registration"
                                + " breaks without it even though the identifier type exists");
                assertTrue(count(stmt, "SELECT COUNT(*) FROM concept c JOIN concept_class cc"
                                + " ON cc.concept_class_id = c.class_id WHERE cc.name = 'Diagnosis'") > 0,
                        "Starter database needs diagnosis concepts or no diagnosis can be recorded");
                assertTrue(count(stmt, "SELECT COUNT(*) FROM drug") > 0,
                        "Starter database needs drug products or nothing can be prescribed");

                // The demo content strip-demo-fixtures.sh removes or renames. Each is silent if the
                // filter stops matching upstream, because it warns rather than failing.
                assertEquals(0, count(stmt,
                                "SELECT COUNT(*) FROM location WHERE name REGEXP '^Site [0-9]+$'"),
                        "Starter database still contains 'Site N' placeholder locations");
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM location"
                                + " WHERE name = 'Ubuntu Hospital'"),
                        "Starter database still names the demo hospital — the rename did not reach the dump");
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM patient_identifier_type"
                                + " WHERE name = 'SSN'"),
                        "Starter database still defines the US-specific 'SSN' identifier type");
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM form WHERE name IN"
                                + " ('Test Form 1', 'Form Engine Cookbook', 'Form Engine Cookbook Library')"),
                        "Starter database still contains developer forms");
                assertEquals(0, count(stmt, "SELECT COUNT(*) FROM relationship_type"
                                + " WHERE CONCAT(a_is_to_b, '/', b_is_to_a) IN"
                                + " ('Uncle/Nephew', 'Aunt/Niece', 'Friend/Friend')"),
                        "Starter database still contains the demo relationship types");
                // The clinically useful ones must survive the same edit — a filter that over-matched
                // would leave a hospital unable to record who brought the patient in.
                assertTrue(count(stmt, "SELECT COUNT(*) FROM relationship_type"
                                + " WHERE CONCAT(a_is_to_b, '/', b_is_to_a) = 'Clinician/Patient'") == 1,
                        "Starter database lost the 'Clinician/Patient' relationship type");
                assertEquals("0", stringValue(stmt, "SELECT property_value FROM global_property"
                                + " WHERE property = 'referencedemodata.createDemoPatientsOnNextStartup'"),
                        "createDemoPatientsOnNextStartup must ship as 0: ReferenceDemoDataActivator"
                                + " generates that many patients whenever it is above 0 and runtime"
                                + " property referencedemodata.createDemoPatients is missing or true,"
                                + " and missing defaults to true");

                // A dump taken before the convergence restart (docs/releasing.md §2 step d) leaves the
                // privilege-level roles short of the grants the demo database has.
                assertTrue(count(stmt, "SELECT COUNT(*) FROM role_privilege"
                                        + " WHERE role = 'Privilege Level: Full'")
                                > CONVERGED_FULL_PRIVILEGE_FLOOR,
                        "Starter database looks un-converged: 'Privilege Level: Full' is missing grants");
            } catch (SQLException e) {
                fail("Could not query the imported Starter database: " + e.getMessage());
            } finally {
                MariaDbController.stopMariaDB();
            }
        }
    }

    private int loginLocations(Statement stmt) throws SQLException {
        return locationsTagged(stmt, "Login Location");
    }

    /** Counts un-retired locations carrying the named location tag. */
    private int locationsTagged(Statement stmt, String tag) throws SQLException {
        return count(stmt, "SELECT COUNT(*) FROM location l"
                + " JOIN location_tag_map m ON m.location_id = l.location_id"
                + " JOIN location_tag t ON t.location_tag_id = m.location_tag_id"
                + " WHERE t.name = '" + tag + "' AND l.retired = 0");
    }

    /** Reads a single string column, or null when the row is absent. */
    private String stringValue(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int count(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * Finds the bundled dump by prefix rather than by version, so a version bump does not silently
     * skip this test. Exactly one must exist — the repo drops the superseded dump on every bump. If
     * two ever coexist we refuse to guess: version strings do not sort lexicographically
     * ({@code empty-db-3.10.0.sql} sorts before {@code empty-db-3.7.1.sql}), so picking one would
     * quietly test the stale dump and report success for an artifact nobody is shipping.
     */
    private File findBundledDump(String prefix) throws IOException {
        Path dbDir = Paths.get("src", "main", "db");
        List<Path> matches = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dbDir, prefix + "*.sql")) {
            for (Path file : files) {
                matches.add(file);
            }
        }
        if (matches.isEmpty()) {
            fail("No " + prefix + "*.sql dump found in " + dbDir.toAbsolutePath()
                    + " — the build bundles one per refapp version");
        }
        if (matches.size() > 1) {
            Collections.sort(matches);
            fail("Expected exactly one " + prefix + "*.sql in " + dbDir.toAbsolutePath()
                    + " but found " + matches + " — delete the superseded dump so this test cannot"
                    + " silently verify the wrong one");
        }
        return matches.get(0).toFile();
    }
}
