package org.openmrs.standalone;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * The only reason for existence of this class is to enable us increase tomcat memory by passing the
 * JVM options as advised at http://wiki.openmrs.org/display/docs/Out+Of+Memory+Errors. Since we
 * cannot pass JVM options when it has already loaded the application, we use this to class start a
 * new JVM process and hence have a chance of passing the JVM parameters.
 */
public class Bootstrap {
	
	class StreamProxy extends Thread {
		
		final InputStream is;
		
		final PrintStream os;
		
		StreamProxy(InputStream is, PrintStream os) {
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
	
	/**
	 * Spawns off a new JVM to launch the main function of the ApplicationController class.
	 */
	private void launch() {
		
		Process process = null;
		
		try {
			// Spin up a separate java process calling a non-default Main class in our Jar.  
			process = Runtime
			        .getRuntime()
			        .exec(
			            "java -Xmx512m -Xms512m -XX:PermSize=256m -XX:MaxPermSize=256m -XX:NewSize=128m -cp standalone-0.0.1-SNAPSHOT.jar org.openmrs.standalone.ApplicationController");
			
			// Proxy the System.out and System.err from the spawned process back to the main window.  This
			// is important or the spawned process could block.
			StreamProxy errorStreamProxy = new StreamProxy(process.getErrorStream(), System.err);
			StreamProxy outStreamProxy = new StreamProxy(process.getInputStream(), System.out);
			
			errorStreamProxy.start();
			outStreamProxy.start();
			
			System.out.println("Exit:" + process.waitFor());
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
	 * @param args
	 */
	public static void main(String[] args) {
		new Bootstrap().launch();
	}
}
