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

/**
 * Provides command line (non GUI) interface to the standalone launcher.
 */
public class CommandLine implements UserInterface {
	
	private static final String CMD_START = "start";
	
	private static final String CMD_STOP = "stop";
	
	private static final String CMD_LAUNCH_BROWSE = "browse";
	
	private static final String CMD_EXIT = "exit";
	
	private ApplicationController appController;
	
	private int tomcatPort = UserInterface.DEFAULT_TOMCAT_PORT;
	
	private String mySqlPort = UserInterface.DEFAULT_MYSQL_PORT;
	
	private BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(System.in));
	
	private String status;
	
	private boolean running = false;
	
	private boolean exiting = false;
	
	private SwingWorker workerThread;
	
	
	public CommandLine(ApplicationController appController, String tomcatPort, String mySqlPort) {
		this.appController = appController;
		
		if(mySqlPort != null)
			this.mySqlPort = mySqlPort;
		
		if(tomcatPort != null)
			this.tomcatPort = StandaloneUtil.fromStringToInt(tomcatPort);
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
	
	public String getMySqlPort(){
		return mySqlPort;
	}
	
	public void setVisible(boolean visible) {
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
	
	private void displayMessage(String message) {
		System.out.println(message);
	}
	
	private void displayStatusMessage(){
		String message = "browser - to launch a new browser instance.";
		if(!running)
			message = "to change the tomcat or mysql port, use -tomcatport and -mysqlport respectively.";
		
		displayMessage("[" + status + "] Type: exit - to quit, "
			+ (running ? "stop - to stop the server, " : "start - to start the server, ") + message);
	}
	
	private void processUserInput() {
		try {
			displayStatusMessage();
			processCommadLine(bufferedReader.readLine().trim());
		}
		catch (IOException ex) {
			ex.printStackTrace();
		}
	}
	
	private void processCommadLine(String line) {	
		if (line.contains(CMD_LAUNCH_BROWSE)) {
			appController.launchBrowser(tomcatPort);
		} else if (line.startsWith(CMD_START)) {
			startServer(line.split(" "));
		} else if (CMD_STOP.equalsIgnoreCase(line)) {
			stopServer();
		} else if (CMD_EXIT.equalsIgnoreCase(line)) {
			exit();
		}
	}
	
	private void startServer(String[] args) {
		if (!running) {
			setStatus(UserInterface.STATUS_MESSAGE_STARTING);
			
			boolean mySqlPortArg = false;
			boolean tomcatPortArg = false;
			for (String arg : args) {
				arg = arg.toLowerCase();
				if(mySqlPortArg){
					mySqlPort = arg;
					mySqlPortArg = false;
				}
				else if(tomcatPortArg){
					tomcatPort = StandaloneUtil.fromStringToInt(arg);
					tomcatPortArg = false;
				}
				else if(arg.contains("tomcatport")){
					tomcatPortArg = true;
				}
				else if(arg.contains("mysqlport")){
					mySqlPortArg = true;
				}
			}
			
			appController.start();
		}
	}
	
	private void stopServer() {
		if (running) {
			try{
				System.out.println(UserInterface.PROMPT_STOP + " Type: y/yes or n/no");
				String line = bufferedReader.readLine().trim();
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
			String line = bufferedReader.readLine().trim();
			if ("yes".equalsIgnoreCase(line) || "y".equalsIgnoreCase(line)) {
				exiting = true;
				appController.exit();
			}
		}
		catch (IOException ex) {
			ex.printStackTrace();
		}
	}
}
