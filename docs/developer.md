# OpenMRS 3 (O3) Standalone — Developer Guide

[← Back to the README](../README.md)

Building the standalone from source, testing changes, and internals. To *run* a downloaded build see
the [User guide](user-guide.md); to rebuild and publish the distributable for a new O3 Reference
Application release see the [Release runbook](releasing.md).

## Contents

- [Quick summary for building the standalone](#quick-summary-for-building-the-standalone)
- [Building & testing a code change locally](#building--testing-a-code-change-locally)
- [How to extract SQL dumps from a running SDK instance](#how-to-extract-sql-dumps-from-a-running-sdk-instance)
- [How to run from Eclipse](#how-to-run-from-eclipse)
- [Distribution folder structure (this is a MUST)](#distribution-folder-structure-this-is-a-must)
- [How to generate a database to include with a distibution](#how-to-generate-a-database-to-include-with-a-distibution)
- [Some rough statistics so far](#some-rough-statistics-so-far)
- [Reusable Embedded MariaDB (ReusableDB.java) for Windows Compatibility](#reusable-embedded-mariadb-reusabledbjava-for-windows-compatibility)

## Quick summary for building the standalone

* Increase the maven memory: e.g. export MAVEN_OPTS="-Xms1012m -Xmx2024m"
* mvn clean
* mvn package -Dopenmrs.version=2.7.4 -Drefapp.version=3.4.0
* You can also use mvn package to build the default version on openmrs.version=2.7.4 and refapp.version=3.4.0
* If running a second time, ALWAYS check to make sure mysql processes on port 3326 and 3328 are stopped. 
  If you DON'T do that, then the "mvn clean" will not really clean. 
  A good command to use is: "pkill -f standalone"  (kills anything with "standalone" in the path) 

-> output is in the target folder, as referenceapplkication-standalone-(refapp.version).zip
-> the contents of that zip are in the similarly-named folder under /target, if you want to test in-place

The standalone now supports loading a pre-initialized SQL database dump (from an SDK 3.x server).
This bypasses the slow XML/metadata bootstrapping and ensures demo data + search index are ready immediately.

## Building & testing a code change locally

If you only changed Java code (not the bundled distro/DB) and just want to compile and run the
unit tests, **do not use `mvn compile` / `mvn test`**. The reactor binds `openmrs-sdk:build-distro`
to the `process-resources` phase, so any normal lifecycle build first tries to rebuild the whole
distro and, when `target/distro` already exists, hits an interactive "choose a different directory?"
prompt that hangs (or fails under `-B`).

Instead, invoke the plugin goals **directly**, which bypasses the lifecycle and that interactive step:

```bash
# compile main + test sources, then run the tests (real surefire, honours the project's Java 8 target)
mvn -o -N compiler:compile compiler:testCompile surefire:test

# run a single test class while iterating
mvn -o -N surefire:test -Dtest=BootstrapTest -DfailIfNoTests=false
```

The MariaDB integration tests (`StandaloneUtilTest`, `MariaDbControllerTest`, `MariaDbRestartTest`)
start a real embedded MariaDB and take ~1–2 min total; the rest are sub-second. The full assembled
jar still requires the normal `mvn package` (which does run the distro build).

## How to extract SQL dumps from a running SDK instance
You can speed up the standalone by bundling it with an SQL dump of a fully initialized OpenMRS SDK 3.x server.

1. **Run your SDK instance and finish setup.**
2. Once everything is initialized (demo data, search index rebuilt), run this command from terminal:

```bash
mysqldump --single-transaction -u root -p openmrs > demo-db-3.4.0.sql
```
Replace 3.4.0 with your current RefApp version.

Place the dump in:

```bash
<project-root>/src/main/db/demo-db-3.4.0.sql       # for demo content
<project-root>/src/main/db/empty-db-3.4.0.sql      # if it's an empty schema only
```
The standalone will auto-detect and load the corresponding SQL dump based on your refapp.version.

## How to run from Eclipse

- Copy your war file into the "tomcat/webapps" folder. Where the tomcat folder is at the root of the project.

If you already have openmrs installed and do not want to interfere with it, just rename
your war file to something different from openmrs.war. Examples are openmrs-2.7.4.war, etc which suppoort Java 17 and above.

- Right click on the project and select Run As -> Run Configurations

- Then create a new launch configuration, with any name you want e.g OpenMRS Standalone, under Java Application

The main class should be org.openmrs.standalone.Bootstrap

If you try to run it now, you will get:
"Exception in thread "main" java.lang.NoClassDefFoundError: org/openmrs/standalone/ApplicationController"

This is because we have to build the executable jar file that the Bootstrap class supplies to the new
java process it spawns in another JVM instance in order to be able to pass tomcat options for 
increasing memory as advised at http://wiki.openmrs.org/display/docs/Out+Of+Memory+Errors

You can build this right from eclipse by:

- Right clicking on the project and then select Export -> Java -> Runnable JAR file. The name of this jar file needs to be standalone.jar because it is hard coded in the Bootstrap class as so.
- In the "Runnable Jar File Specification" window that shows up, select the launch configuration that you created above. (e.g OpenMRS Standalone)
- In the "Export Destination" field you can supply the root folder of your project. e.g openmrs/standalone/standalone.jar	
- For "Library handling", select "Extract required libraries into generated JAR".
- Click "Finish" and just select OK/Yes for any screens that may popup.
- Now you should be able to run the launch configuration that you created above, which will open the OpenMRS Standalone
main window and will eventually open your default browser taking you to the openmrs setup wizard. Ensure that the
contextname_runtime.properties file does not exist, else you will not be taken to the setup screen. After successfully
running setup, subsequent runs will always take you to the openmrs login screen.

NOTE: Using Maven Package will generate the executable jar file in the target folder. How to run directly from eclipse using maven is not yet done.

## Distribution folder structure (this is a MUST)

The release/distribution (end user) folder structure should look like this:
NOTE: Without this folder structure, you will get errors while trying to run the standalone application.

* contextname-runtime.properties 
 * e.g openmrs-runtime.properties, openmrs-3.4.0-runtime.properties, etc
 * If you want to use this runtime properties file, make sure that the web application context name does not match with any existing runtime properties file in say the user's home folder. This is because of the openmrs runtime properties file search order which will only look in the current application folder as the last resort if no runtime properties file has been found in any of the other possible locations.
* standalone.jar
 * This is the output executable jar for this standalone project.
 * You can build this right from eclipse by right clicking on the project and then select Export -> Java -> Runnable JAR file.
 * The name of this jar file needs to be standalone.jar because it is hard coded in the Bootstrap class as so.
* tomcat/conf/web.xml
 * This has the jsp servlet mapping, mime mappings, and other parameters shared by all web applications in this tomcat instance. You can copy this from "tomcat/conf" folder of your tomcat installation.
* tomcat/webapps/openmrs.war
 * This is the application war file. You could as well use the expanded folder.
 * The name of this war file, or expanded web app folder is used to determine the context name. Therefore this tomcat/webapps/ folder should not contain any other file or folder apart from the war file or expanded app folder. If you ever want to run multiple versions of openmrs, then make sure that the name of the war file is different for each. e.g openmrs.war, openmrs-1.6.1.war, openmrs-1.7.0.war, etc
* database/data
 * If you do not want the user to be taken through the openmrs web database setup wizard, just copy all the contents of the mysql data folder into this. This folder is the default one but you can change the location using the database connection string.
* tomcat/logs
 * This is where the log files are created with names having a convention of day-month-year.log  That means each day has a separate log file.
 * You do not need to create this folder because it can be automatically created by the application. The logs displayed in the textarea of the UI are just a convenient way of showing what is going on without having to first open the  log file. Not to run out of memory, the text area displayed logs are trimmed, starting with the oldest, in order not to exceeed 1,000 characters.
* splashscreen-loading.png
 * This is the splash screen displayed on startup. It can be any .png as long as the name remains the same because it is hardcoded in the application.

# How to generate a database to include with a distibution

1- Make sure you have no runtime properties file that the web application will find.
2- Make sure you have no extra modules that the web application will find located in the appdata/modules folder.
3- Run the standalone.jar. You can just double click it, or run from command line as above.
4- The application will start the tomcat server and then the mariadb4j database engine.
5- Follow the steps in the openmrs setup wizard to select where you want the demo database distribution or ciel database distribution to be created.
6- After the setup wizard is done, you will have a database created in the location you selected.
7- Open your browser and go to the openmrs setup wizard at http://localhost:8080/openmrs/spa
NOTE: The default location of the "database" folder is that where the standalone.jar file is.
      You should also add the application_data_directory key to the runtime properties file. Something like this:
      application_data_directory=appdata 

## Some rough statistics so far

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



MariaDB4j documentation can be found at: https://github.com/MariaDB4j/MariaDB4j

## Reusable Embedded MariaDB (ReusableDB.java) for Windows Compatibility
The OpenMRS Standalone project uses a custom wrapper around the MariaDB4j `DB` class called `ReusableDB`. This wrapper is designed to enhance the robustness of the startup process and improve cross-platform compatibility, particularly on Windows systems.


#### 🔍 Purpose

`ReusableDB` avoids deleting the `dataDir` when the database is already initialized. This:
- Prevents startup failures due to locked files on Windows.
- Supports seamless restarts of the Standalone without losing data.
- Makes switching between demo and empty databases more reliable.

#### ✅ How it Works
- Checks for the presence of the `openmrs` database directory inside `dataDir`.
- If not found, triggers the initial MariaDB install process.
- Otherwise, starts MariaDB using the existing configuration and data files.

#### 📦 Usage Example

```java
DBConfigurationBuilder config = DBConfigurationBuilder.newBuilder();
config.setPort(3316);
ReusableDB db = ReusableDB.openEmbeddedDB(config.build());
```
