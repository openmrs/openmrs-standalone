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
}
