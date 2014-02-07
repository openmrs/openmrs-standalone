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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

public class OpenmrsUtil {
	
	private static final String OPERATING_SYSTEM_KEY = "os.name";
	
	private static final String OPERATING_SYSTEM = System.getProperty(OPERATING_SYSTEM_KEY);
	
	private static final String OPERATING_SYSTEM_LINUX = "Linux";
	
	private static final String OPERATING_SYSTEM_SUNOS = "SunOS";
	
	private static final String OPERATING_SYSTEM_FREEBSD = "FreeBSD";
	
	private static final String OPERATING_SYSTEM_OSX = "Mac OS X";
	
	private static final String OPERATING_SYSTEM_WINDOWS_DUMMY = "Windows 7";
	
	private static String runtimePropertiesPathName;
	
	/**
	 * Shortcut booleans used to make some OS specific checks more generic; note the *nix flavored
	 * check is missing some less obvious choices
	 */
	private static final boolean UNIX_BASED_OPERATING_SYSTEM = (OPERATING_SYSTEM.indexOf(OPERATING_SYSTEM_LINUX) > -1
			|| OPERATING_SYSTEM.indexOf(OPERATING_SYSTEM_SUNOS) > -1
			|| OPERATING_SYSTEM.indexOf(OPERATING_SYSTEM_FREEBSD) > -1 || OPERATING_SYSTEM.indexOf(OPERATING_SYSTEM_OSX) > -1);
	
	
	
	/**
	 * <pre>
	 * Finds and loads the runtime properties file for a specific OpenMRS application.
	 * Searches for the file in this order:
	 * 1) {current directory}/{applicationname}_runtime.properties
	 * 2) an environment variable called "{APPLICATIONNAME}_RUNTIME_PROPERTIES_FILE"
	 * 3) {openmrs_app_dir}/{applicationName}_runtime.properties   // openmrs_app_dir is typically {user_home}/.OpenMRS
	 * </pre>
	 * 
	 * @see #getApplicationDataDirectory()
	 * @param applicationName (defaults to "openmrs") the name of the running OpenMRS application,
	 *            e.g. if you have deployed OpenMRS as a web application you would give the deployed
	 *            context path here
	 * @return runtime properties, or null if none can be found
	 * @since 1.8
	 */

	public static Properties getRuntimeProperties(String applicationName) {
		
		if (applicationName == null)
			applicationName = "openmrs";
		
		FileInputStream propertyStream = null;
		
		String filename = applicationName + "-runtime.properties";
		// first look in the current directory (that java was started from)
		runtimePropertiesPathName = filename;
		System.out.println("Attempting to load properties file in current directory: " + runtimePropertiesPathName);
		try {
			propertyStream = new FileInputStream(runtimePropertiesPathName);
		}
		catch (FileNotFoundException e) {
		}
		
		// next look for an environment variable
		if (propertyStream == null) {
			String envVarName = applicationName.toUpperCase() + "_RUNTIME_PROPERTIES_FILE";
			runtimePropertiesPathName = System.getenv(envVarName);
			if (runtimePropertiesPathName != null) {
				System.out.println("Atempting to load runtime properties from: " + runtimePropertiesPathName);
				try {
					propertyStream = new FileInputStream(runtimePropertiesPathName);
				}
				catch (IOException e) {
				}
			}
		}
		
		// next look in the OpenMRS application data directory
		if (propertyStream == null) {
			runtimePropertiesPathName = OpenmrsUtil.getApplicationDataDirectory() + filename;
			System.out.println("Attempting to load property file from: " + runtimePropertiesPathName);
			try {
				propertyStream = new FileInputStream(runtimePropertiesPathName);
			}
			catch (FileNotFoundException e) {
			}
		}
		
		try {
			if (propertyStream != null) {
				Properties props = new Properties();
				loadProperties(props, propertyStream);
				propertyStream.close();
				System.out.println("Using runtime properties file: " + runtimePropertiesPathName);
				return props;
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
		
		System.out.println("Failed to get runtime properties file.");
		
		return null;
		
		/*if (applicationName == null)
			applicationName = "openmrs";
		
		runtimePropertiesPathName = null;
		FileInputStream propertyStream = null;
		
		// first look for an environment variable
		{
			String envVarName = applicationName.toUpperCase() + "_RUNTIME_PROPERTIES_FILE";
			runtimePropertiesPathName = System.getenv(envVarName);
			if (runtimePropertiesPathName != null) {
				try {
					propertyStream = new FileInputStream(runtimePropertiesPathName);
				}
				catch (IOException e) {
				}
			}
		}
		
		String filename = applicationName + "-runtime.properties";
		
		// next look in the OpenMRS application data directory
		if (propertyStream == null) {
			runtimePropertiesPathName = OpenmrsUtil.getApplicationDataDirectory() + filename;
			System.out.println("Attempting to load property file from: " + runtimePropertiesPathName);
			try {
				propertyStream = new FileInputStream(runtimePropertiesPathName);
			}
			catch (FileNotFoundException e) {
			}
		}
		
		// last chance, look in the current directory (that java was started
		// from)
		if (propertyStream == null) {
			runtimePropertiesPathName = filename;
			System.out.println("Attempting to load properties file in current directory: " + runtimePropertiesPathName);
			try {
				propertyStream = new FileInputStream(runtimePropertiesPathName);
			}
			catch (FileNotFoundException e) {
			}
		}
		
		try {
			if (propertyStream != null) {
				Properties props = new Properties();
				loadProperties(props, propertyStream);
				propertyStream.close();
				System.out.println("Using runtime properties file: " + runtimePropertiesPathName);
				return props;
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
			return null;
		}
		
		System.out.println("Failed to get runtime properties file.");
		
		return null;*/
	}
	
	/**
	 * <pre>
	 * Returns the application data directory. Searches for the value first 
	 * in the "application_data_directory" runtime property, then in the servlet
	 * init parameter "application.data.directory." If not found, returns:
	 * a) "{user.home}/.OpenMRS" on UNIX-based systems
	 * b) "{user.home}\Application Data\OpenMRS" on Windows
	 * </pre>
	 * 
	 * @return The path to the directory on the file system that will hold miscellaneous data about
	 *         the application (runtime properties, modules, etc)
	 */
	private static String getApplicationDataDirectory() {
		
		String filepath = null;
		
		if (UNIX_BASED_OPERATING_SYSTEM)
			filepath = System.getProperty("user.home") + File.separator + ".OpenMRS";
		else
			filepath = System.getProperty("user.home") + File.separator + "Application Data" + File.separator
			+ "OpenMRS";
		
		filepath = filepath + File.separator;
		
		File folder = new File(filepath);
		if (!folder.exists())
			folder.mkdirs();
		
		return filepath;
	}
	
	/**
	 * Convenience method used to load properties from the given file.
	 * 
	 * @param props the properties object to be loaded into
	 * @param propertyFile the properties file to read
	 */
	private static void loadProperties(Properties props, InputStream inputStream) {
		try {
			InputStreamReader reader = new InputStreamReader(inputStream, "UTF-8");
			props.load(reader);
		}
		catch (FileNotFoundException fnfe) {
			System.out.println("Unable to find properties file" + fnfe);
		}
		catch (UnsupportedEncodingException uee) {
			System.out.println("Unsupported encoding used in properties file" + uee);
		}
		catch (IOException ioe) {
			System.out.println("Unable to read properties from properties file" + ioe);
		}
		finally {
			try {
				if (inputStream != null)
					inputStream.close();
			}
			catch (IOException ioe) {
				System.out.println("Unable to close properties file " + ioe);
			}
		}
	}
	
	public static String getRuntimePropertiesPathName(){
		return runtimePropertiesPathName;
	}
	
	public static String getMysqlPort(){
		return "3316";
	}
	public static void setDummyOS(){
		if(!UNIX_BASED_OPERATING_SYSTEM){
			System.setProperty(OPERATING_SYSTEM_KEY,OPERATING_SYSTEM_WINDOWS_DUMMY);
		}
	}
	
	public static void setDefaultOS(){
		System.setProperty(OPERATING_SYSTEM_KEY,OPERATING_SYSTEM);
	}
	
}
