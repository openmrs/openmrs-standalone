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

/**
 * Provides a way for the application controller to communicate with the graphical or non graphical
 * user interface.
 */
public interface UserInterface {
	
	public static final String TITLE = "OpenMRS 2.0 Standalone";
	
	public static final int DEFAULT_TOMCAT_PORT = 8088;
	
	public static final String DEFAULT_MYSQL_PORT = "3316";
	
	public static final String STATUS_MESSAGE_RUNNING = "Running";
	public static final String STATUS_MESSAGE_STARTING = "Starting...";
	public static final String STATUS_MESSAGE_STOPPING = "Stopping...";
	public static final String STATUS_MESSAGE_STOPPED = "Stopped";
	public static final String STATUS_MESSAGE_SHUTTINGDOWN = "Shutting down...";
	public static final String PROMPT_STOPSERVER = "Do you really want to stop the server?";
	public static final String PROMPT_EXIT = "Exiting will stop the server. Do you really want to?";
	public static final String PROMPT_STOP = "Do you really want to stop the server?";
	public static final String PROMPT_CHOOSE_DEMO_EMPTY_OR_EXPERT_MODE = "Do you want the demo, empty, or expert mode?";
	
	void enableStart(boolean enable);
	
	void enableStop(boolean enable);
	
	void setStatus(String status);
	
	int getTomcatPort();
	
	String getMySqlPort();
	
	void setVisible(boolean visible);
	
	void onFinishedInitialConfigCheck();
	
	/**
     * Shows the "first-time config" dialog (or asks those questions at the command prompt) 
     */
    void showInitialConfig();
}
