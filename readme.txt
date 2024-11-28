
...............QUICK SUMMARY FOR BUILDING THE STANDALONE.....................
* Increase the maven memory: e.g. export MAVEN_OPTS="-Xms1012m -Xmx2024m"
* mvn clean
* mvn package -Dopenmrs.version=1.9.0
* If you are building standalone for OpenMRS 1.8.x you need to append the above command with -P1.8.x 
and put in the main directory the Demo-1.8.0.sql file from https://wiki.openmrs.org/display/RES/Demo+Data
* If running a second time, ALWAYS check to make sure MariaDB processes on port 3326 and 3328 are stopped.
  If you DON'T do that, then the "mvn clean" will not really clean. 
  A good command to use is: "pkill -f standalone"  (kills anything with "standalone" in the path) 

-> output is in the target folder, as openmrs-standalone-(openmrs.version).zip
-> the contents of that zip are in the similarly-named folder under /target, if you want to test in-place


.......................OpenMRS Standalone README............................



........................HOW TO RUN FROM ECLIPSE.............................

-	Copy your war file into the "tomcat/webapps" folder. Where the tomcat folder is at the root of the project.
If you already have openmrs installed and do not want to interfere with it, just rename
your war file to something different from openmrs.war. Examples are openmrs-1.6.1.war, openmrs-1.9.0.war, etc

-	Right click on the project and select Run As -> Run Configurations

-	Then create a new launch configuration, with any name you want e.g OpenMRS Standalone, under Java Application
	The main class should be org.openmrs.standalone.Bootstrap

If you try to run it now, you will get:
"Exception in thread "main" java.lang.NoClassDefFoundError: org/openmrs/standalone/ApplicationController"

This is because we have to build the executable jar file that the Bootstrap class supplies to the new
java process it spawns in another JVM instance in order to be able to pass tomcat options for 
increasing memory as advised at http://wiki.openmrs.org/display/docs/Out+Of+Memory+Errors

You can build this right from eclipse by:

-	Right clicking on the project and then select Export -> Java -> Runnable JAR file.
  	The name of this jar file needs to be standalone-0.0.1-SNAPSHOT.jar because it is hard coded in the Bootstrap class as so.
  
- 	In the "Runnable Jar File Specification" window that shows up, select the launch configuration that you 
	created above. (e.g OpenMRS Standalone)
	
-	In the "Export Destination" field you can supply the root folder of your project.
	e.g openmrs/standalone/standalone-0.0.1-SNAPSHOT.jar
	
- 	For "Library handling", select "Extract required libraries into generated JAR".

-	Click "Finish" and just select OK/Yes for any screens that may popup.

-	Now you should be able to run the launch configuration that you created above, which will open the OpenMRS Standalone
main window and will eventually open your default browser taking you to the openmrs setup wizard. Ensure that the
contextname_runtime.properties file does not exist, else you will not be taken to the setup screen. After successfully
running setup, subsequent runs will always take you to the openmrs login screen.



NOTE: Using Maven Package will generate the executable jar file in the target folder.
      How to run directly from eclipse using maven is not yet done.
      


...................HOW TO RESPOND TO THE OPENMRS SETUP WIZARD.....................


1-	Copy the "connection.url" value from the default-runtime.properties file, located at the project root folder, and
	paste it into the "Database Connection:" text field of the openmrs setup wizard.

2-  For the section: "Do you currently have an OpenMRS database installed that you would like to connect to?",
	select No. And for the database, enter the default as openmrs.
	Enter "openmrs" and "test" as the username and password
	
3- 	Click "Continue" to go to the next wizard screen.

5-	For the section: "Do you need OpenMRS to automatically create the tables for your current database - openmrs?",
	select Yes.
	
6- 	For the section: "Do you want to also add demo data to your database - openmrs? (This option only available if creating new tables.)",
	just choose what you want.
	
7- 	For the section: "Do you currently have a database user other than root that has read/write access to the openmrs database?",
	Choose Yes, and then enter a "openmrs" and "test" as the user name and password. This account will be created by the embedded database engine.
	The reason to use 'test' is that when the application starts, it checks for the MariaDB password and if it is test, it is replaced with a randomly
	generated 12 character password which is written back to the runtime properties file.
	
8-	Click "Continue" to go to the next wizard screen, and feel free to fill what you want on this screen.

9-	Click "Continue" to got to the next wizard screen where you will fill the openmrs admin account.

10-	Click "Continue" to go to the next wizard screen where you will fill whatever you want.

11-	Click "Continue" to go to the next wizard screen and click "Finish".


...................DISTRIBUTION FOLDER STRUCTURE (This is a MUST)............................

The release/distribution (end user) folder structure should look like this:
NOTE: Without this folder structure, you will get errors while trying to run the standalone application.

contextname-runtime.properties       e.g openmrs-runtime.properties, openmrs-1.6.1-runtime.properties, openmrs-1.9.0-runtime.properties, etc
									 If you want to use this runtime properties file, make sure that the web application context name does
									 not match with any existing runtime properties file in say the user's home folder. This is because of 
									 the openmrs runtime properties file search order which will only look in the current application folder
									 as the last resort if no runtime properties file has been found in any of the other possible locations.

standalone-0.0.1-SNAPSHOT.jar        This is the output executable jar for this standalone project. 
									 You can build this right from eclipse by right clicking on the project
									 and then select Export -> Java -> Runnable JAR file.
									 The name of this jar file needs to be standalone-0.0.1-SNAPSHOT.jar because it is 
									 hard coded in the Bootstrap class as so.
                                        
tomcat/conf/web.xml                  This has the jsp servlet mapping, mime mappings, and other parameters shared
									 by all web applications in this tomcat instance. You can copy this from "tomcat/conf" folder of your tomcat installation.

tomcat/webapps/openmrs.war			 This is the application war file. You could as well use the expanded folder.
                                     The name of this war file, or expanded web app folder is used to determine the
                                     context name. Therefore this tomcat/webapps/ folder should not contain any other
                                     file or folder apart from the war file or expanded app folder. If you ever want to run
                                     multiple versions of openmrs, then make sure that the name of the war file is different
                                     for each. e.g openmrs.war, openmrs-1.6.1.war, openmrs-1.7.0.war, etc

database/data                 		 If you do not want the user to be taken through the openmrs web database setup wizard, just copy
									 all the contents of the mysql data folder into this. This folder is the default one but you
									 can change the location using the database connection string.

tomcat/logs							 This is where the log files are created with names having a convention of 
									 day-month-year.log  That means each day has a separate log file.
									 You do not need to create this folder because it can be automatically created
									 by the application. The logs displayed in the textarea of the UI are just a
									 convenient way of showing what is going on without having to first open the 
									 log file. Not to run out of memory, the text area displayed logs are
									 trimmed, starting with the oldest, in order not to exceeed 1,000 characters.
									
splashscreen-loading.png			 This is the splash screen displayed on startup. It can be any .png as long as the name remains
									 the same because it is hardcoded in the application.
									 


............... DATABASE CONNECTION STRING.......................

jdbc:mysql://127.0.0.1:3316/openmrs?autoReconnect=true&sessionVariables=storage_engine=InnoDB&useUnicode=true&characterEncoding=UTF-8

The above default database connection string has all in the openmrs mysql default database connection string used for a MariaDB
connection

						   
NOTE: When creating a new database using the openmrs database setup wizard, remember to replace the default connection string
	  with the one above in the "Database Connection:" text field.
	  
	  The embedded MariaDB4j database engine is a fully functional database engine that you can connect too using any database
	  GUI query tools like Navicat, EMS MySQL Manager, etc


.......................APPLICATION USER INTERFACE.....................

Tomcat Port					This is the port at which to run tomcat.
MySQL Port					This is the port at which to run MariaDB

File -> Quit				This menu item stops tomcat and MariaDB and then closes the application.
File -> Launch Browser		This menu item opens the openmrs login page for the current web application context.
File -> Clear Output		This clears the output log in the user interface text area. But does not clear the log file
							written on the file system.

Start						This button runs tomcat, which will automatically start the MariaDB database engine if it
							was not already running. For the embedded MariaDB4j, the first connection automatically starts
							the MariaDB engine.
							
Stop						This button stops tomcat and then also stops the MariaDB database engine, without closing the application.


NOTE: Minimizing or Maximizing the application window does not have any effect on the server. The window close icon will stop
	  the server (behaves as File -> Quit) but will first ask if you really want to, and will only do so when you select
	  the Yes option.
	  
	
	
...................HOW TO RUN FROM COMMAND LINE....................................

Running from command line requires the -commandline switch.
e.g. java -jar standalone-0.0.1-SNAPSHOT.jar -commandline

-mysqlport: 	Use to override the database port in the runtime properties file.
-tomcatport: 	Use to override the tomcat port in the runtime properties file.
start			Use to start the server.
stop			Use to stop the server.
browser			Use to launch a new browser instance.

 
 
...................HOW TO GENERATE A DATABASE TO INCLUDE WITH A DISTRIBUTION.....................

1- Make sure you have no runtime properties file that the web application will find.
2- Make sure you have no extra moduls that the web application will find
3- Run the standalone-0.0.1-SNAPSHOT.jar. You can just double click it, or run from command line as above.
4- This will take you through the openmrs setup wizard (because the runtime properties file was not found) and 
   respond to it as per the instructions above under "HOW TO RESPOND TO THE OPENMRS SETUP WIZARD"
5- After the wizard completes, copy the generated runtime properties file and put it in the root folder of the distribution
   as per the above instructions under "DISTRIBUTION FOLDER STRUCTURE"
6- Run packagezip.sh to create the distributable zip file
NOTE: The default location of the "database" folder is that where the standalone-0.0.1-SNAPSHOT.jar file is.
      Also remember to include the runtime properties file in the root folder of the distribution if you want this
      database to be used.
      You should also add the application_data_directory key to the runtime properties file. Something like this:
      application_data_directory=appdata 


	  
................. SOME ROUGH STATISTICS SO FAR...........................

The following are the various compressed standalone distribution sizes:

For all operating systems with a database that has demo data:      142 MB
For all operating systems with a database that has no demo data:   139 MB
For all operating systems without a database:   				   136 MB

Mac-OS-X-i386 without a database 59 MB
Linux-i386 without a database 54 MB
FreeBSD-x86 without a database 53 MB
Windows-x86 without a database 44 MB


NOTE: With database (either with or without demo data) means the user will not run through the openmrs setup wizard.
	  Where database does not mean mysql, it is rather the database files.
	  
SUMMARY: Using a single package for all (most) platforms approximately tripples the download size.
         Including an empty database without demo data increases the size by only around 3 MB
         Including a database with demo data increases the size by only around 6 MB
         
         
     
MariaDB4j documentation can be found at:
https://github.com/MariaDB4j/MariaDB4j
