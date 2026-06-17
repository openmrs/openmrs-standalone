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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * Provides command line (non GUI) interface to the standalone launcher.
 */
public class CommandLine implements UserInterface {
	
	private static final String CMD_START = "start";
	
	private static final String CMD_STOP = "stop";
	
	private static final String CMD_LAUNCH_BROWSE = "browse";
	
	private static final String CMD_DEMO = "demo";
	
	private static final String CMD_EMPTY = "empty";
	
	private static final String CMD_EXPERT = "expert";
	
	private static final String CMD_EXIT = "exit";
	
	private ApplicationController appController;
	
	private int tomcatPort = UserInterface.DEFAULT_TOMCAT_PORT;
	
	private String mySqlPort = UserInterface.DEFAULT_MYSQL_PORT;
	
	private BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
	
	private String status;
	
	private boolean running = false;
	
	private boolean exiting = false;

	/** True once the user has confirmed an explicit exit; suppresses the EOF default-mode import. */
	private boolean exitRequested = false;

	private SwingWorker workerThread;
	
	private boolean nonInteractive = false;
	
	private DatabaseMode mode = DatabaseMode.DEMO_DATABASE;
	
	public CommandLine(ApplicationController appController, String tomcatPort, String mySqlPort, boolean nonInteractive, DatabaseMode mode) {
		this.appController = appController;
		this.nonInteractive = nonInteractive;
		this.mode = mode;
		
		if (mySqlPort != null)
			this.mySqlPort = mySqlPort;
		
		if (tomcatPort != null)
			this.tomcatPort = StandaloneUtil.fromStringToInt(tomcatPort);
		
		CommandLineWriter logWriter = new CommandLineWriter();
		PrintStream stream = new PrintStream(logWriter);
		System.setOut(stream);
		System.setErr(stream);
	}
	
	public void enableStart(boolean enable) {
		running = !enable;
	}
	
	public void enableStop(boolean enable) {
		running = enable;
	}
	
	public void setStatus(String status) {
		this.status = status;
		displayStatusMessage();
	}
	
	public int getTomcatPort() {
		return tomcatPort;
	}
	
	public String getMySqlPort() {
		return mySqlPort;
	}
	
	public void setVisible(boolean visible) {
		//Command line is already visible. :)
	}
	
	public void onFinishedInitialConfigCheck() {
		if (nonInteractive) {
			displayStatusMessage();
		}
		else {
			workerThread = new SwingWorker() {
				
				public Object construct() {
					while (!exiting) {
						processUserInput();
					}
					
					return null;
				}
			};
			
			workerThread.start();
		}
	}
	
	private void displayMessage(String message) {
		System.out.println(message);
	}
	
	private void displayStatusMessage() {
		if (nonInteractive) {
			displayMessage(status);
		}
		else {
			String message = "browser - to launch a new browser instance.";
			if (!running)
				message = "to change the tomcat or mysql port, use -tomcatport and -mysqlport respectively.";
			
			displayMessage("[" + status + "] Type: exit - to quit, "
			        + (running ? "stop - to stop the server, " : "start - to start the server, ") + message);
		}
	}
	
	private void processUserInput() {
		displayStatusMessage();
		processCommadLine();
	}
	
	/**
	 * Reads a line from stdin and trims it, returning null when the stream is at EOF (console closed,
	 * Ctrl+D, or stdin redirected from /dev/null). Callers must treat null as "no input": without this,
	 * {@code readLine().trim()} throws a NullPointerException on EOF and the read loop spins on it.
	 */
	private String readTrimmedLine() throws IOException {
		String line = bufferedReader.readLine();
		return line == null ? null : line.trim();
	}

	/**
	 * Reads and dispatches one command. Returns true only when stdin is at EOF, so callers can tell an
	 * exhausted stream apart from the user typing {@code exit} (both stop the loop via {@code exiting},
	 * but only EOF should trigger the initial-config default - see {@link #showInitialConfig()}).
	 */
	private boolean processCommadLine() {
		try {
			String line = readTrimmedLine();
			if (line == null) {
				//stdin reached EOF (console closed or Ctrl+D). Stop the interactive
				//command loop so we don't spin on null; the server keeps running on its
				//own (Tomcat) threads.
				exiting = true;
				return true;
			}

			if (line.contains(CMD_LAUNCH_BROWSE)) {
				appController.launchBrowser(tomcatPort);
			} else if (line.startsWith(CMD_START)) {
				startServer(line.split(" "));
			} else if (CMD_STOP.equalsIgnoreCase(line)) {
				stopServer();
			} else if (CMD_EXIT.equalsIgnoreCase(line)) {
				exit();
			} else if (CMD_DEMO.equalsIgnoreCase(line)) {
				appController.setApplyDatabaseChange(DatabaseMode.DEMO_DATABASE);
			} else if (CMD_EMPTY.equalsIgnoreCase(line)) {
				appController.setApplyDatabaseChange(DatabaseMode.EMPTY_DATABASE);
			} else if (CMD_EXPERT.equalsIgnoreCase(line)) {
				appController.setApplyDatabaseChange(DatabaseMode.USE_INITIALIZATION_WIZARD);	
			} else {
				displayMessage("Unknown command: " + line);
			}
		}
		catch (IOException ex) {
			ex.printStackTrace();
		}
		return false;
	}
	
	private void startServer(String[] args) {
		if (!running) {
			setStatus(UserInterface.STATUS_MESSAGE_STARTING);
			
			boolean mySqlPortArg = false;
			boolean tomcatPortArg = false;
			for (String arg : args) {
				arg = arg.toLowerCase();
				if (mySqlPortArg) {
					mySqlPort = arg;
					mySqlPortArg = false;
				} else if (tomcatPortArg) {
					tomcatPort = StandaloneUtil.fromStringToInt(arg);
					tomcatPortArg = false;
				} else if (arg.contains("tomcatport")) {
					tomcatPortArg = true;
				} else if (arg.contains("mysqlport")) {
					mySqlPortArg = true;
				}
			}
			
			appController.start();
		}
	}
	
	private void stopServer() {
		if (running) {
			try {
				System.out.println(UserInterface.PROMPT_STOP + " Type: y/yes or n/no");
				String line = readTrimmedLine();
				//A null line means stdin closed at the prompt; treat it as "no" and leave the server running.
				if ("yes".equalsIgnoreCase(line) || "y".equalsIgnoreCase(line)) {
					setStatus(UserInterface.STATUS_MESSAGE_STOPPING);
					appController.stop();
				}
			}
			catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}
	
	private void exit() {
		try {
			System.out.println(UserInterface.PROMPT_EXIT + " Type: y/yes or n/no");
			String line = readTrimmedLine();
			//A null line means stdin closed at the prompt; treat it as "no" and do not exit.
			if ("yes".equalsIgnoreCase(line) || "y".equalsIgnoreCase(line)) {
				exiting = true;
				exitRequested = true;
				appController.exit();
			}
		}
		catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	/**
     * @see org.openmrs.standalone.UserInterface#showInitialConfig()
     */
    public void showInitialConfig() {
    	if (nonInteractive) {
    		appController.setApplyDatabaseChange(mode);
    	}
    	else {
			System.out.println(UserInterface.PROMPT_CHOOSE_DEMO_EMPTY_OR_EXPERT_MODE + " Type: " + CMD_DEMO + " or " + CMD_EMPTY + " or " + CMD_EXPERT);
			boolean reachedEof = processCommadLine();
			if (reachedEof && !exitRequested) {
				//stdin closed (EOF) before a database mode was chosen. Apply the default mode so
				//ApplicationController's initial-config loop terminates instead of re-prompting against
				//a dead stream forever. Gated on EOF specifically (not the shared `exiting` flag), and
				//skipped once the user has asked to quit, so that typing `exit` here - directly or
				//followed by EOF on a later prompt - never triggers a destructive database import that
				//would race the System.exit() that exit() schedules.
				appController.setApplyDatabaseChange(mode);
			}
    	}
    }
}
