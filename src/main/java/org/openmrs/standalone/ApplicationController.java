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

import java.io.File;
import java.util.Properties;

import com.mysql.management.util.ProcessUtil;

/**
 * Manages the application workflow.
 */
public class ApplicationController {
	
	/** The application's user interface. */
	private UserInterface userInterface;
	
	/** Helps us spawn background threads such that we do not freeze the UI. */
	private SwingWorker workerThread;
	
	/** Manages the tomcat instance. */
	private TomcatManager tomcatManager;
	
	/** The web app context name. */
	private String contextName;
	
	public ApplicationController(boolean commandLineMode, String tomcatPort, String mysqlPort) {
		init(commandLineMode, tomcatPort, mysqlPort);
	}
	
	/**
	 * This is the entry point for the application.
	 * 
	 * @param args
	 */
	public static void main(String[] args){
		
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
			} else if (arg.contains("tomcatport")) {
				tomcatPortArg = true;
			} else if (arg.contains("mysqlport")) {
				mySqlPortArg = true;
			} else {
				System.out.println("Exited because of unknown argument: " + arg);
				System.exit(0);
			}
		}
		
		//If launching for the first time, change the mysql password to ensure that
		//installations do not share the same password.
		mySqlPort = StandaloneUtil.setPortsAndMySqlPassword(mySqlPort, tomcatPort);
		
		if(mySqlPort == null)
			mySqlPort = UserInterface.DEFAULT_MYSQL_PORT;
		
		if(tomcatPort == null)
			tomcatPort = UserInterface.DEFAULT_TOMCAT_PORT + "";
		
		new ApplicationController(commandLine, tomcatPort, mySqlPort);
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
					StandaloneUtil.launchBrowser(userInterface.getTomcatPort(), contextName);
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
	private void init(boolean commandLineMode, String tomcatPort, String mySqlPort) {
		if (commandLineMode) {
			userInterface = new CommandLine(this, tomcatPort, mySqlPort);
		} else {
			userInterface = new MainFrame(this, tomcatPort, mySqlPort);
		}
		
		userInterface.setStatus(UserInterface.STATUS_MESSAGE_STARTING);
		userInterface.setVisible(true);
		
		// add shutdown hook to stop server
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				stopServer();
			}
		});
		
		start();
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
};
