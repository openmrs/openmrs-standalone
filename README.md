# OpenMRS 3 (O3) Standalone

[![Download O3 Standalone](https://img.shields.io/badge/Download-O3_Standalone-blue?style=for-the-badge)](https://nightly.link/openmrs/openmrs-standalone/workflows/build-o3-standalone/openmrs-emr3/openmrs-standalone-o3.zip)

Download the **O3 Standalone** — a single zip with everything included (OpenMRS 3 Reference
Application 3.7.1, demo data fully initialised, embedded database), no Docker required.
Unzip it and run `openmrs-standalone.jar`. The download above always tracks the latest build
of the [`openmrs-emr3`](https://github.com/openmrs/openmrs-standalone/tree/openmrs-emr3) branch.

The standalone is meant for evaluation, demos, training, and small single-machine use — not for
running in production. To move a standalone instance to a production server, see:

* **[Migrating to a production Docker deployment](docs/migrating-to-docker-o3.md)** — the official O3 Docker distribution (recommended).
* **[Migrating to a Tomcat + external database deployment](docs/migrating-to-tomcat-server.md)** — a conventional bare-metal/VM stack.

---

## NOTE FOR MACOS USERS RUNNING A DOWNLOADED ZIP

macOS tags every file extracted from a downloaded zip with the com.apple.quarantine
attribute, which makes dyld refuse to load the bundled MariaDB dylibs (libpcre2, openssl)
and the embedded database fails to initialize. The launcher strips the attribute
automatically at startup; if you are running an older build, do it manually:

    xattr -dr com.apple.quarantine <extracted-standalone-directory>

## QUICK SUMMARY FOR BUILDING THE STANDALONE

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

## BUILDING & TESTING A CODE CHANGE LOCALLY

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

## HOW TO UPGRADE THE STANDALONE TO A NEW REFERENCE APPLICATION RELEASE

This is the end-to-end runbook for moving the O3 standalone from one Reference
Application release to the next (e.g. `3.7.0` → `3.7.1`). Read it fully
before starting — the DB-dump regeneration has non-obvious timing.

### 0. Branch & build model (read first)

* **All O3 standalone work happens on the `openmrs-emr3` branch.** `master` is the
  legacy 2.x standalone — do not put O3 workflows there.
* `Build O3 Standalone` runs on **push to `openmrs-emr3`**. That push is what
  refreshes the README download badge, because it points at
  [nightly.link](https://nightly.link), which only serves artifacts from
  push/schedule runs — **never** from manual `workflow_dispatch` runs.
* Manual `workflow_dispatch` is effectively unavailable anyway: GitHub only fires
  `workflow_dispatch` for workflows that live on the **default branch** (`master`),
  and these workflows live only on `openmrs-emr3`. So the canonical trigger is a push.
* For the same reason, **`generate-db-dumps.yml` cannot be dispatched** — regenerate
  the dumps locally as described in step 2.

### 1. Confirm the new refapp version is published

The SDK resolves the distro from `org.openmrs:distro-emr-configuration`. Make sure
your target version exists before doing anything else:

```bash
curl -sL https://mavenrepo.openmrs.org/public/org/openmrs/distro-emr-configuration/maven-metadata.xml \
  | grep -o '<version>3\.7\.1[^<]*</version>'
```

### 2. Regenerate the bundled DB dumps (locally)

The build bundles per-version dumps `src/main/db/{demo,empty}-db-${refapp.version}.sql`
(see `src/main/assembly/zip-{demo,empty}-database.xml`). They MUST exist for the new
version or the build's Lucene-bake gate fails. Needs Docker + JDK 21.

**⚠️ Do not trust `scripts/generate-db-dumps.sh`'s fixed `sleep 60`** — for O3 that is
far too short. Full initialization takes **~14 minutes**: concepts load to ~4254 first
(~7 min), and only THEN does demo data generate (50 patients, thousands of obs). Dumping early
yields a broken ~1.6 MB file. Wait until the row counts stop changing.

**⚠️ Demo-data generation can crash mid-run (seen on 3.7.1 / core 2.8.8).**
`referencedemodata`'s `DemoObsGenerator` clamps generated numeric obs to the concept's
**`ConceptNumeric`** absolute bounds, but core ≥2.8 validates obs against the concept's
**`ConceptReferenceRange`** absolute bounds. When those diverge, a clamped value can still
fall outside the reference range and `saveObs` throws
`ValidationException: valueNumeric: error.value.outOfRange.{low,high}`, which **aborts the whole
run** — you get a partial dump (e.g. 39/50 patients) even though the container looks healthy.
The RNG is fixed-seed (`new Random(0)`), so it fails **identically every time** — a retry
does not help. On 3.7.1 the offenders were concept **210 (Alkaline phosphatase)**
(`low_absolute` NULL but reference range low is 0) and **4184 (Respiratory rate)**
(`hi_absolute` 999 vs reference range 99). Find them with:

```sql
SELECT crr.concept_id, cn.low_absolute, MAX(crr.low_absolute), cn.hi_absolute, MIN(crr.hi_absolute)
FROM concept_reference_range crr JOIN concept_numeric cn ON cn.concept_id=crr.concept_id
GROUP BY crr.concept_id, cn.low_absolute, cn.hi_absolute
HAVING (MAX(crr.low_absolute) IS NOT NULL AND (cn.low_absolute IS NULL OR cn.low_absolute < MAX(crr.low_absolute)))
    OR (MIN(crr.hi_absolute)  IS NOT NULL AND (cn.hi_absolute  IS NULL OR cn.hi_absolute  > MIN(crr.hi_absolute)));
```

Fix by tightening `ConceptNumeric` to the reference-range intersection so the module's clamp
produces valid values, using the **two-phase** demo generation in step (b) below. This is an
upstream `referencedemodata` bug worth reporting; the `ConceptNumeric` edits are baked into the
dumps (only the standalone's local dumps — CI just bundles them).

**Determine `CORE` from the target distro, don't assume the previous value.** Each refapp release
pins its own OpenMRS Core/platform version; bundling a mismatched core can break startup. Read it
from the distro the SDK resolves rather than copying the last build's number:

```bash
curl -sL "https://mavenrepo.openmrs.org/public/org/openmrs/distro-emr-configuration/$VER/distro-emr-configuration-$VER.pom" \
  | grep -oE '<openmrs.version>[^<]*</openmrs.version>'
```

Then keep `.github/workflows/build-o3-standalone.yml` in sync with whatever you use here.

```bash
VER=3.7.1             # the new version
CORE=2.8.8            # OpenMRS Core version (from the distro above; keep the workflow in sync)

# a) Build the distro (clear stale dirs first, or build-distro hits an interactive prompt under -B)
rm -rf target/distro target/openmrs3x
mvn -f pom-step-01.xml process-resources -Pci -B --settings .github/maven-settings.xml \
    -Drefapp.version=$VER -Dopenmrs.version=$CORE

# Two-phase flow (init demo-free → patch bounds → EMPTY dump → regenerate demo → DEMO dump).
# This ordering lets you inject the ConceptNumeric fix between concept-load and demo generation,
# which a single boot can't (demo runs in the same startup as concept load).
cd target/distro

# b) Phase 1 — init WITHOUT demo (demo is driven by the `referencedemodata` module via the
#    Initializer GP `referencedemodata.createDemoPatientsOnNextStartup`, NOT by
#    OMRS_CONFIG_ADD_DEMO_DATA). Set it to 0 so only concepts load.
sed -i '' 's#<value>50</value>#<value>0</value>#' \
  web/openmrs_config/globalproperties/referenceapplication-demo/globalproperties-core_demo.xml
docker compose up -d --build web
DB=$(docker compose ps -q db)
# wait until concepts stabilize (~4254) — patient/obs stay 0; watch with:
#   docker exec $DB mysql -uroot -popenmrs -N -B -e \
#     "SELECT (SELECT COUNT(*) FROM concept),(SELECT COUNT(*) FROM patient),(SELECT COUNT(*) FROM obs);" openmrs

# Patch the ConceptNumeric bounds flagged by the query above so the module clamps into the
# reference-range intersection (values here are the 3.7.1 offenders — re-derive for a new version):
docker exec $DB mysql -uroot -popenmrs -e \
  "UPDATE concept_numeric SET low_absolute=0 WHERE concept_id=210;
   UPDATE concept_numeric SET hi_absolute=99 WHERE concept_id=4184;" openmrs

# EMPTY dump (concepts + fix, no demo):
docker exec $DB mysqldump --single-transaction --routines --triggers -u root -popenmrs openmrs \
  > ../../src/main/db/empty-db-$VER.sql

# c) Phase 2 — turn demo back on and restart so referencedemodata regenerates with the fix in place.
#    NB: `docker compose up -d web` will NOT recreate an already-running container — use `restart`.
sed -i '' 's#<value>0</value>#<value>50</value>#' \
  web/openmrs_config/globalproperties/referenceapplication-demo/globalproperties-core_demo.xml
docker exec $DB mysql -uroot -popenmrs -e \
  "UPDATE global_property SET property_value='50' WHERE property='referencedemodata.createDemoPatientsOnNextStartup';" openmrs
docker compose restart web
# poll until patient=50 and obs stabilize (~7 min, concepts already loaded); confirm NO
# "Exception caught while creating demo data" in `docker compose logs web`, and all 50 patients
# have encounters (SELECT COUNT(DISTINCT patient_id) FROM encounter).

# DEMO dump (concepts + fix + demo):
docker exec $DB mysqldump --single-transaction --routines --triggers -u root -popenmrs openmrs \
  > ../../src/main/db/demo-db-$VER.sql
docker compose down -v
cd ../..
```

**Sanity-check before committing** (demo must have data, empty must not; and every demo
patient must have encounters — a lower obs count with <50 patients-with-encounters means the
generation crashed part-way, see the warning above):

| dump  | concept | patient | obs    | pts w/ enc | size   |
|-------|---------|---------|--------|------------|--------|
| demo  | ~4254   | 50      | ~5300  | 50         | ~17 MB |
| empty | ~4254   | **0**   | 0      | 0          | ~14 MB |

(3.7.1 actuals: demo = 4254 concept / 50 patient / 5348 obs / 271 visits / 1386 enc.)

### 3. Bump the version strings

* `.github/workflows/build-o3-standalone.yml` — **two** version pairs:
  * refapp — the `refapp_version` `workflow_dispatch` default and the `REFAPP_VERSION` env fallback;
  * core — the `openmrs_version` `workflow_dispatch` default and the `OPENMRS_VERSION` env fallback
    (only when the core version changed for this release — see step 2);
  * and the build-step comment that names the version.
* `README.md` — the prose line under the download badge. (The badge URL itself is
  version-independent — leave it.)
* `pom.xml` — the `spa.version` property pins the SPA bridge omod (`omod.spa`) to a
  **released** `spa-omod` version. The refapp distro leaves `omod.spa` unset, so the SDK
  would otherwise default it to a `-SNAPSHOT` (non-reproducible — the bundled module could
  change build to build). This is **independent of the refapp version** — usually leave it.
  Bump it only to adopt a newer released spa module; list what's available with:

  ```bash
  curl -sL https://mavenrepo.openmrs.org/public/org/openmrs/module/spa-omod/maven-metadata.xml \
    | grep -oE '<release>[^<]*</release>'
  ```

  The pin is applied by the `pin-distro-versions` antrun step in `pom-step-01.xml`, which
  rewrites `omod.spa` in the generated distro definition before `build-distro` runs (same
  mechanism as the core-war pin). Confirm a build bundled the release, not a snapshot:

  ```bash
  ls target/distro/web/openmrs_modules/spa-*.omod   # expect spa-${spa.version}.omod, not -SNAPSHOT
  ```

### 4. Remove the superseded dumps

Delete the previous version's `src/main/db/{demo,empty}-db-<old>.sql` (repo convention —
see the "Remove the superseded … DB dumps" commits).

### 5. Commit and push to `openmrs-emr3`

```bash
git checkout openmrs-emr3
git add -A && git commit -m "Base the O3 standalone download on Reference Application $VER"
git push origin openmrs-emr3
```

The push triggers `Build O3 Standalone`. **Verify the run is green** — its Lucene-bake
gate boots a throwaway copy and refuses to publish unless patient (`Smith`) and concept
(`malaria`) search return hits, so a green run is end-to-end proof the demo dump works.
Once green, the README download serves the new version automatically.

## 🛠️ HOW TO EXTRACT SQL DUMPS FROM A RUNNING SDK INSTANCE
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

## HOW TO RUN FROM ECLIPSE

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

## HOW TO RESPOND TO THE OPENMRS SETUP WIZARD

1. Copy the "connection.url" value from the default-runtime.properties file, located at the project root folder, and paste it into the "Database Connection:" text field of the openmrs setup wizard.

2. For the section: "Do you currently have an OpenMRS database installed that you would like to connect to?", select No. And for the database, enter the default as openmrs. Enter "openmrs" and "test" as the username and password

3. Click "Continue" to go to the next wizard screen.

4. For the section: "Do you need OpenMRS to automatically create the tables for your current database - openmrs?", select Yes.
	
5. For the section: "Do you want to also add demo data to your database - openmrs? (This option only available if creating new tables.)", just choose what you want.

6. For the section: "Do you currently have a database user other than root that has read/write access to the openmrs database?", Choose Yes, and then enter a "openmrs" and "test" as the user name and password. This account will be created by the embedded database engine. The reason to use 'test' is that when the application starts, it checks for the mysql password and if it is test, it is replaced with a randomly generated 12 character password which is written back to the runtime properties file.

7. Click "Continue" to go to the next wizard screen, and feel free to fill what you want on this screen.

8. Click "Continue" to got to the next wizard screen where you will fill the openmrs admin account.

9. Click "Continue" to go to the next wizard screen where you will fill whatever you want.

10. Click "Continue" to go to the next wizard screen and click "Finish".

## DISTRIBUTION FOLDER STRUCTURE (This is a MUST)

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

## DATABASE CONNECTION STRING

	jdbc:mariadb://127.0.0.1:3316/openmrs?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull

The above default database connection string has all in the openmrs mysql default database connection and is used for the MariaDB connection.

						   
NOTE: When creating a new database using the openmrs database setup wizard, remember to replace the default connection string
	  with the one above in the "Database Connection:" text field.
	  
	  The embedded mysql database engine is a fully functional database engine that you can connect too using any database 
	  GUI query tools like Navicat, EMS MySQL Manager, etc


## APPLICATION USER INTERFACE

Tomcat Port					This is the port at which to run tomcat.
MySQL Port					This is the port at which to run mariaDB4j (embedded mariadb database engine).
							You can change this port to any other port you want, but make sure that it is not already in use.
							Also make sure that the port you set here is the same as the one in the runtime properties file.

File -> Quit				This menu item stops tomcat and mariadb and then closes the application.
File -> Launch Browser		This menu item opens the openmrs login page for the current web application context.
File -> Clear Output		This clears the output log in the user interface text area. But does not clear the log file
							written on the file system.

Start						This button runs tomcat, which will automatically start the mariadb database engine if it
							was not already running. For the embedded mariadb, the first connection automatically starts
							the mysql engine.
							
Stop						This button stops tomcat and then also stops the mariadb database engine, without closing the application.


NOTE: Minimizing or Maximizing the application window does not have any effect on the server. The window close icon will stop
	  the server (behaves as File -> Quit) but will first ask if you really want to, and will only do so when you select
	  the Yes option.
	  
	
	
## HOW TO RUN FROM COMMAND LINE

Running from command line requires the -commandline switch.
e.g. java -jar standalone.jar -commandline

-mysqlport: 	Use to override the mysql port in the runtime properties file.
-tomcatport: 	Use to override the tomcat port in the runtime properties file.
start			Use to start the server.
stop			Use to stop the server.
browser			Use to launch a new browser instance.

 
 
# HOW TO GENERATE A DATABASE TO INCLUDE WITH A DISTIBUTION

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


	  
## SOME ROUGH STATISTICS SO FAR

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


## 🛠️ Reusable Embedded MariaDB (ReusableDB.java) for Windows Compatibility
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