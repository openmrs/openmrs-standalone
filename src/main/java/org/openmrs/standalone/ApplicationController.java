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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Enumeration;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
	
	private final boolean commandLineMode;
	
	private final boolean nonInteractive;
	
	public ApplicationController(boolean commandLineMode, boolean nonInteractive, DatabaseMode mode, String tomcatPort, String mysqlPort) throws Exception {
		this.commandLineMode = commandLineMode;
		this.nonInteractive = nonInteractive;
		init(commandLineMode, nonInteractive, mode, tomcatPort, mysqlPort);
	}
	
	/**
	 * This is the entry point for the application.
	 * 
	 * @param args -The 'args' parameter in the main method represents an array of strings that hold any command-line 
	 *                arguments passed to the application when it is executed. These arguments can be used to customize 
	 *                the behavior or configuration of the application at runtime.
	 */
	public static void main(String[] args) throws Exception {
		
		String tomcatPort = null;
		String mySqlPort = null;
		
		Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName()); //StandaloneUtil.getRuntimeProperties(); //OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
		if (properties != null) {
			tomcatPort = properties.getProperty("tomcatport");
		}
		
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
					
					//if in non interactive mode, block such that tomcat does not exit
					if (nonInteractive) {
						tomcatManager.await();
					}
				} else {
					userInterface.setStatus(UserInterface.STATUS_MESSAGE_STOPPED);
				}
				
				//userInterface.enableStart(value == null);
				//userInterface.enableStop(value != null);
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
		if (commandLineMode) {
			userInterface = new CommandLine(this, tomcatPort, mySqlPort, nonInteractive, mode);
		} else {
			userInterface = new MainFrame(this, tomcatPort, mySqlPort);
		}
		
		userInterface.setVisible(true);
		
		// add shutdown hook to stop server
		Runtime.getRuntime().addShutdownHook(new Thread() {

			@Override
			public void run() {
				stopServer();
			}
		});
		
		while (needsInitialConfiguration() && applyDatabaseChange == null) {
			System.out.println("Initial configuration needed");
			userInterface.showInitialConfig();
		}
		
		if (applyDatabaseChange != null) {
			if (applyDatabaseChange == DatabaseMode.USE_INITIALIZATION_WIZARD) {
				deleteActiveDatabase();
				StandaloneUtil.resetConnectionPassword();
				StandaloneUtil.startupDatabaseToCreateDefaultUser();
			} else if (applyDatabaseChange == DatabaseMode.EMPTY_DATABASE) {
				deleteActiveDatabase();
				unzipDatabase(new File("emptydatabase.zip"));
				StandaloneUtil.resetConnectionPassword();
				StandaloneUtil.startupDatabaseToCreateDefaultUser();
			} else if (applyDatabaseChange == DatabaseMode.DEMO_DATABASE) {
				deleteActiveDatabase();
				unzipDatabase(new File("demodatabase.zip"));
				StandaloneUtil.resetConnectionPassword();
				StandaloneUtil.startupDatabaseToCreateDefaultUser();
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
		return !(new File("database").exists()) || new File("needsconfig.txt").exists();
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
	 * @param dirOrFile - this arg should contain a file object which contains the file directory or a file
	 * @return - true as the value if file or the directory is deleted successfully, else returns false
	 */
	private boolean deleteFileOrDirectory(File dirOrFile) {
		if (!dirOrFile.exists())
			return true;
		if (!dirOrFile.isDirectory())
			return dirOrFile.delete();

		File[] files = dirOrFile.listFiles();
		if (files == null) {
			// Unable to list files, possibly due to security restrictions
			return false;
		}

		boolean okay = true;
		for (File file : files) {
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
	 * @param zipFile - zipped file which contains database
	 * @throws IOException - throws io exception
	 */
	private void unzipDatabase(File zipFile) throws IOException {
		System.out.println("Unzipping database from " + zipFile.getName());
		File dest = new File("database");
		dest.mkdir();
		unzip(zipFile, dest);
	}
	
	/**
     * Modified version of
     * <a href="http://stackoverflow.com/questions/981578/how-to-unzip-files-recursively-in">...</a>
     * -java/981731#981731
     *
     * @param sourceZipFile - this arg should contain a zipped file which need to be unzipped
     * @param unzipDestinationDirectory - this arg should contain the path of destination directory
     * @throws IOException - throws any input output exception
     */
	public void unzip(File sourceZipFile, File unzipDestinationDirectory) throws IOException {
		final int BUFFER = 2048;
		if (!unzipDestinationDirectory.exists())
			unzipDestinationDirectory.mkdir();

		ZipFile zipFile = null;
		try {
			zipFile = new ZipFile(sourceZipFile, ZipFile.OPEN_READ);
			Enumeration<? extends ZipEntry> zipFileEntries = zipFile.entries();

			while (zipFileEntries.hasMoreElements()) {
				ZipEntry entry = zipFileEntries.nextElement();
				String currentEntry = entry.getName();

				File destFile = new File(unzipDestinationDirectory, currentEntry);

				File destinationParent = destFile.getParentFile();
				destinationParent.mkdirs();

				if (!entry.isDirectory()) {
					BufferedInputStream is = null;
					FileOutputStream fos = null;
					BufferedOutputStream dest = null;
					try {
						is = new BufferedInputStream(zipFile.getInputStream(entry));
						fos = new FileOutputStream(destFile);
						dest = new BufferedOutputStream(fos, BUFFER);
						int currentByte;
						byte[] data = new byte[BUFFER];

						while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
							dest.write(data, 0, currentByte);
						}
						dest.flush();
					} finally {
						if (dest != null) {
							dest.close();
						}
						if (fos != null) {
							fos.close();
						}
						if (is != null) {
							is.close();
						}
					}
				}
			}
		} finally {
			if (zipFile != null) {
				zipFile.close();
			}
		}
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
			
			StandaloneUtil.setPortsAndMySqlPassword(userInterface.getMySqlPort(), userInterface.getTomcatPort() + "");
			
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
	 * @return - returns 'null'
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
	 * 
	 * @param modeToApply - this arg should contain the database mode which should be applied
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
