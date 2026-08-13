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

	private static final File APPDATA = new File("appdata");

	private static final File LUCENE = new File(APPDATA, "lucene");

	private static final File MARKER = new File(LUCENE, ".prebuilt");

	private boolean createdAppdata;

	private boolean createdLucene;

	/**
	 * Removes only what the test itself created. A developer may run this suite from inside an
	 * extracted standalone, where appdata/lucene is their live index directory - deleting it would
	 * cost them a full rebuild.
	 */
	@AfterEach
	void removeOnlyWhatWeCreated() {
		MARKER.delete();
		if (createdLucene) {
			LUCENE.delete();
		}
		if (createdAppdata) {
			APPDATA.delete();
		}
	}

	private void giveUsAMarker() throws IOException {
		// Refuse to run against a marker we did not create: it would belong to a real baked index.
		assumeFalse(MARKER.isFile(), "appdata/lucene/.prebuilt already exists - not touching it");
		createdAppdata = !APPDATA.isDirectory();
		createdLucene = !LUCENE.isDirectory();
		assertTrue(LUCENE.isDirectory() || LUCENE.mkdirs(), "could not create " + LUCENE.getPath());
		assertTrue(MARKER.createNewFile(), "could not create " + MARKER.getPath());
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
		assumeFalse(MARKER.isFile(), "appdata/lucene/.prebuilt already exists - not touching it");

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
