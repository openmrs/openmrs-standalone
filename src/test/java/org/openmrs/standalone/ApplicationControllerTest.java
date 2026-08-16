package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.objenesis.ObjenesisStd;

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
		// to rebuild either, because nothing can answer the request while OpenMRS is serving its own
		// setup pages, so what happens instead is updateSearchIndexAfterStartup's business, tested
		// below.
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
		// Three starts land here, and this predicate is the only thing covering any of them: the
		// in-place upgrade in docs/user-guide.md (database/ copied in, needsconfig.txt deleted, so no
		// mode is ever chosen), the start after an initialization-wizard install, and a retry after a
		// refused rebuild. Without this, patient search describes demo people.
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
	void updateSearchIndexAfterStartup_wizardMode_asksNothingAndKeepsTheMarker() {
		// The wizard deletes the database, so OpenMRS is still serving its own setup pages while this
		// runs and no rebuild request can be answered - asking only prints a failure on a boot where
		// nothing is wrong. The marker must survive, though. Core does NOT index the replacement
		// database for us: openmrs-api 2.8.8's newest core-data snapshot is
		// liquibase-core-data-2.7.x.xml, which seeds search.indexVersion with '8', and that equals
		// OpenmrsConstants.SEARCH_INDEX_VERSION, so setupSearchIndex() skips updateSearchIndex() for
		// exactly the reason the bundled dumps have to ask. Spending it here left the baked demo
		// index in place with nothing able to correct it, since a later boot imports nothing and
		// would see no marker to act on.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);

			ApplicationController.updateSearchIndexAfterStartup(
					DatabaseMode.USE_INITIALIZATION_WIZARD, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker, Mockito.never());
		}
	}

	// Two tests below assert on what the operator is told, so both have to redirect a standard stream
	// and put it back. Restoring it is the part that must not be got wrong: a leaked redirect is
	// silent, and every later test in the JVM would write into a dead buffer. Hence one helper each
	// rather than the try/finally copied per test.

	private static String outFrom(Runnable body) {
		PrintStream original = System.out;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setOut(new PrintStream(captured, true));
		try {
			body.run();
		}
		finally {
			System.setOut(original);
		}
		return captured.toString();
	}

	private static String errFrom(Runnable body) {
		PrintStream original = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured, true));
		try {
			body.run();
		}
		finally {
			System.setErr(original);
		}
		return captured.toString();
	}

	@Test
	void updateSearchIndexAfterStartup_wizardMode_tellsTheOperatorToRestart() {
		// This start cannot fix the index, so the operator carries the gap: between finishing the
		// OpenMRS setup screens and restarting, search still answers from the bundled demo index.
		// Nothing else tells them - no doc covers the wizard option - and one restart is the whole
		// remedy, so the instruction has to survive edits to this message.
		String told = outFrom(() -> {
			try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
				util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);

				ApplicationController.updateSearchIndexAfterStartup(
						DatabaseMode.USE_INITIALIZATION_WIZARD, SERVER_URL);
			}
		});

		assertTrue(told.contains("Restart"),
			"the wizard message must ask for the restart that rebuilds the index:\n" + told);
	}

	@Test
	void updateSearchIndexAfterStartup_wizardInstallThenItsNextStart_rebuildsAgainstWhatTheWizardCreated() {
		// The whole point of keeping the marker above, as the sequence a wizard install actually
		// runs: the boot that chose the wizard asks for nothing, and the next start - which imports
		// nothing, so null - finds the marker still there and rebuilds against the database OpenMRS
		// created in between. Spend the marker in the first call and this second one goes quiet,
		// leaving that install searching its own data through the baked demo index for good.
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			AtomicBoolean markerOnDisk = new AtomicBoolean(true);
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenAnswer(call -> markerOnDisk.get());
			util.when(OpenmrsUtil::clearPrebuiltSearchIndexMarker).thenAnswer(call -> {
				markerOnDisk.set(false);
				return null;
			});
			util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(true);

			ApplicationController.updateSearchIndexAfterStartup(
					DatabaseMode.USE_INITIALIZATION_WIZARD, SERVER_URL);
			ApplicationController.updateSearchIndexAfterStartup(null, SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL));
			assertFalse(markerOnDisk.get(), "the rebuild was accepted, so that start spent the marker");
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
		String warning = errFrom(() -> {
			try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
				util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);
				util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(false);

				ApplicationController.updateSearchIndexAfterStartup(null, SERVER_URL);
			}
		});

		assertTrue(warning.contains(OpenmrsUtil.PREBUILT_SEARCH_INDEX_MARKER.getPath()),
			"the warning must name the marker file, since deleting it is the only way to stop the retry:\n"
			        + warning);
	}

	@Test
	void updateSearchIndexAfterStartup_demoInstallThenAStartThatImportedNothing_staysQuiet() {
		// The whole first-boot-then-restart sequence, with the marker behaving like the file it is.
		// This is what settleSearchIndexForStart buys by clearing applyDatabaseChange: the field used
		// to stay set, so the second start in a process said DEMO_DATABASE again, and with the marker
		// spent by the first start that is no longer a reuse but a full mass re-index of a database
		// the index already matched. Swap the null below for DEMO_DATABASE and rebuildEntireSearchIndex
		// is called, which is exactly what users saw. That the null actually arrives is
		// settleSearchIndexForStart_afterSettlingTheIndex_consumesTheMode's job, below.
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

	// settleSearchIndexForStart: the instance-side half, which reads the configured mode and then
	// spends it. Objenesis allocates the controller past its constructor, which would otherwise boot
	// Tomcat and MariaDB; CommandLineTest reaches this same field the same way.

	private static ApplicationController controllerWithoutItsConstructor(DatabaseMode mode) throws Exception {
		ApplicationController controller = new ObjenesisStd().newInstance(ApplicationController.class);
		Field field = ApplicationController.class.getDeclaredField("applyDatabaseChange");
		field.setAccessible(true);
		field.set(controller, mode);
		return controller;
	}

	private static Object applyDatabaseChangeOf(ApplicationController controller) throws Exception {
		Field field = ApplicationController.class.getDeclaredField("applyDatabaseChange");
		field.setAccessible(true);
		return field.get(controller);
	}

	@Test
	void settleSearchIndexForStart_afterSettlingTheIndex_consumesTheMode() throws Exception {
		// Without this the mode outlives the start that answered it, and the Stop/Start above is read
		// as a fresh import: measured against a stub endpoint, a Demo install went from 0 rebuild
		// requests to 1 across one Stop/Start, and Starter from 1 to 2.
		ApplicationController controller = controllerWithoutItsConstructor(DatabaseMode.DEMO_DATABASE);
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);

			controller.settleSearchIndexForStart(SERVER_URL);

			util.verify(OpenmrsUtil::clearPrebuiltSearchIndexMarker);
		}
		assertNull(applyDatabaseChangeOf(controller),
			"the start that settled the index must consume the mode it was configured with");
	}

	@Test
	void settleSearchIndexForStart_readsTheModeBeforeClearingIt() throws Exception {
		// Order matters as much as the clearing does: read first, then spend. Clearing ahead of the
		// index decision would make every start look like an ordinary restart, so the Starter import
		// would keep the demo index - the bug this whole slice exists to fix.
		ApplicationController controller = controllerWithoutItsConstructor(DatabaseMode.EMPTY_DATABASE);
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(true);
			util.when(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL)).thenReturn(true);

			controller.settleSearchIndexForStart(SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(SERVER_URL));
		}
		assertNull(applyDatabaseChangeOf(controller), "and it is still spent afterwards");
	}

	@Test
	void settleSearchIndexForStart_startThatImportedNothing_staysNull() throws Exception {
		// The ordinary restart, which must not somehow acquire a mode on its way through.
		ApplicationController controller = controllerWithoutItsConstructor(null);
		try (MockedStatic<OpenmrsUtil> util = Mockito.mockStatic(OpenmrsUtil.class)) {
			util.when(OpenmrsUtil::hasPrebuiltSearchIndex).thenReturn(false);

			controller.settleSearchIndexForStart(SERVER_URL);

			util.verify(() -> OpenmrsUtil.rebuildEntireSearchIndex(Mockito.anyString()), Mockito.never());
		}
		assertNull(applyDatabaseChangeOf(controller));
	}

	@Test
	void setApplyDatabaseChange_beforeTheStart_isWhatSettleReads() throws Exception {
		// The other half of the one-shot contract: what CommandLine and MainFrame write is what the
		// next start settles against. Pinned so that the field is not renamed or bypassed without the
		// reflection above failing loudly.
		ApplicationController controller = controllerWithoutItsConstructor(null);

		controller.setApplyDatabaseChange(DatabaseMode.USE_INITIALIZATION_WIZARD);

		assertEquals(DatabaseMode.USE_INITIALIZATION_WIZARD, applyDatabaseChangeOf(controller));
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
