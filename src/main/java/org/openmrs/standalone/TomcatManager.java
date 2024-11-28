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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.AccessControlException;
import java.util.Random;

import ch.vorburger.exec.ManagedProcessException;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Embedded;

/**
 * Manages an embedded tomcat instance.
 */
public class TomcatManager {
	
	private Embedded container = null;
	
	/**
	 * The port number on which we wait for shutdown commands.
	 */
	private int port = 8005;
	
	/**
	 * The address on which we wait for shutdown commands.
	 */
	private String address = "localhost";
	
	/**
	 * A random number generator that is <strong>only</strong> used if the shutdown command string
	 * is longer than 1024 characters.
	 */
	private Random random = null;
	
	/**
	 * The shutdown command string we are looking for.
	 */
	private String shutdown = "SHUTDOWN";
	
	/**
	 * Creates a single webapp configuration to be run in Tomcat.
	 * 
	 * @param contextName the context name without leading slash, for example, "openmrs"
	 * @param port the port at which to run tomcat.
	 */
	public TomcatManager(String contextName, int port) {
		
		// create server
		container = new Embedded();
		container.setCatalinaHome("tomcat");
		
		// create context
		Context rootContext = container.createContext("/" + contextName, contextName);
		rootContext.setReloadable(true);
		
		// create host
		Host localHost = container.createHost("localhost", "webapps");
		localHost.addChild(rootContext);
		
		// create engine
		Engine engine = container.createEngine();
        engine.setService(container);
		engine.setName("Catalina");
		engine.addChild(localHost);
		engine.setDefaultHost(localHost.getName());
		container.addEngine(engine);
		
		// create http connector
		Connector httpConnector = container.createConnector((InetAddress) null, port, false);
		httpConnector.setURIEncoding("UTF-8");
		container.addConnector(httpConnector);
	}
	
	/**
	 * Starts the embedded Tomcat server.
	 */
	public void run() throws LifecycleException, MalformedURLException {
		container.setAwait(true);
		container.start();
	}
	
	/**
	 * Stops the embedded Tomcat server.
	 */
	public boolean stop() {
		
		boolean stopMySql = false;
		
		//stop tomcat.
		try {
			if (container != null) {
				container.stop();
				container = null;
				stopMySql = true;
			}
		}
		catch (LifecycleException exception) {
			System.out.println("Cannot Stop Tomcat" + exception.getMessage());
			return false;
		}
		
		if(stopMySql) {
            try {
                MariaDbController.stopMariaDB();
            } catch (ManagedProcessException e) {
				System.out.println("Failed to stop MariaDB: " + e.getMessage());
				e.printStackTrace();
            }
        }

		return true;
	}
	
	/**
	 * Wait until a proper shutdown command is received, then return. This keeps the main thread
	 * alive - the thread pool listening for http connections is daemon threads.
	 * 
	 * NOTE: This method has been copied and modified slightly from 
	 * org.apache.catalina.core.StandardServer.await()
	 * We should have just called container.getServer().await()
	 * but it returns null for getServer() and i do not know why :)
	 */
	public void await() {
		// Set up a server socket to wait on
		ServerSocket serverSocket = null;
		try {
			serverSocket = new ServerSocket(port, 1, InetAddress.getByName(address));
		}
		catch (IOException e) {
			System.out.println("TomcatManager.await: create[" + address + ":" + port + "]: " + e.getMessage());
			System.exit(1);
		}
		
		// Loop waiting for a connection and a valid command
		while (true) {
			
			// Wait for the next connection
			Socket socket = null;
			InputStream stream = null;
			try {
				socket = serverSocket.accept();
				socket.setSoTimeout(10 * 1000); // Ten seconds
				stream = socket.getInputStream();
			}
			catch (AccessControlException ace) {
				System.out.println("TomcatManager.accept security exception: " + ace.getMessage());
				continue;
			}
			catch (IOException e) {
				System.out.println("TomcatManager.await: accept: " + e.getMessage());
				System.exit(1);
			}
			
			// Read a set of characters from the socket
			StringBuilder command = new StringBuilder();
			int expected = 1024; // Cut off to avoid DoS attack
			while (expected < shutdown.length()) {
				if (random == null) {
					random = new Random();
				}
				expected += (random.nextInt() % 1024);
			}
			while (expected > 0) {
				int ch = -1;
				try {
					ch = stream.read();
				}
				catch (IOException e) {
					System.out.println("TomcatManager.await: read: " + e.getMessage());
					ch = -1;
				}
				if (ch < 32) { // Control character or EOF terminates loop
					break;
				}
				command.append((char) ch);
				expected--;
			}
			
			// Close the socket now that we are done with it
			try {
				socket.close();
			}
			catch (IOException e) {
				// Ignore
			}
			
			// Match against our command string
			boolean match = command.toString().equals(shutdown);
			if (match) {
				System.out.println("TomcatManager.shutdownViaPort");
				break;
			} else {
				System.out.println("TomcatManager.await: Invalid command '" + command.toString() + "' received");
			}
			
		}
		
		// Close the server socket and return
		try {
			serverSocket.close();
		}
		catch (IOException e) {
			// Ignore
		}
	}
}
