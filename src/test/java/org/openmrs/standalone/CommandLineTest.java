package org.openmrs.standalone;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

/**
 * Tests for {@link CommandLine}'s handling of a closed/EOF stdin. These guard the headless-fallback
 * path (a server launched with no tty) and an interactive console hitting Ctrl+D. The constructor
 * reassigns {@code System.out/err}, so every test restores them in a finally block.
 *
 * <p>{@link ApplicationController} is a concrete class with a heavy constructor (it boots the UI and
 * servers) and cannot be Mockito-mocked under Java 21 here, so tests either pass {@code null} (when
 * the code path must not touch the controller) or allocate one without its constructor via Objenesis
 * (when only the lightweight {@code setApplyDatabaseChange} field write is exercised).
 */
class CommandLineTest {

	/** Builds a CommandLine whose stdin is already at EOF (readLine() returns null on first call). */
	private CommandLine commandLineAtEof(ApplicationController controller, boolean nonInteractive) throws Exception {
		CommandLine commandLine = new CommandLine(controller, "8080", "3316", nonInteractive,
		        DatabaseMode.DEMO_DATABASE);
		setField(commandLine, "bufferedReader", new BufferedReader(new StringReader("")));
		return commandLine;
	}

	private void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private Object getField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private Object invoke(Object target, String method) throws Exception {
		Method m = target.getClass().getDeclaredMethod(method);
		m.setAccessible(true);
		try {
			return m.invoke(target);
		} catch (ReflectiveOperationException e) {
			throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
		}
	}

	/** Builds a CommandLine whose stdin yields the given line(s) then EOF. */
	private CommandLine commandLineWithInput(ApplicationController controller, String input) throws Exception {
		CommandLine commandLine = new CommandLine(controller, "8080", "3316", false, DatabaseMode.DEMO_DATABASE);
		setField(commandLine, "bufferedReader", new BufferedReader(new StringReader(input)));
		return commandLine;
	}

	/**
	 * EOF on stdin must not throw a NullPointerException and must stop the interactive loop. Without
	 * the guard, {@code readLine().trim()} dereferences a null line and the {@code while (!exiting)}
	 * loop spins on it. A null controller proves no command (start/stop/exit) was dispatched.
	 */
	@Test
	void processCommandLine_atEof_stopsLoopAndReportsEof() throws Exception {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			CommandLine commandLine = commandLineAtEof(null, false);
			Object eof = assertDoesNotThrow(() -> invoke(commandLine, "processCommadLine"));
			assertTrue((boolean) eof, "processCommadLine() must report EOF so callers can default safely");
			assertTrue((boolean) getField(commandLine, "exiting"),
			        "EOF on stdin should stop the interactive command loop");
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	/**
	 * The EOF signal must be specific to a closed stream: a normal command (here {@code empty}) must
	 * return false so {@link CommandLine#showInitialConfig()} does not overwrite the user's choice (and,
	 * critically, so a user typing {@code exit} at the config prompt does not trigger the default-mode
	 * import). Guards the structural fix for the exiting/EOF conflation.
	 */
	@Test
	void processCommandLine_withCommand_doesNotReportEof() throws Exception {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			ApplicationController controller = new ObjenesisStd().newInstance(ApplicationController.class);
			CommandLine commandLine = commandLineWithInput(controller, "empty\n");
			Object eof = assertDoesNotThrow(() -> invoke(commandLine, "processCommadLine"));
			assertFalse((boolean) eof, "a real command is not EOF");
			assertEquals(DatabaseMode.EMPTY_DATABASE, getField(controller, "applyDatabaseChange"));
			assertFalse((boolean) getField(commandLine, "exiting"), "a real command must not set exiting");
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	/**
	 * The regression guard: when the first-launch config prompt hits EOF before a mode is chosen, the
	 * default mode must be applied so ApplicationController's {@code while (... applyDatabaseChange ==
	 * null)} loop terminates instead of re-prompting against a dead stream forever.
	 */
	@Test
	void showInitialConfig_interactiveAtEof_appliesDefaultMode() throws Exception {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			// Allocate without running the heavy constructor; setApplyDatabaseChange only writes a field.
			ApplicationController controller = new ObjenesisStd().newInstance(ApplicationController.class);
			CommandLine commandLine = commandLineAtEof(controller, false);
			assertDoesNotThrow(commandLine::showInitialConfig);
			assertEquals(DatabaseMode.DEMO_DATABASE, getField(controller, "applyDatabaseChange"),
			        "EOF before a mode is chosen should apply the default database mode");
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	/**
	 * EOF at the stop confirmation prompt must be treated as "no": no NPE, and the server is not
	 * stopped. A null controller proves stop() was not dispatched (it would NPE if it were), and the
	 * prior {@code readLine().trim()} would itself have thrown on the null line.
	 */
	@Test
	void stopServer_atEof_doesNotThrowAndLeavesServerRunning() throws Exception {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			CommandLine commandLine = commandLineAtEof(null, false);
			setField(commandLine, "running", true); // enter the confirmation branch
			assertDoesNotThrow(() -> invoke(commandLine, "stopServer"));
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	/**
	 * Once the user has confirmed an explicit exit, an EOF on a subsequent config prompt must NOT apply
	 * the default database mode - otherwise ApplicationController's init loop would run the destructive
	 * delete+import while the System.exit() scheduled by exit() races it. {@code exitRequested} is set
	 * directly (the real exit() path calls System.exit and cannot be unit-tested under Java 21).
	 */
	@Test
	void showInitialConfig_atEofAfterExitRequested_doesNotApplyDefault() throws Exception {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			ApplicationController controller = new ObjenesisStd().newInstance(ApplicationController.class);
			CommandLine commandLine = commandLineAtEof(controller, false);
			setField(commandLine, "exitRequested", true);
			assertDoesNotThrow(commandLine::showInitialConfig);
			assertEquals(null, getField(controller, "applyDatabaseChange"),
			        "no default import may be triggered after the user asked to exit");
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	/**
	 * EOF at the exit confirmation prompt must be treated as "no": no NPE, and the application is not
	 * exited. A null controller proves exit() was not dispatched (it would NPE if it were).
	 */
	@Test
	void exit_atEof_doesNotThrowAndDoesNotExit() throws Exception {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		try {
			CommandLine commandLine = commandLineAtEof(null, false);
			assertDoesNotThrow(() -> invoke(commandLine, "exit"));
		}
		finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}
}
