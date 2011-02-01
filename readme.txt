...........................OpenMRS Standalone README..........................


NOTE: This standalone application is not tied to any particular openmrs version.

      As you can see from the pom file, the two artifacts mysql-connector-mxj and mysql-connector-mxj-dbfiles
      need to be put into our nexus maven repository. I have not yet done so because i do not yet have the
      permissions to. Therefore to make this project compile, i downloaded MySQL Connector/MXJ 5.0.11 from 
      http://dev.mysql.com/downloads/connector/mxj/
      Then decompressed the download file and looked into the mysql-connector-mxj-gpl-5-0-11 folder for two jar files
      named: mysql-connector-mxj-gpl-5-0-11.jar and mysql-connector-mxj-gpl-5-0-11-db-files.jar
      Then installed them into my local maven repository using the following respective maven commands:
      
mvn install:install-file -DgroupId=com.mysql -DartifactId=mysql-connector-mxj -Dversion=5.0.11 -Dfile=/mysql-connector-mxj-gpl-5-0-11.jar -Dpackaging=jar
      
mvn install:install-file -DgroupId=com.mysql -DartifactId=mysql-connector-mxj-dbfiles -Dversion=5.0.11 -Dfile=/mysql-connector-mxj-gpl-5-0-11-db-files.jar -Dpackaging=jar



...................HOW TO RUN FROM ECLIPSE.....................

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
      By Monday i will be done with the maven instructions. (How to run using maven)
      


...................HOW TO RESPOND TO THE OPENMRS SETUP WIZARD.....................


1-	Copy the "connection.url" value from the default-runtime.properties file, located at the project root folder, and
	paste it into the "Database Connection:" text field of the openmrs setup wizard.

2-  For the section: "Do you currently have an OpenMRS database installed that you would like to connect to?",
	select Yes. And for the database, leave the default as openmrs. If you want a different name, make sure it matches with
	your connection string as in Step 1 above.
	
3- 	Click "Continue" to go to the next wizard screen.

5-	For the section: "Do you need OpenMRS to automatically create the tables for your current database - openmrs?",
	select Yes.
	
6- 	For the section: "Do you want to also add demo data to your database - openmrs? (This option only available if creating new tables.)",
	just choose what you want.
	
7- 	For the section: "Do you currently have a database user other than root that has read/write access to the openmrs database?",
	Choose Yes, and then enter a user name and password for an account which will be created by the embedded database engine.
	If you choose No, enter a user name and password for an account which will be created by the openmrs setup wizard.
	In other wards, choosing Yes or No here is almost the same.
	
8-	Click "Continue" to go to the next wizard screen, and feel free to fill what you want on this screen.

9-	Click "Continue" to got to the next wizard screen where you will fill the openmrs admin account.

10-	Click "Continue" to go to the next wizard screen where you will fill whatever you want.

11-	Click "Continue" to go to the next wizard screen and click "Finish".


...................DISTRIBUTION FOLDER STRUCTURE............................

The release/distribution (end user) folder structure should look like this:

contextname-runtime.properties       e.g openmrs-runtime.properties, openmrs-1.6.1-runtime.properties, openmrs-1.9.0-runtime.properties, etc

standalone-0.0.1-SNAPSHOT.jar                       This is the output executable jar for this standalone project. 
									 You can build this right from eclipse by right clicking on the project
									 and then select Export -> Java -> Runnable JAR file.
									 The name of this jar file needs to be standalone-0.0.1-SNAPSHOT.jar because it is 
									 hard coded in the Bootstrap class as so.
                                        
tomcat/conf/web.xml                  This has the jsp servlet mapping, mime mappings, and other parameters shared
									 by all web applications in this tomcat instance.

tomcat/webapps/openmrs.war			 This is the application war file. You could as well use the expanded folder.
                                     The name of this war file, or expanded web app folder is used to determine the
                                     context name. Therefore this tomcat/webapps/ folder should not contain any other
                                     file or folder apart from the war file or expanded app folder. If you ever want to run
                                     multiple versions of openmrs, then make sure that the name of the war file is different
                                     for each. e.g openmrs.war, openmrs-1.6.1.war, openmrs-1.7.0.war, etc

database/data                 If you do not want the user to be taken through the openmrs web database setup wizard, just copy
									 all the contents of the mysql data folder into this. This folder is the default one but you
									 can change the location using the database connection string.

tomcat/logs							 This is where the log files are created with names having a convention of 
									 day-month-year.log  That means each day has a separate log file.
									 You do not need to create this folder because it can be automatically created
									 by the application. The logs displayed in the textarea of the UI are just a
									 convenient way of showing what is going on without having to first open the 
									 log file. Not to run out of memory, the text area displayed logs are
									 trimmed, starting with the oldest, in order not to exceeed 1,000 characters.
									 

............... DATABASE CONNECTION STRING.......................

jdbc:mysql:mxj://localhost:3316/openmrs?autoReconnect=true&sessionVariables=storage_engine=InnoDB&useUnicode=true
&characterEncoding=UTF-8&server.initialize-user=true&createDatabaseIfNotExist=true&server.basedir=database
&server.datadir=database/data&server.collation-server=utf8_general_ci&server.character-set-server=utf8

The above default database connection string has all in the openmrs mysql default database connection string plus a 
few additional parameters as explained below:

mxj             This is required for the MySQL Connector/MXJ utility which we use for embedding mysql
				More information about it can be found at: http://dev.mysql.com/doc/refman/5.1/en/connector-mxj.html
				
server.initialize-user      	The value of true tells the database engine to create the user account that will be specified
						    	in the openmrs web database setup. This is the account referred to as 
						    	connection.username & connection.password in the runtime properties file.
						   
createDatabaseIfNotExist    	The value of true tells the database engine to create the database if it does not exist.

server.basedir 			    	This is the directory where the mysql database server will be installed. The default value
						    	is a database folder in the current directory where the executable jar file is located.
			
server.datadir    		    	This is the dirrectory where mysql stores the database. The default value is the data folder
						    	under the database folder in the current directory where the executable jar file is located.
						   
server.collation-server     	This sets the collation of the database server. If you do not set it to this value, you will
						    	get problems running the openmrs liquibase files. This is because the default value is swedish
						    	collation yet openmrs uses utf8
					
server.character-set-server 	This is the character set used by the database server.

						   
NOTE: When creating a new database using the openmrs database setup wizard, remember to replace the default connection string
	  with the one above in the "Database Connection:" text field.
	  
	  The embedded mysql database engine is a fully functional database engine that you can connect too using any database 
	  GUI query tools like Navicat, EMS MySQL Manager, etc


.......................APPLICATION USER INTERFACE.....................

Tomcat Port					This is the port at which to run tomcat.
MySQL Port					This is the port at which to run mysql

File -> Quit				This menu item stops tomcat and mysql and then closes the application.
File -> Launch Browser		This menu item opens the openmrs login page for the current web application context.

Start						This button runs tomcat, which will automatically start the mysql database engine if it
							was not already running. For the embedded mysql, the first connection automatically starts
							the mysql engine.
							
Stop						This button stops tomcat and then also stops the mysql database engine.


NOTE: Minimizing or Maximizing the application window does not have any effect on the server. The window close icon will stop
	  the server (behaves as File -> Quit) but will first ask if you really want to, and will only do so when you select
	  the Yes option.
	  
	 
	  
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
         
         
     
MySQL MXJ documentation can be found at:
http://dev.mysql.com/doc/refman/5.1/en/connector-mxj.html

Details on how to add or remove platform specific databases can be found at: 
http://dev.mysql.com/doc/refman/5.1/en/connector-mxj-usagenotes-packaging.html