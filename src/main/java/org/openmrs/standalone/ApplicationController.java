package org.openmrs.standalone;

/**
 * Manages the application workflow.
 */
public class ApplicationController {
	
	/** The application's view. */
	private MainFrame mainFrame;
	
	/** Helps us spawn background threads such that we do not freeze the UI. */
	private SwingWorker workerThread;
	
	/** Manages the tomcat instance. */
	private TomcatManager tomcatManager;
	
	/** The web app context name. */
	private String contextName;
	
	public ApplicationController() {
		init();
	}
	
	/**
	 * This is the entry point for the application.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		new ApplicationController();
	}
	
	/**
	 * Starts running the server.
	 */
	public void start() {
		
		/* Invoking start() on the SwingWorker causes a new Thread
		 * to be created that will call construct(), and then
		 * finished().  Note that finished() is called even if
		 * the worker is interrupted because we catch the
		 * InterruptedException in doWork().
		 */
		workerThread = new SwingWorker() {
			
			public Object construct() {
				return startServer();
			}
			
			public void finished() {
				Object value = workerThread.get();
				
				mainFrame.enableStart(value == null);
				mainFrame.enableStop(value != null);
				
				if (value != null) {
					mainFrame.setStatus("Running");
					StandaloneUtil.launchBrowser(mainFrame.getTomcatPort(), contextName);
				} else {
					mainFrame.setStatus("Stopped");
				}
			}
		};
		
		workerThread.start();
	}
	
	/**
	 * Stops the server from running.
	 */
	public void stop() {
		
		workerThread = new SwingWorker() {
			
			public Object construct() {
				return stopServer();
			}
			
			public void finished() {
				mainFrame.setStatus("Stopped");
				mainFrame.enableStart(true);
				mainFrame.enableStop(false);
				
				Runtime.getRuntime().gc();
			}
		};
		
		workerThread.start();
	}
	
	/**
	 * Stops the server, if running, and closes the application.
	 */
	public void exit() {
		
		workerThread = new SwingWorker() {
			
			public Object construct() {
				return stopServer();
			}
			
			public void finished() {
				System.exit(0);
			}
		};
		
		workerThread.start();
	}
	
	/**
	 * Creates the application window and automatically runs the server
	 */
	private void init() {
		mainFrame = new MainFrame(this);
		mainFrame.setStatus("Starting...");
		mainFrame.setVisible(true);
		
		// add shutdown hook to stop server
		Runtime.getRuntime().addShutdownHook(new Thread() {
			
			public void run() {
				stopServer();
			}
		});
		
		start();
	}
	
	/**
	 * Starts running tomcat.
	 * 
	 * @return "Running" status message if started successfully, else returns an error message.
	 */
	private String startServer() {
		try {
			contextName = StandaloneUtil.getContextName();
			tomcatManager = null;
			tomcatManager = new TomcatManager(contextName, mainFrame.getTomcatPort());
			tomcatManager.run();
			
			// Wait a second to be sure the server is ready
			Thread.sleep(1000);
			
			return "Running";
		}
		catch (Exception ex) {
			ex.printStackTrace();
			return ex.getMessage();
		}
	}
	
	/**
	 * Stops tomcat from running.
	 * 
	 * @return
	 */
	private String stopServer() {
		if (tomcatManager != null)
			tomcatManager.stop();
		
		return null;
	}
	
	/**
	 * Opens the OpenMRS setup wizard or login page in the user's default browser.
	 * 
	 * @param port the port at which tomcat is running.
	 * @return true if successfully opened the browser, else false.
	 */
	public boolean launchBrowser(int port) {
		return StandaloneUtil.launchBrowser(port, contextName);
	}
};
