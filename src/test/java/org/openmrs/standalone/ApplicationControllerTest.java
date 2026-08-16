package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for the headless UI-mode decision in {@link ApplicationController}, and for the
 * post-startup search index decision the same class owns.
 */
class ApplicationControllerTest {

	// canReusePrebuiltSearchIndex: the baked index describes the DEMO database only, so reusing it
	// after importing any other database ships an index that disagrees with the data.

	@Test
	void canReusePrebuiltSearchIndex_demoImportWithBakedIndex_reusesIt() {
		assertTrue(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.DEMO_DATABASE, true));
	}

	@Test
	void canReusePrebuiltSearchIndex_starterImport_rebuildsEvenWithBakedIndex() {
		// The regression this guards: skipping here leaves the Starter option searching an index
		// built from the demo database, which lists 50 patients that are not in it.
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.EMPTY_DATABASE, true));
	}

	@Test
	void canReusePrebuiltSearchIndex_wizardImport_cannotReuseTheBakedIndex() {
		// "Cannot reuse" is all this predicate says. The wizard is the one mode that does not go on
		// to rebuild either - it replaces the database and core indexes the new one - so what happens
		// instead is updateSearchIndexAfterStartup's business, tested below.
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.USE_INITIALIZATION_WIZARD, true));
	}

	@Test
	void canReusePrebuiltSearchIndex_noChanges_rebuildsEvenWithBakedIndex() {
		// Defensive cover for a mode no caller can currently produce: MainFrame never wires up its
		// "Do Not Modify the Database" button, and CommandLine offers only demo/empty/expert. If
		// anything ever does reach here it must not reuse an index baked from a different database,
		// so pin the answer now rather than discover it then. The in-place upgrade is NOT this case -
		// it chooses no mode at all; see mustRebuildUnimportedDatabase below.
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.NO_CHANGES, true));
	}

	@Test
	void canReusePrebuiltSearchIndex_noBakedIndex_alwaysRebuilds() {
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.DEMO_DATABASE, false));
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.EMPTY_DATABASE, false));
	}

	// mustRebuildUnimportedDatabase: a boot that imports nothing, with the marker still present, is
	// someone who brought their own database into a tree carrying the demo-baked index.

	@Test
	void mustRebuildUnimportedDatabase_noImportWithBakedIndex_rebuilds() {
		// The in-place upgrade in docs/user-guide.md: database/ copied in, needsconfig.txt deleted, so
		// no database mode is ever chosen. Without this, patient search describes demo people.
		assertTrue(ApplicationController.mustRebuildUnimportedDatabase(null, true));
	}

	@Test
	void mustRebuildUnimportedDatabase_ordinaryRestart_doesNotRebuild() {
		// No marker means the baked index is no longer what is on disk, so there is nothing here to
		// correct and restarts stay fast. A marker that survived a refused rebuild is the other case,
		// and it deliberately does fire again - see the test above.
		assertFalse(ApplicationController.mustRebuildUnimportedDatabase(null, false));
	}

	@Test
	void mustRebuildUnimportedDatabase_anyImport_leavesTheDecisionToTheImportPath() {
		// Not this predicate's job: an import already rebuilds or reuses deliberately, and firing here
		// too would rebuild twice on the same boot.
		for (DatabaseMode mode : DatabaseMode.values()) {
			assertFalse(ApplicationController.mustRebuildUnimportedDatabase(mode, true),
				"mode " + mode + " is an import, so the import path owns the index decision");
			assertFalse(ApplicationController.mustRebuildUnimportedDatabase(mode, false));
		}
	}

	// updateSearchIndexAfterStartup: what the two predicates above actually compose into. The marker
	// says "appdata/lucene is still the baked demo index", so it must be spent exactly when that stops
	// being true. Stubbing OpenmrsUtil rather than writing real files keeps these away from the
	// working directory - OpenmrsUtilTest owns the file-system side of the same marker.

	private static final String SERVER_URL = "http://localhost:8081/openmrs";

	@Test
	void updateSearchIndexAfterStartup_demoImportWithBakedIndex_reusesItAndSpendsTheMarker() {
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);

			ApplicationController.updateSearchIndexAfterStartup(DatabaseMode.DEMO_DATABASE, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker);
		}
	}

	@Test
	void updateSearchIndexAfterStartup_importRebuildAccepted_spendsTheMarker() {
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);
			util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(true);

			ApplicationController.updateSearchIndexAfterStartup(DatabaseMode.EMPTY_DATABASE, SERVER_URL);

			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker);
		}
	}

	@Test
	void updateSearchIndexAfterStartup_importRebuildRefused_keepsTheMarker() {
		// A Starter import whose rebuild request does not get through - the server not yet answering
		// REST is the ordinary way, since that dump ships the credentials the request uses. Spending
		// the marker here left appdata/lucene holding the baked demo index with nothing ever
		// rebuilding it, because a later boot imports nothing and would see no marker to act on.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);
			util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(false);

			ApplicationController.updateSearchIndexAfterStartup(DatabaseMode.EMPTY_DATABASE, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL));
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker, Mockito.never());
		}
	}

	@Test
	void updateSearchIndexAfterStartup_wizardMode_dropsTheMarkerWithoutAsking() {
		// The wizard deletes the database, so OpenMRS is still serving its own setup pages while this
		// runs and no rebuild request can be answered; when that setup finishes, core indexes the new
		// database itself. Asking was therefore never going to work, and the marker has to go anyway
		// because the index it describes is about to be replaced by one we never triggered. Keeping
		// it left every later start acting on that stale claim - a second full re-index where the
		// wizard kept the default password, and a permanent warning about demo data where it did not.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);

			ApplicationController.updateSearchIndexAfterStartup(
					DatabaseMode.USE_INITIALIZATION_WIZARD, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker);
		}
	}

	@Test
	void updateSearchIndexAfterStartup_wizardModeWithNoBakedIndex_touchesNothing() {
		// A build that never baked an index has nothing to drop and nothing misleading on disk, so
		// this must not turn into a rebuild request that cannot be answered either.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(false);

			ApplicationController.updateSearchIndexAfterStartup(
					DatabaseMode.USE_INITIALIZATION_WIZARD, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker, Mockito.never());
		}
	}

	@Test
	void updateSearchIndexAfterStartup_upgradeRebuildRefused_keepsTheMarkerForTheNextBoot() {
		// The in-place upgrade meeting a changed admin password. Keeping the marker is what makes the
		// next start try again instead of leaving them on the demo index for good.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);
			util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(false);

			ApplicationController.updateSearchIndexAfterStartup(null, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL));
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker, Mockito.never());
		}
	}

	@Test
	void updateSearchIndexAfterStartup_rebuildRefused_saysWhichFileToDeleteToStopRetrying() {
		// The retry has no other exit. Nothing here can see a rebuild done by hand, so the marker
		// survives it and the attempt repeats on every start; naming the file is what turns that from
		// a warning an operator cannot act on into one they can. docs/user-guide.md tells them the
		// same thing, so this pins the half of that promise that lives in code.
		PrintStream originalErr = System.err;
		ByteArrayOutputStream warning = new ByteArrayOutputStream();
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);
			util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(false);
			System.setErr(new PrintStream(warning, true));

			ApplicationController.updateSearchIndexAfterStartup(null, SERVER_URL);
		}
		finally {
			System.setErr(originalErr);
		}

		assertTrue(warning.toString().contains(OpenmrsUtil.PREBUILT_SEARCH_INDEX_MARKER.getPath()),
			"the warning must name the marker file, since deleting it is the only way to stop the retry:\n"
			        + warning);
	}

	@Test
	void updateSearchIndexAfterStartup_demoInstallThenAStartThatImportedNothing_staysQuiet() {
		// The whole first-boot-then-restart sequence, with the marker behaving like the file it is.
		// This is the contract ApplicationController.finished() has to hold up by clearing
		// applyDatabaseChange: it used to leave the field set, so the second start in a process said
		// DEMO_DATABASE again, and with the marker spent by the first start that is no longer a reuse
		// but a full mass re-index of a database the index already matched. Swap the null below for
		// DEMO_DATABASE and rebuildEntireSearchIndex is called, which is exactly what users saw.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			AtomicBoolean markerOnDisk = new AtomicBoolean(true);
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenAnswer(call -> markerOnDisk.get());
			util.when(OpenmrsUtil::clearPrebuiltSearchIndexMarker).thenAnswer(call -> {
				markerOnDisk.set(false);
				return null;
			});

			ApplicationController.updateSearchIndexAfterStartup(DatabaseMode.DEMO_DATABASE, SERVER_URL);
			ApplicationController.updateSearchIndexAfterStartup(null, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
			assertFalse(markerOnDisk.get(), "the first start reused the baked index, so it spent the marker");
		}
	}

	@Test
	void updateSearchIndexAfterStartup_ordinaryRestart_touchesNothing() {
		// No import and no marker: appdata/lucene is the live index OpenMRS maintains, and rebuilding
		// it on every start would cost minutes for nothing.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(false);

			ApplicationController.updateSearchIndexAfterStartup(null, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker, Mockito.never());
		}
	}

	// resolveCommandLine: GUI request on a headless host must downgrade to the command line.

	@Test
	void resolveCommandLine_guiRequestOnHeadlessHost_usesCommandLine() {
		assertTrue(ApplicationController.resolveCommandLine(false, true));
	}

	@Test
	void resolveCommandLine_guiRequestWithDisplay_keepsGui() {
		assertFalse(ApplicationController.resolveCommandLine(false, false));
	}

	@Test
	void resolveCommandLine_explicitCommandLine_alwaysCommandLine() {
		assertTrue(ApplicationController.resolveCommandLine(true, false));
		assertTrue(ApplicationController.resolveCommandLine(true, true));
	}

	// resolveNonInteractive: a headless GUI fallback with no console must run unattended,
	// otherwise the interactive read loop would spin on EOF.

	@Test
	void resolveNonInteractive_headlessFallbackNoConsole_runsUnattended() {
		assertTrue(ApplicationController.resolveNonInteractive(false, false, true, false));
	}

	@Test
	void resolveNonInteractive_headlessFallbackWithConsole_staysInteractive() {
		assertFalse(ApplicationController.resolveNonInteractive(false, false, true, true));
	}

	@Test
	void resolveNonInteractive_explicitCommandLineNoConsole_respectsUserChoice() {
		// The user explicitly asked for -commandline, so we don't silently force unattended.
		assertFalse(ApplicationController.resolveNonInteractive(false, true, true, false));
	}

	@Test
	void resolveNonInteractive_explicitFlag_alwaysUnattended() {
		assertTrue(ApplicationController.resolveNonInteractive(true, false, false, true));
		assertTrue(ApplicationController.resolveNonInteractive(true, true, true, false));
	}

	@Test
	void resolveNonInteractive_guiWithDisplay_staysInteractive() {
		assertFalse(ApplicationController.resolveNonInteractive(false, false, false, true));
	}
}
