package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the headless UI-mode decision in {@link ApplicationController}. These cover the
 * fallback that prevents a HeadlessException when the GUI is requested on a host with no display.
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
	void canReusePrebuiltSearchIndex_wizardImport_rebuildsEvenWithBakedIndex() {
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.USE_INITIALIZATION_WIZARD, true));
	}

	@Test
	void canReusePrebuiltSearchIndex_doNotModifyDatabase_rebuildsEvenWithBakedIndex() {
		// NO_CHANGES imports nothing, so this looks like the one case that could keep the baked
		// index - but it is what the GUI's "Do Not Modify the Database" sets, which is what someone
		// upgrading in place picks after copying their own database into a fresh unzip. That tree
		// still carries the index baked against the demo data, so it must rebuild.
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
		// Whichever branch ran on the previous boot consumed the marker, so restarts stay fast.
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
