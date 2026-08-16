package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The pre-built Lucene index marker in {@link OpenmrsUtil}, and the search-index rebuild request
 * that decides when the marker may be dropped.
 * <p>
 * The marker is a real file at a fixed path relative to the working directory, so these tests touch
 * the file system rather than a stub: the regression they guard against - a marker outliving the
 * index it describes - is a file-system fact, and a mocked marker would prove nothing about it. The
 * rebuild tests answer real HTTP from a loopback stub for the same reason: what they pin down is
 * that a refused request is reported as refused, and that is a property of the exchange rather than
 * of any object we could hand in.
 * <p>
 * The last group closes the seam between this class and {@link ApplicationControllerTest}. That one
 * exercises every branch of
 * {@link ApplicationController#updateSearchIndexAfterStartup(DatabaseMode, String)} but always with
 * {@code OpenmrsUtil}'s statics mocked, so its marker is an answer from Mockito rather than a file;
 * this one drives the real marker and the real HTTP exchange but, until now, never through the
 * method that actually decides. Each half was covered and the composition was not, which is exactly
 * where a decision that reads {@code hasPrebuiltSearchIndex()} once into a local and then acts on it
 * after mutating the file could disagree with the disk while every existing test still passed.
 */
class OpenmrsUtilTest {

	// The path under test comes from OpenmrsUtil itself; see the field's comment for why.
	private static final File MARKER = OpenmrsUtil.PREBUILT_SEARCH_INDEX_MARKER;

	/**
	 * A base URL nothing is listening on. The composition tests below that pass it are the modes that
	 * must not ask for a rebuild at all, so it should never be dialled; if a regression makes one of
	 * them dial it, the connection is refused at once, the rebuild reports failure, and the marker
	 * assertion that follows fails rather than the test hanging.
	 */
	private static final String UNREACHABLE = "http://127.0.0.1:1/openmrs";

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

		// What the rebuild branch does once the server has accepted the request - it clears only then,
		// so that a refused rebuild leaves the marker true of what is still on disk. The HTTP call
		// itself is ApplicationControllerTest's business; here it is assumed to have been taken.
		OpenmrsUtil.clearPrebuiltSearchIndexMarker();

		assertFalse(ApplicationController.canReusePrebuiltSearchIndex(
				DatabaseMode.DEMO_DATABASE, OpenmrsUtil.hasPrebuiltSearchIndex()),
			"a Demo import after that rebuild must rebuild too - the baked index is gone");
	}

	// rebuildEntireSearchIndex reports whether the server took the request, which is what lets
	// ApplicationController.updateSearchIndexAfterStartup spend the pre-built index marker only when
	// something really was rebuilt. It signs in with the credentials the bundled dumps ship, so on any
	// database that did not come from one of them - an in-place upgrade, an expert-mode install -
	// "asked" and "rebuilt" are not the same thing. Treating them as the same dropped the marker on a
	// 401 and left that installation searching its own patients through the baked demo index for good.

	/** Serves one canned status on the rebuild endpoint, on a loopback port the OS picks. */
	private HttpServer stubServer(int status) throws IOException {
		HttpServer server = HttpServer.create(
			new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/openmrs/ws/rest/v1/searchindexupdate", exchange -> {
			// Drain first: the caller sends a body, and replying without reading it can surface as a
			// connection error rather than the status we are trying to test.
			try (InputStream body = exchange.getRequestBody()) {
				byte[] buffer = new byte[512];
				while (body.read(buffer) != -1) {
					// discard
				}
			}
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
		return server;
	}

	private String urlOf(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/openmrs";
	}

	@Test
	void rebuildEntireSearchIndex_serverQueuedIt_reportsSuccess() throws IOException {
		HttpServer server = stubServer(204);
		try {
			assertTrue(OpenmrsUtil.rebuildEntireSearchIndex(urlOf(server)),
				"204 is how the REST endpoint says it queued the rebuild");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rebuildEntireSearchIndex_credentialsRejected_reportsFailure() throws IOException {
		// What a changed admin password looks like from here. Nothing was rebuilt, so the caller must
		// not go on to treat the on-disk index as describing this database.
		HttpServer server = stubServer(401);
		try {
			assertFalse(OpenmrsUtil.rebuildEntireSearchIndex(urlOf(server)),
				"a refused request must not be reported as a rebuild");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void rebuildEntireSearchIndex_serverUnreachable_reportsFailure() throws IOException {
		// Bind and release, so the port is one nothing is listening on rather than one we guessed.
		int port;
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			port = socket.getLocalPort();
		}

		assertFalse(OpenmrsUtil.rebuildEntireSearchIndex("http://127.0.0.1:" + port + "/openmrs"),
			"an exception on the way out is a failure to trigger, not a rebuild");
	}

	// The composition: the real decision method, against the real marker file and a real HTTP
	// exchange, with nothing stubbed. ApplicationControllerTest pins which branch each mode takes;
	// what these pin is that the branch's effect actually lands on disk. Run each mode the standalone
	// can reach, and assert the file afterwards.

	/** Runs the real decision against a real marker and a real server answering {@code status}. */
	private void settleAgainstAServerAnswering(int status, DatabaseMode mode) throws IOException {
		HttpServer server = stubServer(status);
		try {
			ApplicationController.updateSearchIndexAfterStartup(mode, urlOf(server));
		} finally {
			server.stop(0);
		}
	}

	@Test
	void updateSearchIndexAfterStartup_demoImport_reallyDeletesTheMarkerFromDisk() throws IOException {
		giveUsAMarker();

		// Demo reuses the baked index, so it never asks the server; the URL is unreachable on purpose.
		ApplicationController.updateSearchIndexAfterStartup(DatabaseMode.DEMO_DATABASE, UNREACHABLE);

		assertFalse(MARKER.exists(),
			"reuse spends the marker, and it has to be gone from the filesystem, not just from a mock");
	}

	@Test
	void updateSearchIndexAfterStartup_starterImportAccepted_reallyDeletesTheMarkerFromDisk()
			throws IOException {
		giveUsAMarker();

		settleAgainstAServerAnswering(204, DatabaseMode.EMPTY_DATABASE);

		assertFalse(MARKER.exists(), "the server took the rebuild, so the baked index is being replaced");
	}

	@Test
	void updateSearchIndexAfterStartup_starterImportRefused_reallyLeavesTheMarkerOnDisk()
			throws IOException {
		giveUsAMarker();

		settleAgainstAServerAnswering(401, DatabaseMode.EMPTY_DATABASE);

		assertTrue(MARKER.isFile(),
			"a refused rebuild overwrote nothing, so the marker must survive for the next start to retry");
	}

	@Test
	void updateSearchIndexAfterStartup_wizardMode_reallyLeavesTheMarkerOnDisk() throws IOException {
		// The change this slice turns on, against a real file for the first time: the wizard asks for
		// nothing AND keeps the marker, because core will not index the database its setup creates.
		// Spending it here left an expert-mode install on the baked demo index with nothing able to
		// notice.
		giveUsAMarker();

		ApplicationController.updateSearchIndexAfterStartup(
				DatabaseMode.USE_INITIALIZATION_WIZARD, UNREACHABLE);

		assertTrue(MARKER.isFile(),
			"the wizard must leave the marker for the next start's null-mode rebuild to spend");
	}

	@Test
	void updateSearchIndexAfterStartup_wizardThenTheNextStart_reallySpendsItOnTheRebuild()
			throws IOException {
		// Both halves in sequence on one marker file, which is the sequence a wizard install runs and
		// the reason the branch above keeps it.
		giveUsAMarker();

		ApplicationController.updateSearchIndexAfterStartup(
				DatabaseMode.USE_INITIALIZATION_WIZARD, UNREACHABLE);
		assertTrue(MARKER.isFile(), "precondition: the wizard start left it alone");

		settleAgainstAServerAnswering(204, null);

		assertFalse(MARKER.exists(),
			"the next start imports nothing, finds the marker, and rebuilds against what the wizard made");
	}

	@Test
	void updateSearchIndexAfterStartup_ordinaryRestart_reallyLeavesTheFilesystemAlone()
			throws IOException {
		// No marker and no import: this must not create anything either.
		skipIfARealMarkerIsPresent();

		ApplicationController.updateSearchIndexAfterStartup(null, UNREACHABLE);

		assertFalse(MARKER.exists(), "an ordinary restart has nothing to say about the index");
	}
}
