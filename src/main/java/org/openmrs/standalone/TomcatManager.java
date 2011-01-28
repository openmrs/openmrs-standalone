package org.openmrs.standalone;

import java.io.File;
import java.net.InetAddress;
import java.net.MalformedURLException;

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Embedded;

import com.mysql.management.driverlaunched.ServerLauncherSocketFactory;

/**
 * Manages an embedded tomcat instance.
 */
public class TomcatManager {
	
	private Embedded container = null;
	
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
		engine.setName("Catalina");
		engine.addChild(localHost);
		engine.setDefaultHost(localHost.getName());
		container.addEngine(engine);
		
		// create http connector
		Connector httpConnector = container.createConnector((InetAddress) null, port, false);
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
		
		//stop tomcat.
		try {
			if (container != null) {
				container.stop();
			}
		}
		catch (LifecycleException exception) {
			System.out.println("Cannot Stop Tomcat" + exception.getMessage());
			return false;
		}
		
		//stop mysql.
		try {
			ServerLauncherSocketFactory.shutdown(new File("database"), new File("database/data"));
		}
		catch (Exception exception) {
			System.out.println("Cannot Stop MySQL" + exception.getMessage());
		}
		
		return true;
	}
}
