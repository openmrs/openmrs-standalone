# OpenMRS 3 (O3) Standalone — User Guide

[← Back to the README](../README.md)

How to run and operate a downloaded O3 Standalone build. If you want to build the standalone from
source or cut a release, see the [Developer guide](developer.md) and the
[Release runbook](releasing.md).

## Contents

- [Requirements](#requirements)
- [Note for macOS users running a downloaded zip](#note-for-macos-users-running-a-downloaded-zip)
- [Application user interface](#application-user-interface)
- [How to run from command line](#how-to-run-from-command-line)
- [How to respond to the OpenMRS setup wizard](#how-to-respond-to-the-openmrs-setup-wizard)
- [Database connection string](#database-connection-string)
- [Running in production](#running-in-production)
- [Upgrading a standalone that is already in production](#upgrading-a-standalone-that-is-already-in-production)

## Requirements

To run the standalone, use **Java 17 or newer** — the LTS runtime the OpenMRS 3 stack supports, and
what these builds are validated against (CI uses Java 21). It will **not** start on Java 8 or 11:
the launcher's default JVM arguments use options those reject (`--add-opens` needs Java 9+;
`-Djava.security.manager=allow` needs Java 12+). Nothing else needs installing — the database
(MariaDB) and web server (Tomcat) are embedded in the download.

Building the standalone from source is a separate, heavier toolchain (JDK 21 + Maven, and Docker for
regenerating the bundled database dumps) — see [Quick summary for building the standalone](developer.md#quick-summary-for-building-the-standalone).

## Note for macOS users running a downloaded zip

macOS tags every file extracted from a downloaded zip with the com.apple.quarantine
attribute, which makes dyld refuse to load the bundled MariaDB dylibs (libpcre2, openssl)
and the embedded database fails to initialize. The launcher strips the attribute
automatically at startup; if you are running an older build, do it manually:

    xattr -dr com.apple.quarantine <extracted-standalone-directory>

## Application user interface

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

## How to run from command line

Running from command line requires the -commandline switch.
e.g. java -jar standalone.jar -commandline

-mysqlport: 	Use to override the mysql port in the runtime properties file.
-tomcatport: 	Use to override the tomcat port in the runtime properties file.
start			Use to start the server.
stop			Use to stop the server.
browser			Use to launch a new browser instance.

## How to respond to the OpenMRS setup wizard

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

## Database connection string

	jdbc:mariadb://127.0.0.1:3316/openmrs?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull

The above default database connection string has all in the openmrs mysql default database connection and is used for the MariaDB connection.

						   
NOTE: When creating a new database using the openmrs database setup wizard, remember to replace the default connection string
	  with the one above in the "Database Connection:" text field.
	  
	  The embedded mysql database engine is a fully functional database engine that you can connect too using any database 
	  GUI query tools like Navicat, EMS MySQL Manager, etc

## Running in production

Running OpenMRS in production needs the same operational basics regardless of *how* you deploy it —
the standalone, the Docker distribution, or a hand-rolled Tomcat + database server. None of these
provide them out of the box; they are the operator's responsibility in every case:

- **Automated backups with a tested restore** — schedule a database dump (and copy the attachments /
  app-data directory), keep copies off the machine, and actually practise restoring them.
- **Keep it running** — arrange for it to come back automatically after a reboot or crash, and
  monitor that it is up. (A server is normally installed as a service; the standalone's
  double-click launcher does not do this on its own — run it headless via `-commandline` under a
  service manager if you need unattended restarts.)
- **Network hardening** — if it is reachable beyond `localhost`, put it behind HTTPS and restrict
  access, and keep the database off the public network.
- **Machine & data security** — disk encryption, OS updates, and physical/access control for the
  box that holds patient data.

These are requirements for *any* production EMR, not a shortcoming of the standalone. What the
standalone's architecture genuinely doesn't give you — and the reason to move to a server — is more
concurrent users, high availability, and independent database patching/scaling. See the migration
runbooks linked from the [README](../README.md) when you outgrow a single node.

## Upgrading a standalone that is already in production

When a new O3 Reference Application release ships, you upgrade a *running* standalone by
carrying your existing data into a **fresh copy of the new build** — you do **not** upgrade
in place, and you never edit the old install's files directly. The new build brings a new core
war, new modules, a new frontend and new configuration; your job is to bring across only the
things that hold your data. On first start, OpenMRS core runs its Liquibase migrations and
upgrades the database schema for you.

> This section is for **operators** with a live install and real patient data. If you are the
> person *building/publishing* the standalone package from a new refapp version, you want the
> [Release runbook](releasing.md) instead.

**⚠️ Rehearse the upgrade on a copy first.** Schema migrations are one-way. Do a full backup
(below), run the whole procedure against that backup on a spare machine, confirm it comes up
clean, and only then repeat it on production. Never let the first run of a new version touch the
only copy of your data.

### What holds your data (and what doesn't)

Everything in the standalone folder is relative to `openmrs-standalone.jar`. Only three things
carry *your* state — everything else is shipped fresh by the new build:

| Keep (this is your data)                     | What it is                                                        |
|----------------------------------------------|-------------------------------------------------------------------|
| `database/` (in particular `database/data`)  | The embedded MariaDB data files — the whole patient database.     |
| `<context>-runtime.properties`               | Connection URL **and the generated DB password** (see caveat).    |
| `appdata/complex_obs/` and `appdata/person_images/` | Uploaded attachments/complex-obs files and patient photos — patient data that lives on disk, not in the DB. |

| Let the new build win (do **not** copy the old one) | Why                                              |
|-----------------------------------------------------|--------------------------------------------------|
| `tomcat/webapps/openmrs.war`                        | New core platform.                               |
| `appdata/modules/`                                  | New module versions ship here, not inside the war.|
| `appdata/frontend/`                                 | New O3 SPA build.                                |
| `appdata/configuration/`, `appdata/configuration_checksums/` | New distro configuration + Initializer checksums. |
| `appdata/lucene/`                                   | Search index — the new build ships one and rebuilds it against your data on first start (step 5). |

**⚠️ The DB password lives in the runtime properties, not the data files.** On first setup the
standalone replaces the placeholder password `test` with a random 12-character password and
writes it back to `<context>-runtime.properties`. That password is what unlocks *your*
`database/data`. So if you carry `database/data` across, you **must** carry the matching
`<context>-runtime.properties` too — otherwise the new install cannot authenticate to your
copied database. Keep the war name (`openmrs.war` → context `openmrs`) unchanged so the new build
looks for the same `openmrs-runtime.properties`.

**If you added your own modules** (ones not part of the refapp distro), copy just those `.omod`
files from the old `appdata/modules/` into the new one, and confirm each has a version compatible
with the new release. Don't bulk-copy the folder — that would shadow the new distro modules with
old ones.

### Procedure

The shell below is for macOS/Linux; on Windows do the same folder copies/deletes in Explorer or
PowerShell and use `database\bin\mariadb-dump.exe`. The `java -jar` steps are identical everywhere.
`<old>`/`<new>` are the two version strings; adjust `3316` if you changed the MySQL port.

```bash
old=/path/to/openmrs-standalone-<old>

# 0. While the OLD install is STILL RUNNING, take a portable logical dump. Use the client the
#    standalone bundles (database/bin/mariadb-dump) — you do NOT need MySQL/MariaDB installed
#    separately, and a foreign `mysqldump` can emit an incompatible dump. Root is passwordless:
"$old"/database/bin/mariadb-dump --single-transaction --routines --triggers \
  -h 127.0.0.1 -P 3316 -u root openmrs > openmrs-backup-<date>.sql

# 1. Stop the standalone cleanly so the data files are consistent (File -> Quit, or
#    `java -jar openmrs-standalone.jar -commandline stop`). Confirm nothing still holds the DB
#    (e.g. `pkill -f standalone`).

# 2. Full cold backup of the now-stopped OLD install; keep a copy off the machine:
cp -a "$old" "$old".backup

# 3. Unzip the NEW standalone somewhere separate (NOT over the old one) and cd into the
#    extracted referenceapplication-standalone-<new>/ folder:
unzip openmrs-standalone-o3.zip
cd referenceapplication-standalone-<new>

# 4. Carry your data into the new install:
rm -rf database                             # ensure the new install has no DB of its own yet
                                            # (none exists on a first unzip; guards a stray start)
cp -a "$old"/database ./                     # your patient DB (raw data files)
cp    "$old"/openmrs-runtime.properties ./   # the MATCHING DB password (see caveat above)
rm -f needsconfig.txt                        # ⚠️ CRITICAL: the fresh unzip ships this marker, which
                                            # forces the setup wizard on first start — and the wizard
                                            # DELETES the database you just copied in. Removing it lets
                                            # the new build boot straight against your data instead.
# on-disk patient files, if you use them (created lazily on first upload; the paths follow the
# obs.complex_obs_dir GP resolved against application_data_directory=appdata):
cp -a "$old"/appdata/complex_obs   ./appdata/ 2>/dev/null || true   # attachments / complex obs
cp -a "$old"/appdata/person_images ./appdata/ 2>/dev/null || true   # patient photos
# …and any of YOUR OWN added .omod files (not the distro's) from appdata/modules/.

# 5. Leave appdata/lucene alone — do NOT delete it and do NOT copy your old one over.
#    The fresh unzip ships a search index pre-built against the bundled DEMO database. Standalone
#    3.7.1 and later notice that this boot imported no database and ask the server to rebuild that
#    index against YOUR data on first start, logging "Updating the search index" while it runs.
#    Confirm it actually happened — see "After the upgrade" below — because the request signs in
#    with the admin password the bundled databases ship, and yours may no longer be that.
#
#    Deleting it is worse, not safer: OpenMRS only recreates an empty skeleton on startup and does
#    not repopulate it, so a deleted index leaves patient and concept search returning nothing until
#    you rebuild by hand. Both behaviours were measured on a built standalone.

# 6. Start the new standalone. First start runs Liquibase migrations — give it time.
java -jar openmrs-standalone.jar
```

The migration is automatic — the standalone ships `auto_update_database=true`, so first start runs
the Liquibase changesets and takes you to the login page without an "update database" prompt. Watch
the log during that first start (the UI text area, or `tomcat/logs/<date>.log`) and confirm the
changesets complete without error before anyone logs in.

**If you see the setup wizard instead of a login page, stop immediately** — it means
`needsconfig.txt` was not removed in step 4, and finishing the wizard would delete your data.
Quit, remove the file, and start again.

**Fallback — if the raw data files won't start on the new build.** This only happens if the embedded
MariaDB version changed between the two builds (rare — the standalone can't run its bundled MariaDB
independently, so there is no manual "import into a stopped instance" path). Instead of copying
`database/` and `openmrs-runtime.properties` in step 4, feed your logical dump through the
standalone's *own* first-start importer, and let the new install generate its own DB user/password:

1. In a fresh copy of the new standalone, replace the SQL inside the bundled `demodatabase.zip` with
   your dump, keeping the internal `data/` folder — the zip must contain `data/openmrs-backup-<date>.sql`
   (the standalone imports the first `.sql` it finds under `db/data/` after unzipping that archive).
2. With no `database/` folder present yet (fresh unzip), start the standalone and choose the **demo
   database** option in the setup wizard. First start imports your SQL, then OpenMRS migrates it. Do
   **not** copy the old `openmrs-runtime.properties` on this path.
3. **Rebuild the search index by hand** afterwards, from *Home → System Administration → Manage
   Search Index*. Choosing the **demo database** option is what tells the standalone the shipped
   index still matches, and it cannot tell that you swapped your own dump into that zip: it logs
   `✅ Using the pre-built Lucene search index; skipping startup rebuild` and your patients are then
   searched through an index built from the demo data.

### After the upgrade

* **Read the first start's log before trusting search.** If you left `appdata/lucene` in place
  (step 5), that start should have logged `A pre-built search index is present but this boot imported
  no database` followed by `✅ Search index rebuild triggered successfully on startup`. Only that
  pair means the index describes *your* data. Rebuild through *Home → System Administration → Manage
  Search Index* if you see anything else, and note that a search returning results is **not** the
  confirmation — the index shipped with the download is full of demo patients, so it answers
  perfectly well while describing somebody else's database. Two ways it goes wrong:
  * `❌ Failed to trigger rebuild. Status: 401` — the rebuild request signs in as `admin` with the
    password the bundled databases ship, so a changed admin password stops it. The standalone keeps
    the pre-built marker in this case, so restarting retries; rebuilding by hand also settles it.
  * `✅ Using the pre-built Lucene search index; skipping startup rebuild` — you took the fallback
    path above, which is why its step 3 says to rebuild by hand.
* **Verify** — log in, run a patient search and a concept search, and click through the workflows
  you rely on. A green login plus working search is the quickest proof the migration took.
* **Skipping releases** — core migrations are cumulative, so jumping several refapp releases at
  once usually works, but modules are less forgiving. Prefer upgrading one release at a time, and
  always rehearse on the backup first.

**Rollback:** if the migrated instance misbehaves, stop it and restore the
`<old>.backup` copy from step 2 (or feed `openmrs-backup-<date>.sql` through a clean install of the
OLD version using the same importer trick as the fallback). This is why steps 0 and 2 are not optional.
