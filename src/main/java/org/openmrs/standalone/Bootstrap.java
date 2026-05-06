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

import ch.vorburger.exec.ManagedProcessException;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Properties;

/**
 * The only reason for existence of this class is to enable us increase tomcat memory by passing the
 * JVM options as advised at http://wiki.openmrs.org/display/docs/Out+Of+Memory+Errors. Since we
 * cannot pass JVM options when it has already loaded the application, we use this to class start a
 * new JVM process and hence have a chance of passing the JVM parameters.
 */
public class Bootstrap {
	
	class InputStreamProxy extends Thread {
		
		final InputStream is;
		
		final PrintStream os;
		
		InputStreamProxy(InputStream is, PrintStream os) {
			this.is = is;
			this.os = os;
		}
		
		public void run() {
			try {
				InputStreamReader isr = new InputStreamReader(is);
				BufferedReader br = new BufferedReader(isr);
				String line = null;
				while ((line = br.readLine()) != null) {
					os.println(line);
				}
			}
			catch (IOException ex) {
				throw new RuntimeException(ex.getMessage(), ex);
			}
		}
	}
	
	class OutputStreamProxy extends Thread {
		
		final OutputStream os;
		
		OutputStreamProxy(OutputStream os) {
			this.os = os;
		}
		
		public void run() {
			try {
				PrintWriter writer = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(os)), true);
				
				InputStreamReader isr = new InputStreamReader(System.in);
				BufferedReader br = new BufferedReader(isr);
				String line = null;
				while ((line = br.readLine()) != null) {
					writer.println(line);
				}
			}
			catch (IOException ex) {
				throw new RuntimeException(ex.getMessage(), ex);
			}
		}
	}
	
	/**
	 * Spawns off a new JVM to launch the main function of the ApplicationController class.
	 * 
	 * @param args the command line arguments.
	 * @param showSplashScreen determines whether the splashscreen is to be shown.
	 */
	private void launch(String[] args, boolean showSplashScreen) {
		
		Process process = null;
		
		try {	
			Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
			String vm_arguments = properties.getProperty("vm_arguments", "-Xmx512m -Xms512m -XX:NewSize=128m --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED");

			String javaHome = System.getProperty("java.home");
			String javaExecutable = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
			if (System.getProperty("os.name").toLowerCase().contains("win")) {
				javaExecutable += ".exe";
			}

			java.util.List<String> command = new java.util.ArrayList<String>();
			command.add(javaExecutable);
			if (showSplashScreen) {
				command.add("-splash:splashscreen-loading.png");
			}
			if (vm_arguments != null && !vm_arguments.trim().isEmpty()) {
				for (String arg : vm_arguments.trim().split("\\s+")) {
					command.add(arg);
				}
			}
			command.add("-cp");
			command.add(StandaloneUtil.getJarFileName());
			command.add("org.openmrs.standalone.ApplicationController");
			if (args != null) {
				for (String arg : args) {
					command.add(arg);
				}
			}

			// Spin up a separate java process calling a non-default Main class in our Jar.  
			process = Runtime.getRuntime().exec(command.toArray(new String[0]));
			
			// Proxy the System.out and System.err from the spawned process back to the main window.  This
			// is important or the spawned process could block.
			InputStreamProxy errorStreamProxy = new InputStreamProxy(process.getErrorStream(), System.err);
			InputStreamProxy inputStreamProxy = new InputStreamProxy(process.getInputStream(), System.out);
			OutputStreamProxy outputStreamProxy = new OutputStreamProxy(process.getOutputStream());
			
			errorStreamProxy.start();
			inputStreamProxy.start();
			outputStreamProxy.start();
			
			System.out.println("Exit:" + process.waitFor());
			
			System.exit(0);
		}
		catch (Exception ex) {
			System.out.println("There was a problem in the program.  Details:");
			ex.printStackTrace(System.err);
			
			if (null != process) {
				try {
					process.destroy();
				}
				catch (Exception e) {
					System.err.println("Error destroying process: " + e.getMessage());
				}
			}
		}
	}
	
	/**
	 * This is the entry point for the entire executable jar.
	 * 
	 * @param args the command line arguments.
	 */
	public static void main(String[] args) {
		String javaVersion = System.getProperty("java.version");
		int majorVersion = 0;
		if (javaVersion != null) {
			try {
				if (javaVersion.startsWith("1.")) {
					majorVersion = Integer.parseInt(javaVersion.substring(2, 3));
				} else {
					majorVersion = Integer.parseInt(javaVersion.split("[\\.-]")[0]);
				}
			} catch (Exception e) {
				System.err.println("Failed to parse java.version: " + javaVersion);
			}
		}

		if (majorVersion < 17 || majorVersion > 21) {
			String message = "OpenMRS Standalone requires Java 17 through 21 (Java 17 is recommended).\n"
					+ "You are currently running Java " + javaVersion + ".\n\n"
					+ (majorVersion >= 24 ? "Java 24+ removes security features required by OpenMRS, causing startup failures.\n"
							: majorVersion > 21 ? "Java versions above 21 are not yet supported by the bundled components.\n" : "")
					+ "Please install a supported Java version and use it to run this application.";
			try {
				javax.swing.JOptionPane.showMessageDialog(null, message, "Unsupported Java Version", javax.swing.JOptionPane.ERROR_MESSAGE);
			} catch (Exception e) {
				System.err.println(message);
			}
			System.exit(1);
		}

		boolean showSplashScreen = true;
		
		for (String arg : args) {
			if (arg.contains("commandline"))
				showSplashScreen = false;
		}
		
		// add shutdown hook to stop server
		Runtime.getRuntime().addShutdownHook(new Thread() {
			
			public void run() {
				try {
					MariaDbController.stopMariaDB();
				} catch (ManagedProcessException e) {
					System.out.println("Failed to stop MariaDB: " + e.getMessage());
					e.printStackTrace();
				}
			}
		});
		
		new Bootstrap().launch(args, showSplashScreen);
	}
}
