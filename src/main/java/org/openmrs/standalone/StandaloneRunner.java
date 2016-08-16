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
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;


public class StandaloneRunner {
	
	private static File standaloneDirectory;
	
	private final static String SUCCESS_START_MESSAGE = "Running - Tomcat Port:8081  MySQL Port:33330";
	
	public static void main(String[] args) throws Exception {
		start();
	}
	
	private static void start() throws Exception {
		System.out.println("......................Starting Standalone................");
		
		Properties properties = new Properties();
		FileInputStream propertyStream = new FileInputStream(new File("").getAbsolutePath()+"/target/standalone/openmrs-standalone-runtime.properties");
		InputStreamReader reader = new InputStreamReader(propertyStream, "UTF-8");
		properties.load(reader);
		propertyStream.close();
		
		String connectionString = properties.getProperty(StandaloneUtil.KEY_CONNECTION_URL);
		connectionString = connectionString.replace("=database", "=target/demodatabase");
		connectionString = connectionString.replace("3316", "33330");
		properties.put(StandaloneUtil.KEY_CONNECTION_URL,connectionString);
		properties.remove(StandaloneUtil.KEY_RESET_CONNECTION_PASSWORD);
		properties.put("server.basedir", "target/demodatabase");
		writeRuntimeProperties(properties);
		
		standaloneDirectory = new File("target/standalone");
		
		BufferedReader normalStream = null;
		BufferedReader errorStream = null;
		try {
			Process process = Runtime.getRuntime().exec(
			    new String[] { "java", "-jar", "openmrs-standalone.jar", "-commandline", "-noninteractive" }, null,
			    standaloneDirectory);
			
			String output;
			normalStream = new BufferedReader(new InputStreamReader(process.getInputStream()));
			while ((output = normalStream.readLine()) != null) {
				if (output.indexOf(SUCCESS_START_MESSAGE) > -1) {
					System.out.println("Successfully started the standalone");
					resetLuceneIndex(properties);
					stop();
					break;
				}
			}
		}
		catch (Exception e) {
			throw new Exception("An error occurred while starting the standalone:" + e.getMessage());
		}
		finally {
			close(normalStream, errorStream);
		}
	}

	private static void resetLuceneIndex(Properties properties) throws SQLException, ClassNotFoundException {
		String connectionString = properties.getProperty(StandaloneUtil.KEY_CONNECTION_URL);
	    Connection connection = null;
	    PreparedStatement statement = null;
	    Properties dbProperties = new Properties();
	    dbProperties.put("user", properties.get("connection.username"));
	    dbProperties.put("password", properties.get("connection.password"));
	    try {
	    	Class.forName("com.mysql.jdbc.Driver");
	    	connection = DriverManager.getConnection(connectionString, dbProperties);
	    	
	    	statement = connection.prepareStatement("delete from global_property where property = 'search.indexVersion'");
	    	
	    	statement.execute();
	    	
	    	statement.close();
	    	connection.close();
	    } finally {
	    	if (statement != null) {
	    		try {
	    			statement.close();
	    		} catch (Exception e) {
	    			//close quietly
	    		}
	    	}
	    	if (connection != null) {
	    		try {
	    			connection.close();
	    		} catch (Exception e) {
	    			//close quietly
	    		}
	    	}
	    }
    }
	
	private static void stop() throws Exception {
		System.out.println("......................Shutting down standalone...................");
		File pidFile = new File(standaloneDirectory, ".standalone.pid");
		BufferedReader normalStream = null;
		BufferedReader errorStream = null;
		Scanner fileScanner = null;
		try {
			fileScanner = new Scanner(pidFile);
			if (fileScanner.hasNext()) {
				String processId = fileScanner.next().trim();
				fileScanner.close();
				if (fileScanner.ioException() != null) {
					throw fileScanner.ioException();
				}
				
				System.out.println("Found the standalone process id:" + processId);
				
				Runtime.getRuntime().exec("kill -9 " + processId);
				
				pidFile.deleteOnExit();
				System.out.println("Successfully shutdown the standalone...");
				
			} else {
				throw new Exception("Failed to acquire process id of standalone, please make sure it is running");
			}
		}
		catch (Exception e) {
			throw new Exception("An error occurred while shutting down the standalone:" + e.getMessage());
		}
		finally {
			close(normalStream, errorStream);
		}
	}
	
	private static void close(Closeable... closeables) throws Exception {
		try {
			for (Closeable c : closeables) {
				if (c != null) {
					c.close();
				}
			}
		}
		catch (IOException e) {
			throw new Exception("An error occurred while closing input streams:" + e.getMessage());
		}
	}
	
	public static void writeRuntimeProperties(Properties properties) {
    	OutputStreamWriter output = null;
    	try {
    		output = new OutputStreamWriter(new FileOutputStream(new File("").getAbsolutePath()+"/target/standalone/openmrs-standalone-runtime.properties"), "UTF-8");

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
}
