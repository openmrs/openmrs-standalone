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

import ch.vorburger.mariadb4j.DB;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.Base64;

import static org.openmrs.standalone.MariaDbController.ROOT_USER;
import static org.openmrs.standalone.MariaDbController.ROOT_PASSWORD;
import static org.openmrs.standalone.MariaDbController.DATABASE_NAME;

public class OpenmrsUtil {
	
	public static String REFAPP_VERSION;
			
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
		setDummyOS();
		
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
	 * @param inputStream the properties file to read
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
	
	public static String getTitle() {
		return "O3 RefApp v" +REFAPP_VERSION + " Standalone";
	}

	/**
	 * Imports an SQL file into the database.
	 *
	 * @param sqlFile   The SQL file to import
	 */
	public static void importSqlFile(File sqlFile) {
		if (!sqlFile.exists()) {
			System.err.println("❌ SQL file not found: " + sqlFile.getAbsolutePath());
			return;
		}

		System.out.println("✅ Preparing to import data from " + sqlFile);

		// Using MariaDbController's DB instance
		DB dbInstance = getDB();
		if (dbInstance == null) {
			throw new IllegalStateException("MariaDB has not been started. Call MariaDbController.startMariaDB() first.");
		}

		try (InputStream in = Files.newInputStream(sqlFile.toPath())) {
			System.out.println("📥 Importing SQL from: " + sqlFile.getAbsolutePath());
			dbInstance.source(in, ROOT_USER, ROOT_PASSWORD, DATABASE_NAME);
			System.out.println("✅ Successfully imported SQL: " + sqlFile.getAbsolutePath());
		} catch (Exception e) {
			System.err.println("❌ Error importing SQL: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private static DB getDB() {
		try {
			java.lang.reflect.Field f = MariaDbController.class.getDeclaredField("mariaDB");
			f.setAccessible(true);
			return (DB) f.get(null);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Marker file dropped next to the Lucene index by the build pipeline when it has pre-built
	 * (baked) the search index for the bundled demo database.
	 * <p>
	 * Its presence lets the standalone skip the expensive startup rebuild on first run — but ONLY
	 * when the demo database is the one being imported, since that is the data the index was baked
	 * against. See {@link ApplicationController#canReusePrebuiltSearchIndex(DatabaseMode, boolean)}:
	 * reusing it after a Starter or wizard import would leave search backed by an index describing
	 * 50 patients that database does not contain.
	 */
	// Package-private, not private, so OpenmrsUtilTest asserts against this exact path instead of
	// re-deriving the three segments by hand: nothing would tie the two copies together, and the
	// test would keep passing against a path the standalone no longer uses.
	static final File PREBUILT_SEARCH_INDEX_MARKER =
			new File("appdata" + File.separator + "lucene" + File.separator + ".prebuilt");

	/**
	 * @return true if {@code appdata/lucene} is still the index baked against the bundled demo
	 *         database, untouched since the build pipeline put it there. Not simply "an index was
	 *         bundled": callers use this to decide whether the on-disk index can be trusted to
	 *         describe that demo database, and it stops being trustworthy the moment anything
	 *         overwrites or adds to that index, so the marker is cleared then - see
	 *         {@link #clearPrebuiltSearchIndexMarker()}. It survives a boot that changed neither,
	 *         which is what lets a refused rebuild be retried on the next start. We key off an
	 *         explicit marker file rather than the mere presence of the lucene directory, because
	 *         OpenMRS itself creates an empty index skeleton on startup which would otherwise be
	 *         mistaken for a populated index.
	 */
	public static boolean hasPrebuiltSearchIndex() {
		return PREBUILT_SEARCH_INDEX_MARKER.isFile();
	}

	/**
	 * Drops the marker, so {@link #hasPrebuiltSearchIndex()} stops claiming the on-disk index is the
	 * baked demo one. Called exactly when that claim stops being true: after the baked index has been
	 * reused (OpenMRS updates it in place from the first patient registered) and after a rebuild the
	 * server accepted. Never after a rebuild it refused, which overwrites nothing and leaves the
	 * marker still true of what is on disk - see
	 * {@link ApplicationController#updateSearchIndexAfterStartup(DatabaseMode, String)}.
	 */
	public static void clearPrebuiltSearchIndexMarker() {
		if (!PREBUILT_SEARCH_INDEX_MARKER.isFile()) {
			return;
		}
		if (PREBUILT_SEARCH_INDEX_MARKER.delete()) {
			System.out.println("Cleared the pre-built search index marker; the index no longer describes the demo database.");
		} else {
			// Not fatal, but say so: the next import of the demo database would skip its rebuild and
			// search it through an index built from different data.
			System.out.println("⚠️  Could not delete " + PREBUILT_SEARCH_INDEX_MARKER.getPath()
			        + "; delete it by hand, or a later Demo setup may skip its search index rebuild.");
		}
	}

	/**
	 * Asks the running server to rebuild its Lucene index, and reports whether the request was
	 * accepted.
	 * <p>
	 * The return value matters because the credentials below are the ones the bundled dumps ship. A
	 * boot that imported one of those dumps can rely on them; a boot that did not (the in-place
	 * upgrade in docs/user-guide.md, which brings the operator's own {@code database/}, or an
	 * expert-mode install whose admin password was chosen in the OpenMRS setup wizard) is
	 * authenticating against whatever password that installation set, so this can come back 401 and
	 * no rebuild happens at all. Callers that consume the pre-built index marker must not do so on
	 * the strength of having merely asked - see
	 * {@link ApplicationController#updateSearchIndexAfterStartup(DatabaseMode, String)}.
	 * <p>
	 * True means only that the server accepted the request: the rebuild itself runs asynchronously,
	 * and nothing here waits for it.
	 *
	 * @param resourceUrl the running web application's base URL
	 * @return true if the server answered 204, i.e. the rebuild was queued
	 */
	public static boolean rebuildEntireSearchIndex(String resourceUrl) {
		final String SEARCH_INDEX_URL = resourceUrl + "/ws/rest/v1/searchindexupdate";
		try {
			URL url = new URL(SEARCH_INDEX_URL);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();

			// Basic Auth with the credentials the bundled dumps ship. Correct for every boot that just
			// imported one of them; a database the operator brought themselves may well say otherwise,
			// which is why the caller is told whether this worked.
			String username = "admin";
			String password = "Admin123";
			String auth = username + ":" + password;
			String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
			String authHeader = "Basic " + encodedAuth;

			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json");
			conn.setRequestProperty("Authorization", authHeader);
			conn.setDoOutput(true);

			// Prepare the request body
			String body = "{}";
			try (OutputStream os = conn.getOutputStream()) {
				byte[] input = body.getBytes("utf-8");
				os.write(input, 0, input.length);
			}

			int responseCode = conn.getResponseCode();
			boolean accepted = responseCode == HttpURLConnection.HTTP_NO_CONTENT;
			if (accepted) {
				System.out.println("✅ Search index rebuild triggered successfully on startup.");
			} else {
				System.err.println("❌ Failed to trigger rebuild. Status: " + responseCode);
				if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED
				        || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
					// Say what to do about it. A bare status line reads as noise during a startup that
					// otherwise looks healthy, and search then quietly answers from whatever index is on
					// disk.
					System.err.println("   The request signs in as the default 'admin' account, so this is"
					        + " what a changed admin password looks like. Rebuild by hand from Home >"
					        + " System Administration > Manage Search Index, or restart to try again.");
				}
			}
			conn.disconnect();
			return accepted;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
}
