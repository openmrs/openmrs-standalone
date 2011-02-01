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

import java.awt.Desktop;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility routines used by the standalone application.
 */
public class StandaloneUtil {
	
	/**
	 * The minimum number of server port number.
	 */
	public static final int MIN_PORT_NUMBER = 1;
	
	/**
	 * The maximum number of server port number.
	 */
	public static final int MAX_PORT_NUMBER = 49151;
	
	/**
	 * Checks to see if a specific port is available.
	 * 
	 * @param port the port to check for availability
	 */
	public static boolean isPortAvailable(int port) {
		
		if ((port < MIN_PORT_NUMBER) || (port > MAX_PORT_NUMBER))
			return false;
		
		ServerSocket ss = null;
		DatagramSocket ds = null;
		try {
			ss = new ServerSocket(port);
			ss.setReuseAddress(true);
			ds = new DatagramSocket(port);
			ds.setReuseAddress(true);
			return true;
		}
		catch (IOException e) {}
		finally {
			if (ds != null)
				ds.close();
			
			if (ss != null) {
				try {
					ss.close();
				}
				catch (IOException e) {}
			}
		}
		
		return false;
	}
	
	/**
	 * Changes the MySQL port in the run time properties file connection string.
	 * 
	 * @param port the port number.
	 */
	public static void changeMySqlPort(String port) {
		final String KEY_CONNECTION_URL = "connection.url";
		final String KEY_RUNTIME_PROPERTIES_FILE = getContextName() + "-runtime.properties";
		
		String portToken = ":" + port + "/";
		
		InputStream input = null;
		OutputStreamWriter output = null;
		
		try {
			Properties properties = new Properties();
			input = new FileInputStream(new File(KEY_RUNTIME_PROPERTIES_FILE));
			properties.load(input);
			String connectionString = properties.getProperty(KEY_CONNECTION_URL);
			
			//If the port has changed, then update the properties file with the new one.
			if (!connectionString.contains(portToken)) {
				//in a string like this: jdbc:mysql:mxj://localhost:3306/openmrs?autoReconnect=true
				//look for :3306/
				String regex = ":[0-9]+/";
				Pattern pattern = Pattern.compile(regex);
				Matcher matcher = pattern.matcher(connectionString);
				connectionString = matcher.replaceAll(portToken);
				properties.put(KEY_CONNECTION_URL, connectionString);
				output = new OutputStreamWriter(new FileOutputStream(KEY_RUNTIME_PROPERTIES_FILE), "UTF-8");
				
				Writer out = new BufferedWriter(output);
				out.write("\n#Last updated by the OpenMRS Standalone application.\n");
				out.write("#" + new Date() + "\n");
				for (Map.Entry<Object, Object> e : properties.entrySet()) {
					out.write(e.getKey() + "=" + e.getValue() + "\n");
				}
				out.write("\n");
				out.flush();
				out.close();
				
				//I just do not like the extra characters that the store() method puts in the properties file.
				//properties.store(output, null);
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
		finally {
			try {
				if (input != null)
					input.close();
				
				if (output != null)
					output.close();
				
			}
			catch (Exception ex) {}
		}
	}
	
	/**
	 * Converts a string to an integer.
	 * 
	 * @param value the string value.
	 * @return the integer value.
	 */
	public static int fromStringToInt(String value) {
		try {
			return Integer.parseInt(value);
		}
		catch (Exception ex) {}
		
		return 0;
	}
	
	public static boolean launchBrowser(int port, String contextName) {
		try {
			// Before more Desktop API is used, first check 
			// whether the API is supported by this particular 
			// virtual machine (VM) on this particular host.
			if (Desktop.isDesktopSupported()) {
				Desktop desktop = Desktop.getDesktop();
				
				if (desktop.isSupported(Desktop.Action.BROWSE)) {
					desktop.browse(new URI("http://localhost:" + port + "/" + contextName));
					return true;
				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
		return false;
	}
	
	public static String getContextName() {
		
		// This will create a reference to a file in the
		// current working directory, which is the path
		// where the application started (at least on
		// Win32 & Mac OS X)
		File baseDirectory = new File("");
		
		// This is the path to the application's base directory
		String path = baseDirectory.getAbsolutePath();
		
		//Get the name of the war file in the tomcat/webapps folder.
		//If no war file found, the just get the name of the folder.
		String folderName = null;
		path = path + File.separatorChar + "tomcat" + File.separatorChar + "webapps";
		File webappsFolder = new File(path);
		File files[] = webappsFolder.listFiles();
		if (files != null) {
			for (File file : files) {
				String name = file.getName();
				if (file.isFile()) {
					if (name.endsWith(".war")) {
						return name.substring(0, name.length() - 4);
					}
				} else if (file.isDirectory()) {
					folderName = name;
				}
			}
		}
		
		return folderName;
	}
}
