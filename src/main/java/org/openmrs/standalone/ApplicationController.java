/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.standalone;

import ch.vorburger.exec.ManagedProcessException;
import org.apache.commons.io.FileUtils;

import java.awt.GraphicsEnvironment;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.swing.JOptionPane;

import static org.openmrs.standalone.MariaDbController.stopMariaDB;

/**
 * Manages the application workflow.
 */
public class ApplicationController {
	
	private DatabaseMode applyDatabaseChange = null;
	
	/** The application's user interface. */
	private UserInterface userInterface;
	
	/** Helps us spawn background threads such that we do not freeze the UI. */
	private SwingWorker workerThread;
	
	/** Manages the tomcat instance. */
	private TomcatManager tomcatManager;
	
	/** The web app context name. */
	private String contextName;
	
	private boolean commandLineMode = false;
	
	private boolean nonInteractive = false;
	
	public ApplicationController(boolean commandLineMode, boolean nonInteractive, DatabaseMode mode, String tomcatPort, String mysqlPort) throws Exception {
		this.commandLineMode = commandLineMode;
		this.nonInteractive = nonInteractive;
		init(commandLineMode, nonInteractive, mode, tomcatPort, mysqlPort);
	}
	
	/**
	 * This is the entry point for the application.
	 * 
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		
		String tomcatPort = null;
		String mySqlPort = null;
		
		Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName()); //StandaloneUtil.getRuntimeProperties(); //OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
		if (properties != null) {
			tomcatPort = properties.getProperty("tomcatport");
		}

		OpenmrsUtil.REFAPP_VERSION = StandaloneUtil.getRefappVersion();
		
		//Some users may prefer command line to having the GUI, by providing the -commandline switch.
		//Command line args can always override the values in the runtime properties file.
		boolean commandLine = false;
		boolean mySqlPortArg = false;
		boolean tomcatPortArg = false;
		boolean nonInteractive = false;
		DatabaseMode mode = DatabaseMode.DEMO_DATABASE;
		for (String arg : args) {
			arg = arg.toLowerCase();
			if (mySqlPortArg) {
				mySqlPort = arg;
				mySqlPortArg = false;
			} else if (tomcatPortArg) {
				tomcatPort = arg;
				tomcatPortArg = false;
			} else if (arg.contains("commandline")) {
				commandLine = true;
			} else if (arg.contains("noninteractive")) {
				nonInteractive = true;
			} else if (arg.contains("empty")) {
				mode = DatabaseMode.EMPTY_DATABASE;
			} else if (arg.contains("expert")) {
				mode = DatabaseMode.USE_INITIALIZATION_WIZARD;
			} else if (arg.contains("tomcatport")) {
				tomcatPortArg = true;
			} else if (arg.contains("mysqlport")) {
				mySqlPortArg = true;
			} else {
				System.out.println("Exited because of unknown argument: " + arg);
				System.exit(0);
			}
		}
		
		//The GUI (MainFrame) needs a graphical display. When none is available
		//(e.g. a headless Linux server with no X11 DISPLAY) building the JFrame
		//throws a HeadlessException and the launcher dies before doing anything
		//useful. Fall back to the command-line interface instead of crashing.
		boolean headless = GraphicsEnvironment.isHeadless();
		boolean guiFallback = !commandLine && headless;
		//Resolve nonInteractive BEFORE overwriting commandLine, since the decision
		//depends on whether the GUI (not -commandline) was the original request.
		nonInteractive = resolveNonInteractive(nonInteractive, commandLine, headless, System.console() != null);
		commandLine = resolveCommandLine(commandLine, headless);

		// Fail fast (and visibly) if the install path has a space: MariaDB's bundled installer would
		// otherwise abort deep in startup with a cryptic error. This must run before MainFrame
		// redirects stderr into its log window, so the message is surfaced explicitly through the
		// active UI - a dialog in GUI mode (where a double-clicked jar has no visible console), the
		// console in command-line mode. Keep this AFTER resolveCommandLine: that is what guarantees a
		// headless JVM has already been downgraded to command-line mode, so the dialog branch in
		// reportFatalStartupError only runs when a real display exists (no HeadlessException).
		try {
			StandaloneUtil.assertInstallPathHasNoSpaces();
		} catch (IllegalStateException badInstallPath) {
			reportFatalStartupError(badInstallPath.getMessage(), commandLine);
			System.exit(1);
		}

		if (guiFallback) {
			System.out.println("No graphical display detected; falling back to "
			        + (nonInteractive ? "non-interactive " : "") + "command-line mode. "
			        + "Pass -commandline (optionally with -noninteractive) to select it explicitly.");
		}

		//If running in non interactive mode, write the process id
		//to be used with kill -9
		if (nonInteractive) {
			writeProcessIdFile();
		}
				
		//Update the runtime properties file with the mysql and tomcat port numbers
		//which may have been supplied as command line arguments. 
		//If we have no mysql port number supplied, this method will simply return that
		//in the runtime properties file database connection string.
		mySqlPort = StandaloneUtil.setRuntimePropertiesFileMysqlAndTomcatPorts(mySqlPort, tomcatPort);
		
		if (mySqlPort == null)
			mySqlPort = UserInterface.DEFAULT_MYSQL_PORT;
		
		if (tomcatPort == null)
			tomcatPort = UserInterface.DEFAULT_TOMCAT_PORT + "";
		
		new ApplicationController(commandLine, nonInteractive, mode, tomcatPort, mySqlPort);
	}

	/**
	 * Surfaces a fatal pre-startup error through the active UI. In command-line mode the message goes
	 * to the console; in GUI mode it also pops a dialog, because a double-clicked jar has no visible
	 * console and stderr would otherwise vanish. A graphical display is guaranteed in GUI mode, since
	 * a headless environment is downgraded to command-line mode before this is reached.
	 *
	 * @param message the actionable error text to show the user
	 * @param commandLine whether the application is running in command-line mode
	 */
	private static void reportFatalStartupError(String message, boolean commandLine) {
		System.err.println(message);
		if (!commandLine) {
			JOptionPane.showMessageDialog(null, message, "OpenMRS Standalone cannot start",
			        JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Decides the effective UI mode. The GUI cannot run on a headless JVM, so a GUI request on a
	 * headless host is downgraded to the command-line interface. Pure (no environment access) so the
	 * decision can be unit-tested without a real display.
	 *
	 * @param commandLineRequested whether -commandline was passed
	 * @param headless whether the JVM has no graphical display
	 * @return true if the command-line interface should be used
	 */
	static boolean resolveCommandLine(boolean commandLineRequested, boolean headless) {
		return commandLineRequested || headless;
	}

	/**
	 * Whether the bundled, pre-built Lucene index can be reused instead of rebuilding it after an
	 * import.
	 * <p>
	 * The build pipeline bakes that index by booting the standalone against its <em>demo</em>
	 * database, so it only describes demo data. Reusing it after importing anything else ships an
	 * index that disagrees with the database: choose "Starter Implementation" and search is backed by
	 * an index listing the 50 demo patients, none of which exist in that database. The marker file
	 * alone cannot tell the two apart, so the database mode has to be part of the decision.
	 * <p>
	 * Reuse costs the marker, because OpenMRS updates that index in place as soon as anyone registers
	 * a patient, so it stops being the baked one. What happens on the other side of this decision is
	 * {@link #updateSearchIndexAfterStartup(DatabaseMode, String)}'s business, not this method's.
	 * <p>
	 * Pure so it can be unit-tested.
	 *
	 * @param mode the database the user chose to import
	 * @param hasPrebuiltIndex whether the distribution shipped a baked index (marker present)
	 * @return true only when the shipped index actually matches the imported database
	 */
	static boolean canReusePrebuiltSearchIndex(DatabaseMode mode, boolean hasPrebuiltIndex) {
		return hasPrebuiltIndex && mode == DatabaseMode.DEMO_DATABASE;
	}

	/**
	 * Whether a boot that imported nothing still has to rebuild the search index.
	 * <p>
	 * A surviving marker means {@code appdata/lucene} is the index baked against the bundled demo
	 * dump. If this boot imported no database, then whatever is in {@code database/} arrived by other
	 * means, and searching it through an index describing demo people gives hits that do not exist and
	 * misses that do, with nothing logged. Three starts arrive here, and this is the only branch that
	 * covers any of them:
	 * <ul>
	 * <li>the in-place upgrade in docs/user-guide.md, which copies the operator's own database in and
	 * deletes {@code needsconfig.txt}, so the database question is never asked;
	 * <li>the start after an initialization-wizard install, whose replacement database OpenMRS built
	 * during its own setup and did <em>not</em> index (see
	 * {@link #updateSearchIndexAfterStartup(DatabaseMode, String)}) - the wizard boot itself keeps the
	 * marker precisely so that this fires;
	 * <li>a retry, because a rebuild the server refused leaves the marker in place.
	 * </ul>
	 * <p>
	 * It cannot fire on a normal first run, which always answers the database question because the
	 * distribution ships {@code needsconfig.txt}. It CAN therefore fire on more than one restart,
	 * deliberately. Pure so it can be unit-tested.
	 *
	 * @param mode the database the user chose to import, or null when none was chosen
	 * @param hasPrebuiltIndex whether the marker is still present
	 * @return true only when no import happened and the on-disk index is still the baked demo one
	 */
	static boolean mustRebuildUnimportedDatabase(DatabaseMode mode, boolean hasPrebuiltIndex) {
		return mode == null && hasPrebuiltIndex;
	}

	/**
	 * Brings {@code appdata/lucene} into agreement with whatever database this boot ended up running
	 * against, and keeps the {@code .prebuilt} marker honest while doing it.
	 * <p>
	 * The index lives on the filesystem rather than inside the imported SQL dump, so without this a
	 * fresh import searches an index that knows nothing about it. Runs in command-line mode too - it
	 * was once gated behind the GUI-only browser launch.
	 * <p>
	 * One rule decides the marker on every path through here. It claims "{@code appdata/lucene} is
	 * still the index the build pipeline baked against the bundled demo database", so it is dropped
	 * exactly when that stops being true: after the baked index is reused, and after a rebuild the
	 * server <em>accepted</em>. Nothing else on any path replaces that index, so nothing else spends
	 * the marker.
	 * <p>
	 * The wizard mode is the one that looks like it should and does not. It asks for no rebuild,
	 * because it has just deleted the database and OpenMRS is serving its own setup pages, but the
	 * database that setup creates arrives with {@code search.indexVersion} already matching core's
	 * {@code OpenmrsConstants.SEARCH_INDEX_VERSION}, so core's own {@code setupSearchIndex()} skips
	 * its rebuild too and the baked demo index is still there afterwards. Keeping the marker is what
	 * lets the next start put it right.
	 * <p>
	 * A rebuild the server refused overwrites nothing, so the marker still describes what is on disk
	 * and the next boot is free to try again. That case is not hypothetical -
	 * {@link OpenmrsUtil#rebuildEntireSearchIndex(String)} signs in with the credentials the bundled
	 * dumps ship, so any database that did not come from one of them can answer 401 indefinitely: the
	 * in-place upgrade in docs/user-guide.md, which brings the operator's own {@code database/}, or a
	 * wizard install whose admin password they chose in OpenMRS's advanced setup. Since nothing here
	 * can tell a rebuild done by hand from one never done at all, the retry repeats until a start
	 * succeeds, and the message it prints says which file to delete to stop it.
	 * <p>
	 * In practice the modes reaching here are demo, empty and wizard. NO_CHANGES is declared but
	 * unreachable: MainFrame builds a "Do Not Modify the Database" button and then leaves it out of
	 * the {@code buttonList} that attaches the listener and adds the buttons to the dialog, and
	 * CommandLine offers only demo/empty/expert. A null mode is therefore not a redundant second path
	 * for the in-place upgrade - it is the only one, because that upgrade deletes
	 * {@code needsconfig.txt} and so is never asked the database question at all.
	 * <p>
	 * The mode describes the start that is finishing, not the process: the only caller outside the
	 * tests, {@link #settleSearchIndexForStart(String)}, clears {@code applyDatabaseChange} once this
	 * has read it, so a later Stop/Start imports nothing and is told so.
	 *
	 * @param mode the database this start imported, or null when it imported nothing
	 * @param resourceUrl the running web application's base URL
	 */
	static void updateSearchIndexAfterStartup(DatabaseMode mode, String resourceUrl) {
		boolean hasPrebuiltIndex = OpenmrsUtil.hasPrebuiltSearchIndex();

		if (canReusePrebuiltSearchIndex(mode, hasPrebuiltIndex)) {
			System.out.println("✅ Using the pre-built Lucene search index; skipping startup rebuild.");
			OpenmrsUtil.clearPrebuiltSearchIndexMarker();
			return;
		}

		if (mode == DatabaseMode.USE_INITIALIZATION_WIZARD) {
			// Nothing worth asking for yet: this mode deleted database/ (see init()), so OpenMRS is
			// sitting on its own setup wizard while this runs and no rebuild request can be answered
			// by anything. Firing one only prints a failure on a boot where nothing is wrong.
			//
			// The marker stays, though, because core will NOT index the replacement database for us.
			// openmrs-api 2.8.8 ships no 2.8.x core-data snapshot, so the wizard's createTables step
			// runs liquibase-core-data-2.7.x.xml, which seeds search.indexVersion with '8' rather
			// than leaving it blank; that is exactly OpenmrsConstants.SEARCH_INDEX_VERSION, so
			// Context.startup's setupSearchIndex() finds them equal and skips updateSearchIndex().
			// (Context.checkCoreDataset() cannot rescue it either: it fills a core global property in
			// only when absent, never overwriting one that is there.) It is the same reason the
			// bundled dumps, which also ship '8', have to ask below.
			//
			// So the baked demo index survives this whole mode untouched, and keeping the marker is
			// what lets the NEXT start correct it through mustRebuildUnimportedDatabase below, once
			// there is a database and a server that can answer.
			//
			// Say that out loud rather than only logging the decision: this start cannot fix the
			// index, so between finishing OpenMRS's setup and restarting, search is still answering
			// out of the bundled demo index. Nothing else would tell them, and one restart is the
			// whole remedy.
			if (hasPrebuiltIndex) {
				System.out.println("The initialization wizard is replacing the database, so there is nothing to"
				        + " rebuild the search index against yet. Restart the standalone once you have finished"
				        + " the OpenMRS setup screens: until then search still answers from the index built for"
				        + " the bundled demo database, and that restart is what rebuilds it against your data.");
			}
			return;
		}

		if (mustRebuildUnimportedDatabase(mode, hasPrebuiltIndex)) {
			System.out.println("A pre-built search index is present but this boot imported no database;"
			        + " rebuilding so search describes the database actually in use.");
		} else if (mode == null) {
			// An ordinary restart: nothing imported, and no baked index left to disagree with the
			// database. appdata/lucene is the live index OpenMRS has been maintaining.
			return;
		}

		if (OpenmrsUtil.rebuildEntireSearchIndex(resourceUrl)) {
			OpenmrsUtil.clearPrebuiltSearchIndexMarker();
		} else if (hasPrebuiltIndex) {
			// Only worth saying when a baked index is what would be left behind. Distributions that
			// shipped none have nothing misleading on disk, and rebuildEntireSearchIndex has already
			// reported the failure itself.
			//
			// Name the marker file, and take the path from the field that defines it rather than
			// spelling it out again. Rebuilding by hand settles the index but not the marker, and
			// nothing here can see that they did it, so without this the retry - and this message -
			// come back on every start with no way out short of guessing which file to remove.
			System.err.println("⚠️  The rebuild was not started, so appdata/lucene may still be the index"
			        + " built for the bundled demo database. Restarting retries it. If you rebuild by hand"
			        + " instead, from Home > System Administration > Manage Search Index, delete "
			        + OpenmrsUtil.PREBUILT_SEARCH_INDEX_MARKER.getPath()
			        + " afterwards or this message returns on every start.");
		}
	}

	/**
	 * Settles {@code appdata/lucene} for the start that has just come up, and then consumes the
	 * database mode that start was configured with.
	 * <p>
	 * The two halves belong together, which is why they are one method rather than two lines inside
	 * {@code start()}'s {@code SwingWorker}. Left set, the field made the second start in a process
	 * claim an import that never happened, and with the marker already spent by the first start that
	 * is not a reuse but a full mass re-index of a database the index already matched - search
	 * answering nothing for minutes while Hibernate Search purges and repopulates. See
	 * {@link #setApplyDatabaseChange(DatabaseMode)} for the one-shot contract.
	 * <p>
	 * Called from {@code finished()} rather than from {@code start()}: a start that never got this
	 * far, because Tomcat did not come up, has not settled the index yet, and its retry must still
	 * see the mode it was configured with. {@code finished()} always runs on the event dispatch
	 * thread, so the next start reads what this one wrote.
	 * <p>
	 * Package-private and separated out so a test can hold it to that contract without booting
	 * Tomcat and MariaDB, which is what the constructor does.
	 *
	 * @param resourceUrl the running web application's base URL
	 */
	void settleSearchIndexForStart(String resourceUrl) {
		updateSearchIndexAfterStartup(applyDatabaseChange, resourceUrl);
		applyDatabaseChange = null;
	}

	/**
	 * Decides whether to run non-interactively. A GUI request that falls back to the command line on a
	 * headless host with no attached console (launched via nohup/systemd/cron or with redirected stdin)
	 * must run unattended: the interactive loop would otherwise read EOF forever, spinning on a null
	 * line. An explicit -commandline request is left as the user chose it. Pure so it can be unit-tested.
	 *
	 * @param nonInteractiveRequested whether -noninteractive was passed
	 * @param commandLineRequested whether -commandline was passed
	 * @param headless whether the JVM has no graphical display
	 * @param consoleAttached whether an interactive console is attached (System.console() != null)
	 * @return true if the server should run non-interactively
	 */
	static boolean resolveNonInteractive(boolean nonInteractiveRequested, boolean commandLineRequested,
	        boolean headless, boolean consoleAttached) {
		return nonInteractiveRequested || (!commandLineRequested && headless && !consoleAttached);
	}

	/**
	 * Starts running the server.
	 */
	public void start() {
		
		/* Invoking start() on the SwingWorker causes a new Thread
		 * to be created that will call construct(), and then
		 * finished().  Note that finished() is called even if
		 * the worker is interrupted because we catch the
		 * InterruptedException in doWork().
		 */
		workerThread = new SwingWorker() {
			
			public Object construct() {
				return startServer();
			}
			
			public void finished() {
				String resourceUrl = "http://localhost:" + userInterface.getTomcatPort() + "/" + contextName;
				Object value = workerThread.get();
				
				userInterface.enableStart(value == null);
				userInterface.enableStop(value != null);
				
				if (value != null) {
					userInterface.setStatus(getRunningStatusMessage());
					//If not command line mode, launch the browser
					//else block with the await call such that we do not exit tomcat
					if (!commandLineMode) {
						StandaloneUtil.launchBrowser(userInterface.getTomcatPort(), contextName);
					}

					settleSearchIndexForStart(resourceUrl);

					//if in non interactive mode, block such that tomcat does not exit
					if (nonInteractive) {
						tomcatManager.await();
					}
				} else {
					userInterface.setStatus(UserInterface.STATUS_MESSAGE_STOPPED);
				}
			}
		};
		
		workerThread.start();
	}
	
	/**
	 * Stops the server from running.
	 */
	public void stop() {
		
		workerThread = new SwingWorker() {
			
			public Object construct() {
				return stopServer();
			}
			
			public void finished() {
				OpenmrsUtil.setDefaultOS();
				userInterface.enableStart(true);
				userInterface.enableStop(false);
				
				userInterface.setStatus(UserInterface.STATUS_MESSAGE_STOPPED);
				
				Runtime.getRuntime().gc();
			}
		};
		
		workerThread.start();
	}
	
	/**
	 * Stops the server, if running, and closes the application.
	 */
	public void exit() {
		
		workerThread = new SwingWorker() {
			
			public Object construct() {
				return stopServer();
			}
			
			public void finished() {
				System.exit(0);
			}
		};
		
		workerThread.start();
	}
	
	/**
	 * Creates the application user interface and automatically runs the server
	 */
	private void init(boolean commandLineMode, boolean nonInteractive, DatabaseMode mode, String tomcatPort, String mySqlPort) throws Exception {
		// When the standalone was downloaded as a zip on macOS, every extracted file carries the
		// com.apple.quarantine attribute and dyld refuses to load the bundled MariaDB dylibs.
		// Strip the attribute from the native binary trees before anything tries to execute them.
		if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
			StandaloneUtil.stripQuarantineAttributes(new File("database"));
			StandaloneUtil.stripQuarantineAttributes(new File("native"));
			StandaloneUtil.stripQuarantineAttributes(new File("appdata"));
		}

		if (commandLineMode) {
			userInterface = new CommandLine(this, tomcatPort, mySqlPort, nonInteractive, mode);
		} else {
			userInterface = new MainFrame(this, tomcatPort, mySqlPort);
		}
		
		userInterface.setVisible(true);
		
		// add shutdown hook to stop server (Tomcat first, then MariaDB)
		Runtime.getRuntime().addShutdownHook(new Thread() {
			
			public void run() {
				stopServer();
				try {
					stopMariaDB();
				} catch (ManagedProcessException e) {
					System.out.println("Failed to stop MariaDB: " + e.getMessage());
					e.printStackTrace();
				}
			}
		});
		
		while (needsInitialConfiguration() && applyDatabaseChange == null) {
			System.out.println("Initial configuration needed");
			userInterface.showInitialConfig();
		}
		
		if (applyDatabaseChange != null) {
			// Use the ports the UI actually resolved as available. setAvailablePorts()
			// auto-increments past any port already in use (e.g. a leftover MariaDB from a
			// previous standalone), so the field can hold 3317 while openmrs-runtime.properties
			// still says 3316. Without this re-sync the wizard steps below would start (and
			// persist) the stale ports and collide with whatever holds them. The normal start
			// path already reads userInterface.getMySqlPort()/getTomcatPort() directly.
			mySqlPort = userInterface.getMySqlPort();
			tomcatPort = String.valueOf(userInterface.getTomcatPort());

			File dest = new File("db");
			if (dest.exists()) {
				if (dest.isDirectory() && dest.listFiles() != null && Objects.requireNonNull(dest.listFiles()).length > 0) {
					FileUtils.cleanDirectory(dest);
				}
			}
			if (applyDatabaseChange == DatabaseMode.USE_INITIALIZATION_WIZARD) {
				deleteActiveDatabase();
				StandaloneUtil.resetConnectionPassword();
				StandaloneUtil.startupDatabaseToCreateDefaultUser(mySqlPort);
				System.out.println("Database mode using wizard: " + applyDatabaseChange);
			} else if (applyDatabaseChange == DatabaseMode.EMPTY_DATABASE) {
				deleteActiveDatabase();
				unzipDatabase(new File("emptydatabase.zip"));
				StandaloneUtil.disableDemoDataGeneration();
				StandaloneUtil.resetConnectionPassword();
				StandaloneUtil.startupDatabaseToCreateDefaultUser(mySqlPort);
				System.out.println("Database mode using wizard: " + applyDatabaseChange);
			} else if (applyDatabaseChange == DatabaseMode.DEMO_DATABASE) {
				deleteActiveDatabase();
				unzipDatabase(new File("demodatabase.zip"));
				StandaloneUtil.disableDemoDataGeneration();
				StandaloneUtil.resetConnectionPassword();
				StandaloneUtil.startupDatabaseToCreateDefaultUser(mySqlPort);
				System.out.println("Database mode using wizard: " + applyDatabaseChange);
			}
			
			deleteNeedsConfigFile();
			
			//If launching for the first time, change the mysql password to ensure that
			//installations do not share the same password.
			mySqlPort = StandaloneUtil.setPortsAndMySqlPassword(mySqlPort, tomcatPort);
		}
		
		userInterface.setStatus(UserInterface.STATUS_MESSAGE_STARTING);
		userInterface.onFinishedInitialConfigCheck();
		
		start();
	}
	
	/**
	 * True if there is no database, or if there's a "needsconfig.txt" file.
	 * 
	 * @return whether or not initial configuration is needed
	 */
	private boolean needsInitialConfiguration() {
		return !(databaseFolderExists()) || new File("needsconfig.txt").exists();
	}
	
	private boolean databaseFolderExists() {
		if (new File("database").exists()) {
			return true;
		}
		
		Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
		String databaseDir = properties.getProperty("server.basedir");
		if (databaseDir == null || databaseDir.trim().length() == 0) {
			return false;
		}
		
		return new File(databaseDir).exists();
	}
	
	/**
	 * Deletes the /database/data folder
	 */
	private void deleteActiveDatabase() {
		System.out.println("Deleting active database");
		if (!deleteFileOrDirectory(new File("database")))
			System.out.println("...failed to delete!");
	}
	
	/**
	 * Deletes the file indicating that configuration is needed.
	 */
	private void deleteNeedsConfigFile() {
		deleteFileOrDirectory(new File("needsconfig.txt"));
	}
	
	/**
	 * @param dirOrFile
	 * @return
	 */
	private boolean deleteFileOrDirectory(File dirOrFile) {
		if (!dirOrFile.exists())
			return true;
		if (!dirOrFile.isDirectory())
			return dirOrFile.delete();
		boolean okay = true;
		for (File file : dirOrFile.listFiles()) {
			if (file.isDirectory())
				okay &= deleteFileOrDirectory(file);
			else
				okay &= file.delete();
		}
		return okay;
	}
	
	/**
	 * Expands the given zip file as /database
	 * 
	 * @param zipFile
	 * @throws IOException
	 */
	private void unzipDatabase(File zipFile) throws IOException {
		System.out.println("Unzipping database from " + zipFile.getName());
		File dest = new File("db");
		dest.mkdir();
		unzip(zipFile, dest);
	}
	
	/**
	 * Modified version of
	 * http://stackoverflow.com/questions/981578/how-to-unzip-files-recursively-in
	 * -java/981731#981731
	 * 
	 * @param sourceZipFile
	 * @param unzipDestinationDirectory
	 * @throws IOException
	 */
	public void unzip(File sourceZipFile, File unzipDestinationDirectory) throws IOException {
		int BUFFER = 2048;
		if (!unzipDestinationDirectory.exists())
			unzipDestinationDirectory.mkdir();
		
		ZipFile zipFile;
		// Open Zip file for reading
		zipFile = new ZipFile(sourceZipFile, ZipFile.OPEN_READ);
		
		// Create an enumeration of the entries in the zip file
		Enumeration<? extends ZipEntry> zipFileEntries = zipFile.entries();
		
		// Process each entry
		while (zipFileEntries.hasMoreElements()) {
			// grab a zip file entry
			ZipEntry entry = zipFileEntries.nextElement();
			String currentEntry = entry.getName();
			
			File destFile = new File(unzipDestinationDirectory, currentEntry);
			
			// grab file's parent directory structure
			File destinationParent = destFile.getParentFile();
			
			// create the parent directory structure if needed
			destinationParent.mkdirs();
			
			// extract file if not a directory
			if (!entry.isDirectory()) {
				BufferedInputStream is = new BufferedInputStream(zipFile.getInputStream(entry));
				int currentByte;
				// establish buffer for writing file
				byte data[] = new byte[BUFFER];
				
				// write the current file to disk
				FileOutputStream fos = new FileOutputStream(destFile);
				BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER);
				
				// read and write until last byte is encountered
				while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
					dest.write(data, 0, currentByte);
				}
				dest.flush();
				dest.close();
				is.close();
			}
		}
		zipFile.close();
	}
	
	/**
	 * Starts running tomcat.
	 * 
	 * @return "Running" status message if started successfully, else returns an error message.
	 */
	private String startServer() {
		try {
			//This is an attempt to prevent some of the bad behavior caused by tomcat caching
			//some stuff in this directory.
			deleteTomcatWorkDir();

			String mySqlPort = StandaloneUtil.setPortsAndMySqlPassword(userInterface.getMySqlPort(), userInterface.getTomcatPort() + "");
			Properties updatedProperties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());

			MariaDbController.startMariaDB(mySqlPort, updatedProperties.getProperty("connection.password"));

			contextName = StandaloneUtil.getContextName();
			tomcatManager = null;
			tomcatManager = new TomcatManager(contextName, userInterface.getTomcatPort());
			tomcatManager.run();
			
			return getRunningStatusMessage();
		}
		catch (Exception ex) {
			ex.printStackTrace();
			return ex.getMessage();
		}
	}
	
	/**
	 * Stops tomcat from running.
	 * 
	 * @return
	 */
	private String stopServer() {
		if (tomcatManager != null)
			tomcatManager.stop();
		
		return null;
	}
	
	/**
	 * Opens the OpenMRS setup wizard or login page in the user's default browser.
	 * 
	 * @param port the port at which tomcat is running.
	 * @return true if successfully opened the browser, else false.
	 */
	public boolean launchBrowser(int port) {
		return StandaloneUtil.launchBrowser(port, contextName);
	}
	
	/**
	 * Deletes the tomcat work directory.
	 */
	private void deleteTomcatWorkDir() {
		try {
			String path = new File("").getAbsolutePath() + File.separatorChar + "tomcat" + File.separatorChar + "work";
			new File(path).delete();
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	private String getRunningStatusMessage() {
		return UserInterface.STATUS_MESSAGE_RUNNING + " - Tomcat Port:" + userInterface.getTomcatPort() + "  MySQL Port:"
		        + userInterface.getMySqlPort();
	}
	
	/**
	 * Indicates that the user has requested a database change.
	 * <p>
	 * The request is one-shot: {@link #init} imports it, and
	 * {@link #settleSearchIndexForStart(String)} clears it on the start that follows, so a second
	 * start in the same process (the GUI's Start button, the command line's {@code start}) correctly
	 * reports that it imported nothing.
	 * <p>
	 * Only the initial-config loop in {@code init} ever acts on it as an import request, so setting
	 * it again afterwards - which CommandLine's {@code demo}/{@code empty}/{@code expert} commands
	 * still allow at the running prompt, undocumented there - imports nothing. It is not inert,
	 * though: the next start still reads it when it settles the index, so a mode typed at the running
	 * prompt can buy a rebuild that boot did not need.
	 *
	 * @param modeToApply the database to import on the next start
	 */
	public void setApplyDatabaseChange(DatabaseMode modeToApply) {
		this.applyDatabaseChange = modeToApply;
	}
	
	private static void writeProcessIdFile() {
		FileWriter fw = null;
		try {
			String processId = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
			System.out.println("OpenMRS Standalone process id:" + processId);
			File pidFile = new File(".standalone.pid");
			if (pidFile.exists()) {
				System.out.println("There is already an instance of this standalone running, "
				        + "please make sure all previous instances have been stopped");
			}
			pidFile.createNewFile();
			pidFile.deleteOnExit();
			System.out.println("Pid file:" + pidFile.getAbsolutePath());
			
			fw = new FileWriter(pidFile);
			fw.write(processId);
			fw.flush();
		}
		catch (IOException ex) {
			ex.printStackTrace();
		}
		finally {
			if (fw != null) {
				try {
					fw.close();
				}
				catch (IOException ex) {}
			}
		}
	}
}
