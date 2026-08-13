package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The pre-built Lucene index marker in {@link OpenmrsUtil}. The marker is a real file at a fixed
 * path relative to the working directory, so these tests touch the file system rather than a stub:
 * the regression they guard against - a marker outliving the index it describes - is a file-system
 * fact, and a mocked marker would prove nothing about it.
 */
class OpenmrsUtilTest {

	// The path under test comes from OpenmrsUtil itself; see the field's comment for why.
	private static final File MARKER = OpenmrsUtil.PREBUILT_SEARCH_INDEX_MARKER;

	private static final File LUCENE = MARKER.getParentFile();

	private static final File APPDATA = LUCENE.getParentFile();

	private boolean createdMarker;

	private boolean createdLucene;

	private boolean createdAppdata;

	/**
	 * Removes only what the test itself created. A developer may run this suite from inside an
	 * extracted standalone, where appdata/lucene is their live index directory - deleting the marker
	 * there would cost them the full rebuild the marker exists to avoid. Note that JUnit runs
	 * @AfterEach even for a test that aborted on a failed assumption, so every delete here has to be
	 * gated on this instance having created the thing: an unconditional MARKER.delete() removed a
	 * developer's real marker on the very run that had just declined to touch it.
	 */
	@AfterEach
	void removeOnlyWhatWeCreated() {
		if (createdMarker) {
			MARKER.delete();
		}
		if (createdLucene) {
			LUCENE.delete();
		}
		if (createdAppdata) {
			APPDATA.delete();
		}
	}

	/** Refuses to run against a marker we did not create: it would belong to a real baked index. */
	private void skipIfARealMarkerIsPresent() {
		assumeFalse(MARKER.isFile(), MARKER.getPath() + " already exists - not touching it");
	}

	private void giveUsAMarker() throws IOException {
		skipIfARealMarkerIsPresent();
		createdAppdata = !APPDATA.isDirectory();
		createdLucene = !LUCENE.isDirectory();
		assertTrue(LUCENE.isDirectory() || LUCENE.mkdirs(), "could not create " + LUCENE.getPath());
		assertTrue(MARKER.createNewFile(), "could not create " + MARKER.getPath());
		createdMarker = true;
	}

	@Test
	void clearPrebuiltSearchIndexMarker_removesTheMarker() throws IOException {
		giveUsAMarker();
		assertTrue(OpenmrsUtil.hasPrebuiltSearchIndex(), "precondition: the marker should be seen");

		OpenmrsUtil.clearPrebuiltSearchIndexMarker();

		assertFalse(MARKER.exists(), "the marker file should be gone");
		assertFalse(OpenmrsUtil.hasPrebuiltSearchIndex(), "and no longer reported as present");
	}

	@Test
	void clearPrebuiltSearchIndexMarker_noMarker_isAQuietNoOp() {
		skipIfARealMarkerIsPresent();

		// Called on every non-demo import, including the common case of a distribution that shipped
		// no baked index at all, so an absent marker must not be treated as a failure.
		OpenmrsUtil.clearPrebuiltSearchIndexMarker();

		assertFalse(OpenmrsUtil.hasPrebuiltSearchIndex());
	}

	/**
	 * The actual review finding: a rebuild overwrites appdata/lucene in place, so once the Starter
	 * import has rebuilt, a later Demo import in the same directory must not be told it can reuse a
	 * baked index that no longer exists. Before the marker was cleared, this sequence handed the
	 * Demo import an index describing the starter database.
	 */
	@Test
	void afterAStarterRebuild_aLaterDemoImportStillRebuilds() throws IOException {
		giveUsAMarker();
		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.EMPTY_DATABASE, OpenmrsUtil.hasPrebuiltSearchIndex()),
			"the Starter import must rebuild rather than reuse the demo index");

		// What the rebuild branch does, minus the HTTP call to the running server.
		OpenmrsUtil.clearPrebuiltSearchIndexMarker();

		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.DEMO_DATABASE, OpenmrsUtil.hasPrebuiltSearchIndex()),
			"a Demo import after that rebuild must rebuild too - the baked index is gone");
	}
}
