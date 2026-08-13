# Migrating from the O3 Standalone to a Tomcat + external database deployment

This is the **non-Docker** counterpart to
[Migrating to a production Docker deployment](migrating-to-docker-o3.md). It moves a
single-site standalone instance onto a conventional server stack: **Apache Tomcat** running the
OpenMRS platform, with an **external, managed MariaDB/MySQL** database. Choose this over the
Docker distro when your operations team standardises on bare-metal/VM Tomcat and a
separately-administered database rather than containers.

As with the Docker path, there is no separate "enterprise edition" of OpenMRS — you run the
**same** OpenMRS 3 Reference Application, just as a properly managed server. You move off the
standalone for architectural reasons (more concurrent users, high availability, or a database you
patch and scale independently), not because of the operational basics — backups, keeping it
running, hardening — which any production deployment needs equally. The migration is a
**database + files move with strict version alignment**.

> ⚠️ **Version alignment.** An OpenMRS database is tied to specific **Platform (core)**,
> **Reference Application**, and **module** versions through Liquibase changesets. Migrate to the
> **same versions** you run today, verify, and upgrade later as a **separate** step. This runbook
> uses **RefApp 3.7.1 / Core 2.8.8** as the example — substitute your actual versions.

**The shortcut that makes this reliable:** your unzipped standalone already contains the fully
assembled RefApp 3.7.1 — the `openmrs.war`, the complete module set (including the `spa` module),
the built **O3 frontend assets** (`appdata/frontend`, which the spa module serves), and the
Initializer configuration. Reuse those artifacts rather than trying to reassemble the exact module
set by hand. That guarantees the version match.

---

## Contents

- [Prerequisites (on the target server)](#prerequisites-on-the-target-server)
- [Phase 0 — Record what you are running (do not skip)](#phase-0--record-what-you-are-running-do-not-skip)
- [Phase 1 — Back up the standalone and gather its artifacts](#phase-1--back-up-the-standalone-and-gather-its-artifacts)
- [Phase 2 — Provision the external database and import](#phase-2--provision-the-external-database-and-import)
- [Phase 3 — Lay down the OpenMRS application data directory](#phase-3--lay-down-the-openmrs-application-data-directory)
- [Phase 4 — Write the runtime properties (point at the external DB)](#phase-4--write-the-runtime-properties-point-at-the-external-db)
- [Phase 5 — Deploy the war and start Tomcat](#phase-5--deploy-the-war-and-start-tomcat)
- [Phase 6 — Rebuild the search index and verify parity](#phase-6--rebuild-the-search-index-and-verify-parity)
- [Phase 7 — Production hardening](#phase-7--production-hardening)
- [Phase 8 — Cutover](#phase-8--cutover)
- [Rollback](#rollback)
- [Caveats](#caveats)

## Prerequisites (on the target server)

- **Apache Tomcat 9** and a JDK supported by Platform 2.8.8 (the reference distribution images
  use Java 11; Java 17 is also supported — match the platform's documented requirements).
- An **external MariaDB/MySQL** server (MariaDB 10.11 LTS is a safe choice), reachable from the
  Tomcat host.
- Your **unzipped standalone directory** (source of the war/modules/config) and disk space for
  the database, attachments, and backups.

---

## Phase 0 — Record what you are running (do not skip)

On the standalone: **Administration → Manage Modules** (note every module and version) and the
startup log banner (Platform / RefApp version). Confirm **RefApp 3.7.1 / Core 2.8.8**. Anything
beyond stock 3.7.1 is a custom module you must carry over.

## Phase 1 — Back up the standalone and gather its artifacts

With the standalone **running** (embedded MariaDB on `127.0.0.1:3316`; password is in the
`*-runtime.properties` file — the `test` password is rotated to a random 12-character value):

```bash
mkdir -p ~/migration

# 1a. Database dump
mysqldump --single-transaction --routines --triggers \
  -h 127.0.0.1 -P 3316 -u openmrs -p openmrs > ~/migration/openmrs-standalone.sql

# 1b. The assembled RefApp 3.7.1 artifacts from the standalone directory.
#     Copy the WHOLE appdata/ — besides modules/ it contains owa/, configuration/, and (crucially)
#     frontend/ (the built O3 SPA that the spa module serves) plus spa-build-config.json, and
#     complex_obs/ if you have attachments. The embedded database lives under <standalone-dir>/database,
#     NOT under appdata, so copying appdata wholesale is safe.
cp  <standalone-dir>/tomcat/webapps/openmrs.war ~/migration/openmrs.war    # the platform war
cp -a <standalone-dir>/appdata                  ~/migration/appdata        # modules, owa, configuration, frontend, ...
```

Verify the dump is complete: `ls -lh ~/migration/openmrs-standalone.sql` and
`grep -c 'INSERT INTO \`patient\`' ~/migration/openmrs-standalone.sql`.

> Don't have the standalone directory handy? You can instead regenerate the exact 3.7.1 module
> set and config with the OpenMRS SDK
> (`mvn org.openmrs.maven.plugins:openmrs-sdk-maven-plugin:build-distro -Ddistro=org.openmrs:distro-emr-configuration:3.7.1 …`)
> and take the war, modules, configuration, and SPA (`openmrs_core/openmrs.war`, `openmrs_modules`,
> `openmrs_config`, `openmrs_spa`) from the generated distro. Reusing the standalone's own artifacts
> is simpler and guarantees the version match.

## Phase 2 — Provision the external database and import

On the database server:

```sql
CREATE DATABASE openmrs CHARACTER SET utf8 COLLATE utf8_general_ci;
CREATE USER 'openmrs'@'%' IDENTIFIED BY '<a-real-strong-password>';
GRANT ALL PRIVILEGES ON openmrs.* TO 'openmrs'@'%';
FLUSH PRIVILEGES;
```

Import your dump and sanity-check the counts:

```bash
mysql -h <db-host> -u openmrs -p openmrs < ~/migration/openmrs-standalone.sql
mysql -h <db-host> -u openmrs -p -e \
  "SELECT (SELECT COUNT(*) FROM patient) patients, (SELECT COUNT(*) FROM obs) obs, (SELECT COUNT(*) FROM users) users;" openmrs
```

## Phase 3 — Lay down the OpenMRS application data directory

Create a dedicated app-data directory owned by the Tomcat user (example uses `/var/lib/openmrs`):

```bash
sudo mkdir -p /var/lib/openmrs
sudo cp -a ~/migration/appdata/. /var/lib/openmrs/   # modules, owa, configuration, frontend, complex_obs, ...
sudo chown -R tomcat:tomcat /var/lib/openmrs
```

This brings across the modules, the Initializer `configuration/`, the **`frontend/`** SPA assets
(without which `/openmrs/spa` would load nothing), and `configuration_checksums/` (so Initializer
skips re-processing config the imported database already has). Add any **custom modules** from
Phase 0 into `/var/lib/openmrs/modules`.

Belt-and-suspenders: confirm the demo global property is `0` in the config, so no demo data can
generate even if Initializer re-applies it. A standalone from 3.7.1 onward already ships it as `0`
(`scripts/strip-demo-fixtures.sh` sets it), so this is a check rather than an edit — but an
`appdata` copied from an older standalone will still say `50`:

```bash
grep -rl "createDemoPatientsOnNextStartup" /var/lib/openmrs/configuration 2>/dev/null
# in that globalproperties file, confirm <value>0</value>; change it if it still says 50
```

## Phase 4 — Write the runtime properties (point at the external DB)

Create `/var/lib/openmrs/openmrs-runtime.properties` (filename must match the `openmrs` webapp
context) owned by the Tomcat user:

```properties
connection.url=jdbc:mariadb://<db-host>:3306/openmrs?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull
connection.username=openmrs
connection.password=<the-real-strong-password>
connection.driver_class=org.mariadb.jdbc.Driver
module.allow_web_admin=true
auto_update_database=false
application_data_directory=/var/lib/openmrs
```

`auto_update_database=false` ensures the app won't attempt schema changes — correct, because the
imported DB already matches the war's version.

## Phase 5 — Deploy the war and start Tomcat

Point Tomcat at the app-data directory and give the JVM adequate memory, then deploy the war:

```bash
# in setenv.sh (or the systemd unit's Environment):
export CATALINA_OPTS="$CATALINA_OPTS -DOPENMRS_APPLICATION_DATA_DIRECTORY=/var/lib/openmrs -Xms1g -Xmx3g"

sudo cp ~/migration/openmrs.war "$CATALINA_HOME/webapps/openmrs.war"
sudo systemctl restart tomcat        # or $CATALINA_HOME/bin/startup.sh
tail -f "$CATALINA_HOME/logs/catalina.out"   # watch modules start; Liquibase should no-op
```

Because the versions match, startup should connect, run no Liquibase changes, start every
module, and — via the bundled **`spa`** module — serve the O3 frontend at
`http://<host>:8080/openmrs/spa`.

What you should see in `catalina.out`:

- `Using runtime properties file: /var/lib/openmrs/openmrs-runtime.properties` — confirms the
  `-DOPENMRS_APPLICATION_DATA_DIRECTORY` setting worked. OpenMRS also logs a few benign
  `Unable to find a runtime properties file at …` WARNs for the *other* locations it checks first
  (e.g. the process working directory); those are normal as long as the "Using runtime properties
  file" line points at your app-data directory. If instead you land on the web setup wizard, the
  file wasn't found — re-check the path and the system property.
- `liquibase-update-to-latest-2.8.x.xml contains 0 un-run change sets` — confirms the imported
  database already matches the war's schema (a clean, version-aligned boot with nothing to
  migrate). Un-run change sets here would mean a version mismatch — stop and reconcile versions.

## Phase 6 — Rebuild the search index and verify parity

- In the app: **Administration → Manage Search Index** (patient/concept search won't work until
  the Lucene index is rebuilt on the new instance).
- Verify: log in as an existing user; open a known patient (search e.g. "Smith"); confirm a
  concept search ("malaria") returns hits; confirm an attachment renders; compare the
  patient / obs / user counts from Phase 2 against the standalone.

## Phase 7 — Production hardening

- Run Tomcat as a **systemd service** under a non-root `tomcat` user (not from a shell).
- Put it behind a reverse proxy (nginx/Apache/Caddy) terminating **HTTPS**; set a real hostname.
  The O3 frontend then lives at `https://<your-host>/openmrs/spa`.
- **Automate backups**: nightly `mysqldump` of the database, plus a copy of
  `/var/lib/openmrs/complex_obs` (attachments live on disk, not in the database).
- Lock down the database user to the Tomcat host's address rather than `'%'`.

## Phase 8 — Cutover

1. Announce a short freeze; stop writes on the standalone.
2. Take a **final** dump (repeat Phase 1a) and re-import it (repeat Phase 2) so nothing entered
   after your test import is lost.
3. Re-run the Phase 6 verification.
4. Point users at the new URL. Keep the standalone archived (launcher jar + final dump +
   `appdata`) until the new instance is proven in real use.

## Rollback

If anything fails, the standalone is untouched and fully functional — restart it and re-point
users. Nothing in this runbook modifies the source standalone.

---

## Caveats

- **Attachments live on disk**, not in the database — the `complex_obs` copy (Phases 1b/3) is the
  most commonly missed step. If you use the **Attachments** module, confirm its storage-directory
  global property matches `/var/lib/openmrs/complex_obs`.
- **Absolute-path global properties.** A few settings baked into the shipped database hold
  absolute paths under `/openmrs/data` (the app-data directory used when the bundled dumps were
  generated) — for example `openconceptlab.oclLoadAtStartupPath`. On a Tomcat host with a
  different app-data directory these resolve to nothing; that is harmless for the migration (the
  concept dictionary is already in the imported database, so nothing is lost — at most a start-up
  log warning). Repoint them to your `/var/lib/openmrs/...` path under
  **Administration → Manage Global Properties** if you want the start-up paths to resolve. The
  `spa.local.directory` property, by contrast, is `frontend` (relative to the app-data directory),
  so the O3 frontend is found without any change.
- Exact **Tomcat/JDK/MariaDB versions** should follow Platform 2.8.8's documented requirements;
  the versions named here are known-good examples, not the only supported ones.
- This is a single-site runbook. For multi-node/HA you would front several Tomcat nodes with a
  load balancer and use a shared/replicated database and shared attachment storage — out of scope
  here.
