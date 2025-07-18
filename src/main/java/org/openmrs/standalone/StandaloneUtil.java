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
import java.io.*;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.vorburger.exec.ManagedProcessException;

import static org.openmrs.standalone.OpenmrsUtil.importSqlFile;

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
	private static final String ROOT_USER = "root";

	private static String CONTEXT_NAME;

	static Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
	
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
			
			try {
				closeConnections(ss, ds);
				
				//Checking if port is open by trying to connect as a client;
		        Socket socket = new Socket("127.0.0.1", port);          
		        socket.close();
		        return false; //Someone responding on port - so not available;
		    } catch (Exception e) {    
		        //Connection refused, so port must be available
		    }

			return true;
		}
		catch (IOException e) {}
		finally {
			closeConnections(ss, ds);
		}
		
		return false;
	}
	
	private static void closeConnections(ServerSocket ss, DatagramSocket ds) {
		if (ds != null)
			ds.close();
		
		if (ss != null) {
			try {
				ss.close();
			}
			catch (IOException e) {}
		}
	}

	private static String generateSecurePassword() {
		// intentionally left out these characters: ufsb$() to prevent certain words forming randomly
		String chars = "acdeghijklmnopqrtvwxyzACDEGHIJKLMNOPQRTVWXYZ0123456789.|~@^&";
		StringBuilder sb = new StringBuilder();
		Random r = new Random();
		for (int x = 0; x < 12; x++) {
			sb.append(chars.charAt(r.nextInt(chars.length())));
		}
		return sb.toString();
	}
	
	/**
	 * Changes the MariaDB and tomcat ports in the run time properties file and also changes the mariaDB
	 * password if it is "test".
	 * 
	 * @param mariaDBPort the mariaDB port number.
	 * @param tomcatPort the tomcat port number.
	 * @return the mysql port number. If supplied in the parameter, it will be the same, else the
	 *         one in the connection string.
	 */
	public static String setPortsAndMySqlPassword(String mariaDBPort, String tomcatPort) {
		final String KEY_CONNECTION_USERNAME = "connection.username";
		final String KEY_CONNECTION_PASSWORD = "connection.password";
		final String KEY_CONNECTION_URL = "connection.url";
		final String KEY_TOMCAT_PORT = "tomcatport";
		final String KEY_RESET_CONNECTION_PASSWORD = "reset_connection_password";
		
		InputStream input = null;
		boolean propertiesFileChanged = false;
		
		try {
			Properties properties = OpenmrsUtil.getRuntimeProperties(getContextName()); //new Properties();

			if (properties != null) {
				String connectionString = properties.getProperty(KEY_CONNECTION_URL);
				String username = properties.getProperty(KEY_CONNECTION_USERNAME);
				String resetConnectionPassword = properties.getProperty(KEY_RESET_CONNECTION_PASSWORD);

				String portToken = ":" + mariaDBPort + "/";

				//in a string like this: jdbc:mysql://localhost:3316/openmrs?autoReconnect=true
				//look for something like this :3316/
				String regex = ":[0-9]+/";
				Pattern pattern = Pattern.compile(regex);
				Matcher matcher = pattern.matcher(connectionString);

				//Check if we have a port number to set.
				if (mariaDBPort != null) {
					//If the port has changed, then update the properties file with the new one.
					if (!connectionString.contains(portToken)) {
						connectionString = matcher.replaceAll(portToken);
						properties.put(KEY_CONNECTION_URL, connectionString);

						propertiesFileChanged = true;
					}
				} else {
					//Extract the port number in the connection string, for returning to the caller.
					if (matcher.find()) {
						mariaDBPort = matcher.group();
						mariaDBPort = mariaDBPort.replace(":", "");
						mariaDBPort = mariaDBPort.replace("/", "");
					}
				}


				//We change the mysql password only if it is test.
				//if (password != null && password.toLowerCase().equals("test")) {

				//Change the mysql password if instructed to.
				if ("true".equalsIgnoreCase(resetConnectionPassword)) {
					String newPassword = generateSecurePassword();

					boolean passwordChanged = setMysqlPassword(connectionString, mariaDBPort, username, newPassword);

					if (passwordChanged) {
						properties.put(KEY_CONNECTION_PASSWORD, newPassword);
						//Now remove the reset connection password property such that we do not change the password again.
						properties.remove(KEY_RESET_CONNECTION_PASSWORD);
						propertiesFileChanged = true;
						System.out.println("✅ New password persisted.");
					} else {
						System.err.println("❌ Password not changed. Keeping existing configuration.");
					}
				}

				if (tomcatPort != null) {
					if (!tomcatPort.equals(properties.get(KEY_TOMCAT_PORT))) {
						properties.put(KEY_TOMCAT_PORT, tomcatPort);
						propertiesFileChanged = true;
					}
				}

				//Write back properties file only if changed.
				if (propertiesFileChanged) {
					writeRuntimeProperties(properties);
				}
			}
			
		} catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
			try {
				if (input != null){
					input.close();
				}
			}
			catch (Exception ex) {}
		}
		
		return mariaDBPort;
	}
	
	/**
     * Auto generated method comment
     * 
     * @param properties
     */
    private static void writeRuntimeProperties(Properties properties) {
    	//I just do not like the extra characters that the store() method puts in the properties file.
		//properties.store(output, null);
    	OutputStreamWriter output = null;
    	try {
    		output = new OutputStreamWriter(new FileOutputStream(OpenmrsUtil.getRuntimePropertiesPathName()), "UTF-8");

    		Writer out = new BufferedWriter(output);
    		out.write("\n#Last updated by the OpenMRS Standalone application.\n");
    		out.write("#" + new Date() + "\n");
    		for (Map.Entry<Object, Object> e : properties.entrySet()) {
    			out.write(e.getKey() + "=" + e.getValue() + "\n");
    		}
    		out.write("\n");
    		out.flush();
    		out.close();
    	} catch (IOException ex) {
    		throw new RuntimeException("Error writing runtime properties file", ex);
    	} finally {
    		try {
    			output.close();
    		} catch (Exception ex) { }
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
		catch (NumberFormatException ex) {}
		
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
		
		if (CONTEXT_NAME == null) {
			
			// This is the path to the application's base directory
			String path = getBaseDir();
			
			//Get the name of the war file in the tomcat/webapps folder.
			//If no war file found, the just get the name of the folder.
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
						CONTEXT_NAME = name;
					}
				}
			}
		}
		
		return CONTEXT_NAME;
	}

	private static boolean setMysqlPassword(String url, String mysqlPort, String username, String newPassword) throws Exception {
		try {
			Class.forName("com.mysql.cj.jdbc.Driver").newInstance();

			MariaDbController.startMariaDB(mysqlPort, properties.getProperty("connection.password", ""));

			String sqlCreate = "CREATE USER IF NOT EXISTS '" + username + "'@'localhost' IDENTIFIED BY '" + newPassword + "';";
			String sqlAlter = "ALTER USER '" + username + "'@'localhost' IDENTIFIED BY '" + newPassword + "';";
			String sqlFlush = "FLUSH PRIVILEGES;";

			try (Connection connection = DriverManager.getConnection(url, ROOT_USER, MariaDbController.getRootPassword());
				 Statement statement = connection.createStatement()) {

				statement.execute(sqlCreate); // ensure user exists
				statement.execute(sqlAlter);  // change password
				statement.execute(sqlFlush);  // apply changes
				return true;

			} catch (SQLException ex) {
				System.err.println("❌ Failed to update password.");
				ex.printStackTrace();
				return false;
			}


		} catch (Exception ex) {
			System.err.println("❌ Exception while setting MySQL password.");
			ex.printStackTrace();
			return false;

		} finally {
			try {
				MariaDbController.stopMariaDB();
			} catch (ManagedProcessException e) {
				System.out.println("Failed to stop MariaDB: " + e.getMessage());
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * Gets the name of the running jar file, without the path.
	 * 
	 * @return the name of the running jar file.
	 */
	public static String getJarFileName() {
		return getJarPathName().getName();
	}
	
	/**
	 * Gets the full path and name of the running jar file.
	 * 
	 * @return the full path and name of the jar file.
	 */
	private static File getJarPathName() {
		try {
			return new File(Bootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
		}
		catch (URISyntaxException ex) {
			ex.printStackTrace();
		}
		
		return null;
	}
	
	/**
	 * Gets the directory of the running jar file.
	 * 
	 * @return the full path of the running jar file.
	 */
	private static String getBaseDir() {
		String jarPathName = getJarPathName().getAbsolutePath();
		return jarPathName.substring(0, jarPathName.lastIndexOf(File.separatorChar));
	}
	
	/**
	 * Resets the connection.password in the runtime properties file to "test"
	 */
	public static void resetConnectionPassword() {
		System.out.println("Resetting connection.password to 'test'");
		Map<String, String> props = new HashMap<String, String>();
		props.put("connection.username", "openmrs");
		props.put("connection.password", "test");
		updateRuntimeProperties(props);
	}
	
	/**
	 * Sets the given runtime properties, and re-saves the file
	 * 
	 * @param newProps
	 */
	private static void updateRuntimeProperties(Map<String, String> newProps) {
		Properties properties = OpenmrsUtil.getRuntimeProperties(getContextName());
		properties.putAll(newProps);
		writeRuntimeProperties(properties);
	}
	
	
	/**
	 * Starts and stops MySQL, so that MariaDB can create the default user
	 * @throws Exception 
	 */
	public static void startupDatabaseToCreateDefaultUser(String mariaDBPort) throws Exception {
		try {
			Class.forName("com.mysql.cj.jdbc.Driver");
		} catch (ClassNotFoundException ex) {
			throw new RuntimeException("Cannot find MySQL driver class", ex);
		}

		Properties props = OpenmrsUtil.getRuntimeProperties(getContextName());
		String url = props.getProperty("connection.url");
		String password = props.getProperty("connection.password");
		String username = props.getProperty("connection.username");

		System.out.println("Starting MariaDB on port " + mariaDBPort + "...");
		MariaDbController.startMariaDB(mariaDBPort, password);

		System.out.println("Attempting to connect to the database: " + url);
		try (Connection conn = DriverManager.getConnection(url, ROOT_USER, MariaDbController.getRootPassword());
			 Statement stmt = conn.createStatement()) {

			// Check if connection is valid
			if (conn.isValid(5)) {

				System.out.println("✅ Connection to MariaDB successful.");

				// Find sql if exist to preload DB
				File dataDir = new File("db/data");

				if (dataDir.exists() && dataDir.isDirectory()) {
					// Find the first .sql file in the unzipped folder
					File[] sqlFiles = dataDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".sql"));
					if (sqlFiles != null && sqlFiles.length != 0) {
						// Run the first found SQL file
						File sqlFile = sqlFiles[0];
						importSqlFile(sqlFile, url, username, password);
					}
				}
			} else {
				System.err.println("❌ Connection established, but it is not valid.");
			}

		} finally {
			System.out.println("Stopping MariaDB...");
			MariaDbController.stopMariaDB();
		}
	}

	/**
	 * Sets the MySQL and Tomcat ports in the run time properties file.
	 * 
	 * @param mariaDBPort the mariaDB port number to set.
	 * @param tomcatPort the Tomcat port number to set.
	 * @return the mysql port number. If supplied in the parameter, it will be the same, else the
	 *         one in the connection string.
	 */
	public static String setRuntimePropertiesFileMysqlAndTomcatPorts(String mariaDBPort, String tomcatPort) {
		final String KEY_CONNECTION_URL = "connection.url";
		final String KEY_TOMCAT_PORT = "tomcatport";
		
		InputStream input = null;
		boolean propertiesFileChanged = false;
		
		try {
			Properties properties = OpenmrsUtil.getRuntimeProperties(getContextName()); //new Properties();
			String connectionString = properties.getProperty(KEY_CONNECTION_URL);
			String portToken = ":" + mariaDBPort + "/";

			//in a string like this: jdbc:mysql://localhost:3316/openmrs?autoReconnect=true
			//look for something like this :3316/
			String regex = ":[0-9]+/";
			Pattern pattern = Pattern.compile(regex);
			Matcher matcher = pattern.matcher(connectionString);
			
			//Check if we have a mysql port number to set.
			if (mariaDBPort != null) {
				
				//If the mysql port has changed, then update the properties file with the new one.
				if (!connectionString.contains(portToken)) {
					connectionString = matcher.replaceAll(portToken);
					properties.put(KEY_CONNECTION_URL, connectionString);
					
					propertiesFileChanged = true;
				}
			} else {
				//Extract the mysql port number in the connection string, for returning to the caller.
				if (matcher.find()) {
					mariaDBPort = matcher.group();
					mariaDBPort = mariaDBPort.replace(":", "");
					mariaDBPort = mariaDBPort.replace("/", "");
				}
			}
			
			//Set the Tomcat port
			if (tomcatPort != null) {
				if (!tomcatPort.equals(properties.get(KEY_TOMCAT_PORT))) {
					properties.put(KEY_TOMCAT_PORT, tomcatPort);
					propertiesFileChanged = true;
				}
			}
			
			//Write back properties file only if changed.
			if (propertiesFileChanged) {
				writeRuntimeProperties(properties);
			}
			
		}
		finally {
			try {
				if (input != null)
					input.close();
			}
			catch (Exception ex) {}
		}
		
		return mariaDBPort;
	}
}
