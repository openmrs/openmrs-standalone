package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * Imports each bundled database dump into a real embedded MariaDB through the standalone's own
 * import path, then asserts that dump still meets its contract.
 * <p>
 * Why this exists: {@link OpenmrsUtil#importSqlFile(File)} reports a failed import by printing it
 * rather than throwing, so a malformed dump does not fail anything at import time. It would first
 * surface as a user picking that option and landing in a half-imported database. The content checks
 * in {@code scripts/verify-no-demo-fixtures.sh} guard each dump's <em>shape</em> at publish time;
 * this guards that it actually loads.
 * <p>
 * Both dumps are covered, and deliberately so. CI does boot the demo dump when it bakes the Lucene
 * index, but that job runs on a push to the release branch rather than on a pull request, so a PR
 * that regenerated a broken demo dump used to merge green and go red on the branch afterwards. The
 * starter dump has never been executed anywhere else at all.
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

    /** What a set of assertions against the imported database looks like. */
    @FunctionalInterface
    private interface DatabaseContract {
        void check(Statement stmt) throws SQLException;
    }

    /**
     * Imports the one dump matching {@code prefix} into a fresh embedded MariaDB and runs
     * {@code contract} against it. Everything both dumps must satisfy is asserted here, so a caller
     * only states what is specific to its option.
     */
    private void importAndCheck(String prefix, String label, DatabaseContract contract) throws Exception {
        File dump = findBundledDump(prefix);

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
                        label + " dump should have created the full OpenMRS schema");

                assertSharedContract(stmt, label);
                contract.check(stmt);
            } catch (SQLException e) {
                fail("Could not query the imported " + label + " database: " + e.getMessage());
            } finally {
                MariaDbController.stopMariaDB();
            }
        }
    }

    @Test
    public void starterDumpShouldImportAndMeetTheStarterContract() throws Exception {
        importAndCheck("empty-db-", "Starter", stmt -> {
            // The Starter option promises a configured system with no patient data.
            assertEquals(0, count(stmt, "SELECT COUNT(*) FROM patient"),
                    "Starter database must ship no patients");
            assertEquals(0, count(stmt, "SELECT COUNT(*) FROM obs"),
                    "Starter database must ship no observations");
            assertEquals(0, count(stmt, "SELECT COUNT(*) FROM visit"),
                    "Starter database must ship no visits");
        });
    }

    /**
     * The demo dump is what the Demo option ships and what CI bakes the Lucene index against, so a
     * half-imported or under-generated one is worth catching on the pull request rather than on the
     * release branch. Patient data is the only thing it is allowed to have that the Starter has not;
     * everything else it must share is asserted in {@link #assertSharedContract(Statement, String)}.
     */
    @Test
    public void demoDumpShouldImportAndCarryItsDemoData() throws Exception {
        importAndCheck("demo-db-", "Demo", stmt -> {
            // 50 is the count docs/releasing.md's table documents, which is what
            // referencedemodata.createDemoPatientsOnNextStartup ships as and what
            // scripts/generate-demo-data-locally.sh builds the dump with. Pinned exactly rather than
            // ">0" so that a generation which produced some-but-not-all patients cannot pass, and
            // named here so whoever changes the number knows the table is the other copy.
            assertEquals(50, count(stmt, "SELECT COUNT(*) FROM patient"),
                    "Demo database ships 50 generated patients (docs/releasing.md §2 table)");
            assertTrue(count(stmt, "SELECT COUNT(*) FROM obs") > 1000,
                    "Demo database should carry the generated observations");
            assertTrue(count(stmt, "SELECT COUNT(*) FROM visit") > 0,
                    "Demo database should carry generated visits");
            // The failure docs/releasing.md warns about: generation crashing part-way leaves patients
            // with no encounters, which looks healthy on a patient count alone.
            assertEquals(count(stmt, "SELECT COUNT(*) FROM patient"),
                    count(stmt, "SELECT COUNT(DISTINCT patient_id) FROM encounter"),
                    "every demo patient must have encounters — a lower count means generation"
                            + " crashed part-way (docs/releasing.md §2)");
        });
    }

    /**
     * The clinical bounds have to be the SAME in both dumps, and this is the only check that can see
     * it. Everything else here imports one dump into its own database, so a value that differs
     * between the two is invisible to every assertion in {@link #assertSharedContract(Statement,
     * String)} — each one passes on each side independently.
     * <p>
     * Not hypothetical. The two dumps are cut from separate boots of the same pinned config, and the
     * boots do not always agree about which source wins for a concept that has both its own declared
     * bounds and a {@code conceptreferencerange} CSV: on 2026-08-13 the starter boot ended with
     * respiratory rate's declared {@code hi_absolute} of 999 and the demo boot, 90 minutes later,
     * with the 99 its reference-range bands carry. That pair sat in this branch until review caught
     * it by hand. {@code hi_absolute}/{@code low_absolute} are what core validates an obs against
     * when no reference-range criterion matches the patient, so the two options would have accepted
     * different observations.
     * <p>
     * Compares by content rather than against a hardcoded number: the right value is whatever the
     * content package declares, and copying it here would just be an upstream constant with nothing
     * tying the two together. docs/releasing.md §2 says how to decide which side is right when this
     * fails, and it is not automatically the freshly-cut one.
     */
    @Test
    public void bothDumpsShouldAgreeOnClinicalBounds() throws Exception {
        assertDumpsAgreeOn("concept_numeric");
        assertDumpsAgreeOn("concept_reference_range");
    }

    private void assertDumpsAgreeOn(String table) throws Exception {
        List<String> starter = tableRows(findBundledDump("empty-db-"), table);
        List<String> demo = tableRows(findBundledDump("demo-db-"), table);

        // Non-vacuity first. A dump-format change that stopped this matching would otherwise report
        // two empty lists as agreement, which is the one outcome a check like this must not have.
        assertTrue(starter.size() >= 50,
                "only " + starter.size() + " `" + table + "` rows found in the Starter dump — this"
                        + " check is not reading the table it thinks it is");

        List<String> onlyInStarter = new ArrayList<>(starter);
        onlyInStarter.removeAll(demo);
        List<String> onlyInDemo = new ArrayList<>(demo);
        onlyInDemo.removeAll(starter);

        assertEquals(Collections.emptyList(), onlyInStarter,
                "the bundled databases disagree on `" + table + "`: these rows are in the Starter"
                        + " dump but not the Demo one. Both are cut from the same config, so one of"
                        + " them was written from a source the other did not use — decide which"
                        + " matches the content package before shipping (docs/releasing.md §2)."
                        + " Demo-only rows: " + onlyInDemo);
        assertEquals(Collections.emptyList(), onlyInDemo,
                "the bundled databases disagree on `" + table + "`: these rows are in the Demo dump"
                        + " but not the Starter one (docs/releasing.md §2)");
    }

    /**
     * The data rows of one table's INSERT block, read byte for byte.
     * <p>
     * ISO-8859-1 rather than UTF-8 because both dumps carry invalid UTF-8 in openconceptlab's hash
     * column, which a UTF-8 reader throws on. Every byte maps in this charset, and these rows are
     * compared against each other rather than interpreted, so a byte-faithful read is exactly right.
     */
    private List<String> tableRows(File dump, String table) throws IOException {
        String header = "INSERT INTO `" + table + "` VALUES";
        List<String> rows = new ArrayList<>();
        boolean inBlock = false;
        try (BufferedReader reader =
                     Files.newBufferedReader(dump.toPath(), StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(header)) {
                    inBlock = true;
                } else if (inBlock) {
                    if (line.startsWith("(")) {
                        rows.add(line);
                    }
                    if (line.endsWith(";")) {
                        inBlock = false;
                    }
                }
            }
        }
        return rows;
    }

    /**
     * Everything both dumps must satisfy: they are cut from one converged database and differ only in
     * patient data, so every curation edit and every day-one requirement has to hold on both. Only
     * the starter side used to be asserted here, which left the demo dump's copy of the same edits to
     * the shell gate alone.
     */
    private void assertSharedContract(Statement stmt, String label) throws SQLException {
        // It must be a usable implementation to start from.
        assertTrue(count(stmt, "SELECT COUNT(*) FROM concept") > 4000,
                label + " database should carry the reference concept dictionary");
        assertTrue(loginLocations(stmt) >= 1,
                label + " database needs at least one Login Location or nobody can sign in");

        // /home resolves to the Service Queues dashboard, and esm-service-queues-app throws
        // `Cannot read properties of undefined (reading 'id')` when no location carries this tag — so
        // without it the first screen after login is an error page. Measured, and invisible to every
        // other check here: the dump imports, login succeeds, and every other route renders.
        assertTrue(locationsTagged(stmt, "Queue Location") >= 1,
                label + " database needs a Queue Location or /home (Service queues) throws on load");

        // A clinician has to be able to work on day one, without configuring anything.
        assertTrue(count(stmt, "SELECT COUNT(*) FROM visit_type") >= 1,
                label + " database needs a visit type or no visit can be started");
        assertTrue(count(stmt, "SELECT COUNT(*) FROM idgen_identifier_source") >= 1,
                label + " database needs an identifier source or no patient can be registered");
        assertEquals(1, count(stmt, "SELECT COUNT(*) FROM metadatamapping_metadata_term_mapping"
                        + " WHERE code = 'emr.primaryIdentifierType'"),
                "O3 resolves the primary identifier through this emrapi mapping — registration"
                        + " breaks without it even though the identifier type exists (" + label + ")");
        assertTrue(count(stmt, "SELECT COUNT(*) FROM concept c JOIN concept_class cc"
                        + " ON cc.concept_class_id = c.class_id WHERE cc.name = 'Diagnosis'") > 0,
                label + " database needs diagnosis concepts or no diagnosis can be recorded");
        assertTrue(count(stmt, "SELECT COUNT(*) FROM drug") > 0,
                label + " database needs drug products or nothing can be prescribed");

        // The demo content strip-demo-fixtures.sh removes or renames. Each is silent if the filter
        // stops matching upstream, because it warns rather than failing — and each has to have
        // reached BOTH dumps, since they are cut from one boot of the same filtered config.
        assertEquals(0, count(stmt,
                        "SELECT COUNT(*) FROM location WHERE name REGEXP '^Site [0-9]+$'"),
                label + " database still contains 'Site N' placeholder locations");
        assertEquals(0, count(stmt, "SELECT COUNT(*) FROM location"
                        + " WHERE name = 'Ubuntu Hospital'"),
                label + " database still names the demo hospital — the rename did not reach the dump");
        assertEquals(0, count(stmt, "SELECT COUNT(*) FROM patient_identifier_type"
                        + " WHERE name = 'SSN'"),
                label + " database still defines the US-specific 'SSN' identifier type");
        assertEquals(0, count(stmt, "SELECT COUNT(*) FROM form WHERE name IN"
                        + " ('Test Form 1', 'Form Engine Cookbook', 'Form Engine Cookbook Library')"),
                label + " database still contains developer forms");
        assertEquals(0, count(stmt, "SELECT COUNT(*) FROM relationship_type"
                        + " WHERE CONCAT(a_is_to_b, '/', b_is_to_a) IN"
                        + " ('Uncle/Nephew', 'Aunt/Niece', 'Friend/Friend')"),
                label + " database still contains the demo relationship types");
        // The clinically useful ones must survive the same edit — a filter that over-matched would
        // leave a hospital unable to record who brought the patient in.
        assertEquals(1, count(stmt, "SELECT COUNT(*) FROM relationship_type"
                        + " WHERE CONCAT(a_is_to_b, '/', b_is_to_a) = 'Clinician/Patient'"),
                label + " database lost the 'Clinician/Patient' relationship type");
        assertEquals("0", stringValue(stmt, "SELECT property_value FROM global_property"
                        + " WHERE property = 'referencedemodata.createDemoPatientsOnNextStartup'"),
                "createDemoPatientsOnNextStartup must ship as 0 in the " + label + " database:"
                        + " ReferenceDemoDataActivator generates that many patients whenever it is"
                        + " above 0 and runtime property referencedemodata.createDemoPatients is"
                        + " missing or true, and missing defaults to true");

        // A dump taken before the convergence restart (docs/releasing.md §2 step d) leaves the
        // privilege-level roles short of the grants the other one has.
        assertTrue(count(stmt, "SELECT COUNT(*) FROM role_privilege"
                                + " WHERE role = 'Privilege Level: Full'")
                        > CONVERGED_FULL_PRIVILEGE_FLOOR,
                label + " database looks un-converged: 'Privilege Level: Full' is missing grants");
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
