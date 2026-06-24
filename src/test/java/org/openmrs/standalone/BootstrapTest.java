package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the argv assembly in {@link Bootstrap}. The launcher spawns the real application
 * JVM; these cover the pure command-building so the space-safety of the classpath element and the
 * argument ordering can be verified without actually starting a process.
 */
class BootstrapTest {

	private static final String JAR = "openmrs-standalone.jar";

	@Test
	void buildCommand_putsArgumentsInExpectedOrder() {
		List<String> command = Bootstrap.buildCommand(true, "-Xmx512m -Xms512m", JAR, " -commandline -mysqlport 3316");
		assertEquals(
				Arrays.asList("java", "-splash:splashscreen-loading.png", "-Xmx512m", "-Xms512m", "-cp", JAR,
						"org.openmrs.standalone.ApplicationController", "-commandline", "-mysqlport", "3316"),
				command);
	}

	@Test
	void buildCommand_omitsSplashWhenNotRequested() {
		List<String> command = Bootstrap.buildCommand(false, "-Xmx512m", JAR, "");
		assertEquals(Arrays.asList("java", "-Xmx512m", "-cp", JAR, "org.openmrs.standalone.ApplicationController"), command);
	}

	/**
	 * Contract behind the array form of {@code Runtime.exec}: a classpath entry whose path contains a
	 * space stays a single argv element instead of being split into two broken tokens (as the old
	 * single-string {@code exec(String)} would). getJarFileName() returns a bare filename today, so
	 * this guards against a future absolute/spaced classpath entry rather than a live failure.
	 */
	@Test
	void buildCommand_keepsSpacedJarPathAsSingleElement() {
		String spacedJar = "/Users/veronica/Downloads/referenceapplication-standalone-3.7.0-rc.2 2/openmrs-standalone.jar";
		List<String> command = Bootstrap.buildCommand(false, "-Xmx512m", spacedJar, "");
		int cpIndex = command.indexOf("-cp");
		assertTrue(cpIndex >= 0, "command must contain -cp");
		assertEquals(spacedJar, command.get(cpIndex + 1), "the spaced jar path must be one element, not split");
	}

	@Test
	void addTokens_dropsEmptyTokensAndHandlesNull() {
		List<String> command = new ArrayList<>();
		Bootstrap.addTokens(command, "  -a   -b  ");
		Bootstrap.addTokens(command, null);
		Bootstrap.addTokens(command, "");
		assertEquals(Arrays.asList("-a", "-b"), command);
	}
}
