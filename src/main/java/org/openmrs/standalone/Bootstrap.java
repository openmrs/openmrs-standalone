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
import java.util.ArrayList;
import java.util.List;
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
	private void launch(String args, boolean showSplashScreen) {
		
		Process process = null;
		
		try {	
			Properties properties = OpenmrsUtil.getRuntimeProperties(StandaloneUtil.getContextName());
			String vm_arguments = properties.getProperty("vm_arguments", "-Xmx512m -Xms512m -XX:NewSize=128m --add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -Djava.security.manager=allow");
			
			// Spin up a separate java process calling a non-default Main class in our Jar. Build the
			// command as an explicit argument array rather than a single string. The string form of
			// Runtime.exec() tokenises the whole command on whitespace, so any element that ever
			// carried a path with a space (e.g. an absolute classpath entry) would be split into
			// broken tokens. Today getJarFileName() returns a bare filename run from the install dir,
			// so no element contains a space - this is defensive hardening that keeps each element
			// intact regardless. (vm_arguments and args remain whitespace-separated token lists by
			// design; they carry flags, never paths with spaces.)
			List<String> command = buildCommand(showSplashScreen, vm_arguments, StandaloneUtil.getJarFileName(), args);
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
	 * Assembles the argv for the spawned JVM as an explicit list so each argument is passed to
	 * {@code Runtime.exec} intact rather than re-split on whitespace (see {@link #launch}). Pure and
	 * static so the argument ordering and the single-element handling of {@code jarFileName} can be
	 * unit-tested without actually spawning a process.
	 *
	 * @param showSplashScreen whether to pass {@code -splash:...}
	 * @param vmArguments space-separated JVM flags
	 * @param jarFileName the classpath entry; kept as a single element even if it contains a space
	 * @param args space-separated application arguments
	 * @return the command and its arguments, one element per argv slot
	 */
	static List<String> buildCommand(boolean showSplashScreen, String vmArguments, String jarFileName, String args) {
		List<String> command = new ArrayList<>();
		command.add("java");
		if (showSplashScreen) {
			command.add("-splash:splashscreen-loading.png");
		}
		addTokens(command, vmArguments);
		command.add("-cp");
		command.add(jarFileName);
		command.add("org.openmrs.standalone.ApplicationController");
		addTokens(command, args);
		return command;
	}

	/**
	 * Splits a whitespace-separated argument string into individual arguments and appends the
	 * non-empty ones to {@code command}. Mirrors the tokenisation the old single-string
	 * {@code Runtime.exec(String)} performed, but lets the caller assemble an explicit argv.
	 */
	static void addTokens(List<String> command, String arguments) {
		if (arguments == null) {
			return;
		}
		for (String token : arguments.trim().split("\\s+")) {
			if (!token.isEmpty()) {
				command.add(token);
			}
		}
	}

	/**
	 * This is the entry point for the entire executable jar.
	 *
	 * @param args the command line arguments.
	 */
	public static void main(String[] args) {
		boolean showSplashScreen = true;
		
		String commandLineArguments = "";
		for (String arg : args) {
			commandLineArguments += (" " + arg);
			
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
		
		new Bootstrap().launch(commandLineArguments, showSplashScreen);
	}
}
