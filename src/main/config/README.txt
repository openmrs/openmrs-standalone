+--------------------+
| OpenMRS Standalone |
+--------------------+

Thank you for downloading OpenMRS. The README file contains important information about how to get started with our standalone installer. You can always find an up-to-date version of this document at: http://go.openmrs.org/standalone-readme

CONTENTS
========
1.	Background
2.	Warnings
3.	System requirements
4. 	Running the standalone app
		A. Windows
		B. Mac OS X
		C. Linux
5.	Login Details
6.	Changing the password
7. 	GUI mode options
8. 	Command line options

BACKGROUND
==========

The OpenMRS Standalone is a great way to evaluate and explore OpenMRS, getting you get a local copy of OpenMRS up and running within minutes.

OpenMRS Standalone provides a simplified installation option with an embedded database and web server.  Simply download, expand the archive, run the openmrs-standalone.jar file inside, and it will open your browser to your new OpenMRS instance.

We limited experience using OpenMRS Standalone in production environments. We expect it should run fine for smaller installations (less than 10,000 patient records), but if you're setting up a large production installation, we recommend doing a standard installation. (It is possible to start with the Standalone, and migrate data to a standard installation later.)

WARNINGS
========

- Do NOT delete or rename any folders after decompressing the zip file. They are used by the standalone jar file and it expects them to be in the exact locations where they already are.
- You MUST immediately change the admin password after installation for security purposes.

SYSTEM REQUIREMENTS
===================

You must have ﻿Java 6+ installed on your system to run OpenMRS.

RUNNING THE STANDLONE APP
=========================

Windows
-------

Option 1: Double click the 'openmrs-standalone.jar' file in the expanded archive folder to launch OpenMRS.

Option 2: From the Windows command line, navigate to the expanded archive folder and run this command: 
java -jar openmrs-standalone.jar

Mac OS X
--------

Option 1: Double click 'openmrs-standalone.jar' file in the expanded archive folder to launch OpenMRS.

Option 2: Launch Terminal.app or another command line tool and navigate to the root directory of the expanded archive file. Run this command: 
java -jar openmrs-standalone.jar

Linux
-----

Option 1: Using a graphical shell such as KDE, GNOME, etc., open the folder that was created when extracting the ZIP file. Double click on the shell script named 'run-on-unix.sh'. If you a get prompted for how to run it, just select 'run'.

Option 2: From the command line, navigate to the directory created when expanding the ZIP file. Run either of the commands below: 
java -jar openmrs-standalone.jar
or
./run-on-unix.sh


LOGIN DETAILS (remember to change the password immediately after installation in a production environment)
=============
username - admin
password - Admin123


CHANGING THE USERNAME AND PASSWORD
==================================

- See below for how to start/stop OpenmMRS.
- Login with the credentials above.
- From the browser, in the top right corner click on 'My Profile' link,then on the left panel, select the 'Change Login Info' tab, enter the old and new password, confirm new password and click 'Save Options'.


GUI MODE OPTIONS
================

Tomcat Port			This is the port at which to run tomcat.
MySQL Port 			This is the port at which to run mysql
File → Quit			This menu item stops tomcat and mysql and then closes the application.
File → Launch Browser 	This menu item opens the openmrs login page for the current web application context.
File → Clear Output 	This clears the output log in the user interface text area. But does not clear the log file written on the file system.
Start 				This button runs tomcat, which will automatically start the mysql database engine if it was not already running. For the embedded mysql, the first connection 				     	automatically starts the mysql engine.
Stop 				This button stops tomcat and then also stops the mysql database engine, without closing the application. 

COMMAND LINE OPTIONS
====================

-mysqlport		Use to override the mysql port in the runtime properties file.
-tomcatport		Use to override the tomcat port in the runtime properties file.
-start			Use to start the server.
-stop			Use to stop the server.
-browser			Use to launch a new browser instance.

---------------------------------------------------------------------

This Source Code Form is subject to the terms of the Mozilla Public License,
v. 2.0. If a copy of the MPL was not distributed with this file, You can
obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
the terms of the Healthcare Disclaimer located at http://openmrs.org/license.

Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
graphic logo is a trademark of OpenMRS Inc.

---------------------------------------------------------------------
