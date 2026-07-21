# Migrating from the O3 Standalone to a production Docker deployment

The **O3 Standalone** is a self-contained package — a bundled Tomcat plus an *embedded*
MariaDB database in a single launcher — intended for evaluation, demos, training, and small
single-machine use. It is **not** meant to be run as a production server.

There is no separate "enterprise edition" of OpenMRS. Moving to production means running the
**same** OpenMRS 3 Reference Application as a proper server deployment — here, the official
**Docker distribution** (`distro-emr-configuration`) with a managed database. Your application
and your data are the same OpenMRS; only the deployment topology and the database engine
change. So this is fundamentally a **database + files migration with strict version
alignment**, not a product switch.

This runbook covers a **single-site** migration to the Docker O3 distro.

> ⚠️ **The one rule that will bite you: version alignment.**
> An OpenMRS database schema is tied to specific **Platform (core)**, **Reference Application**,
> and **module** versions through Liquibase changesets. Migrate to the **same versions** you are
> running now, verify, and only then perform a version upgrade as a **separate** exercise. Never
> import a standalone database into a mismatched or newer server and expect Liquibase to sort it
> out. This runbook uses **RefApp 3.7.1 / Core 2.8.8** as the example — substitute your actual
> versions.

Two things that make a single-site migration simpler than the general case:

- **Stock modules do not need to be migrated.** The 3.7.1 Docker distro ships the exact same
  module set your 3.7.1 standalone has. You carry over only the **database**, the on-disk
  **attachments / complex-obs**, and any **custom modules you added yourself**.
- **The migration is a database restore into a matching-version distro.** Because the versions
  match, Liquibase is a no-op and Initializer merely re-affirms its UUID-keyed configuration
  idempotently — no demo data regenerates (the demo flag is already consumed in your database).

---

## Contents

- [Prerequisites (on the target server)](#prerequisites-on-the-target-server)
- [Phase 0 — Record what you are running (do not skip)](#phase-0--record-what-you-are-running-do-not-skip)
- [Phase 1 — Back up the standalone](#phase-1--back-up-the-standalone)
- [Phase 2 — Generate the version-pinned Docker distro](#phase-2--generate-the-version-pinned-docker-distro)
- [Phase 3 — Prevent demo data on first boot (belt-and-suspenders)](#phase-3--prevent-demo-data-on-first-boot-belt-and-suspenders)
- [Phase 4 — Import the database (database container only)](#phase-4--import-the-database-database-container-only)
- [Phase 5 — Start the backend and restore attachments](#phase-5--start-the-backend-and-restore-attachments)
- [Phase 6 — Rebuild the search index and verify parity](#phase-6--rebuild-the-search-index-and-verify-parity)
- [Phase 7 — Production hardening](#phase-7--production-hardening)
- [Phase 8 — Cutover](#phase-8--cutover)
- [Rollback](#rollback)
- [Caveats](#caveats)

## Prerequisites (on the target server)

- Docker and Docker Compose
- JDK 21, Maven, and the OpenMRS SDK
  (`mvn org.openmrs.maven.plugins:openmrs-sdk-maven-plugin:setup-sdk`)
- Disk space for the database, attachments, and a couple of backups

---

## Phase 0 — Record what you are running (do not skip)

On the standalone, open **Administration → Manage Modules** (note every module and its version)
and the startup log banner (Platform / RefApp version). Confirm it is **RefApp 3.7.1 /
Core 2.8.8**. Flag any module that is **not** part of stock 3.7.1 — those are the custom modules
you must carry over in Phase 5.

## Phase 1 — Back up the standalone

With the standalone **running** (its embedded MariaDB listens on `127.0.0.1:3316`), using the
database password from the `*-runtime.properties` file (the initial `test` password is rotated
to a random 12-character value on first run):

```bash
mkdir -p ~/migration

# 1a. Database dump
mysqldump --single-transaction --routines --triggers \
  -h 127.0.0.1 -P 3316 -u openmrs -p openmrs > ~/migration/openmrs-standalone.sql

# 1b. On-disk data the database does NOT contain: attachments / complex-obs and custom modules.
#     In the standalone these live under the application data directory (the folder that holds
#     'database/', 'modules/', and 'complex_obs/'). Copy the whole thing for safety:
cp -a <standalone-dir>/appdata ~/migration/appdata-backup
```

Verify the dump is complete (not truncated):

```bash
ls -lh ~/migration/openmrs-standalone.sql
grep -c 'INSERT INTO `patient`' ~/migration/openmrs-standalone.sql
```

## Phase 2 — Generate the version-pinned Docker distro

```bash
cd ~/migration
mvn org.openmrs.maven.plugins:openmrs-sdk-maven-plugin:build-distro \
  -Ddistro=org.openmrs:distro-emr-configuration:3.7.1 \
  -Ddir=openmrs-o3 -B          # use a fresh, non-existent dir; needs Docker + JDK 21
cd openmrs-o3
docker compose config --services   # confirm the exact service names
```

> The `build-distro` output for `distro-emr-configuration:3.7.1` is a **two-service** stack:
> **`db`** (MariaDB) and **`web`** (the OpenMRS backend, which also serves the O3 frontend at
> `/openmrs/spa` via the bundled `spa` module) — with named volumes **`db-data`** and
> **`openmrs-data`** (mounted at `/openmrs/data`). Confirm with `docker compose config --services`;
> some SDK versions name the backend `backend` instead of `web`.
>
> (The OpenMRS reference application also publishes a separate **multi-container** `docker-compose`
> — distinct `gateway` / `frontend` / `backend` / `db` — for deployments that scale the frontend
> independently. This runbook targets the reproducible, version-pinned `build-distro` output; the
> migration steps below are identical either way, only the service names differ.)

Review the generated `.env` / `docker-compose.yml` and set **real** database credentials (not the
`openmrs`/`test` defaults) for `OMRS_DB_PASSWORD` / `MYSQL_ROOT_PASSWORD`, and confirm both the
database and the OpenMRS app data use **named volumes** so nothing is ephemeral.

**Patch the generated `web/Dockerfile` (two 3.7.1 workarounds — without these, `docker compose up
-d web` in Phase 5 fails).** The SDK generates it against a base image tag and a startup script
that need two fixes on this release:

```dockerfile
# 1. The generated FROM tag `openmrs/openmrs-core:nightly-amazoncorretto-11` no longer exists on
#    Docker Hub — pin it to the core version (2.8.8 for RefApp 3.7.1):
FROM openmrs/openmrs-core:2.8.8-amazoncorretto-11

# 2. The image's startup.sh runs a DB-auth pre-check via the `mariadb` CLI, but this core image
#    ships only `mysql`; without a symlink the check fails 30× and the container exits before
#    starting. Add right after the FROM line (the image runs as UID 1001, so switch to root):
USER root
RUN ln -sf /usr/bin/mysql /usr/bin/mariadb
USER 1001
```

(Both are upstream distro/image issues on 3.7.1, not migration-specific — they may be fixed in a
later release, in which case skip whichever no longer applies.)

## Phase 3 — Prevent demo data on first boot (belt-and-suspenders)

Your imported database already has the demo flag consumed, but to be safe, set the demo global
property to `0` in the distro configuration before the first boot:

```bash
grep -rl "createDemoPatientsOnNextStartup" web/ config* 2>/dev/null
# in that globalproperties file, change <value>50</value> to <value>0</value>
```

## Phase 4 — Import the database (database container only)

```bash
docker compose up -d db

# root password = MYSQL_ROOT_PASSWORD from your .env (the generated default is 'openmrs')
DBROOT=openmrs

# Wait until MariaDB accepts an AUTHENTICATED query. Do NOT use `mysqladmin ping` here — during
# the container's first-boot init it reports "alive" before the real server (with the root
# password) is ready, so the import below would fail with "Access denied" / socket errors.
until docker compose exec -T db mysql -uroot -p"$DBROOT" -e "SELECT 1" >/dev/null 2>&1; do sleep 3; done

# recreate a clean schema, then import your dump
docker compose exec -T db mysql -uroot -p"$DBROOT" \
  -e "DROP DATABASE IF EXISTS openmrs; CREATE DATABASE openmrs CHARACTER SET utf8 COLLATE utf8_general_ci;"
docker compose exec -T db mysql -uroot -p"$DBROOT" openmrs < ~/migration/openmrs-standalone.sql

# sanity-check the row counts against the standalone
docker compose exec -T db mysql -uroot -p"$DBROOT" -e \
  "SELECT (SELECT COUNT(*) FROM patient) patients, (SELECT COUNT(*) FROM obs) obs, (SELECT COUNT(*) FROM users) users;" openmrs
```

## Phase 5 — Start the backend and restore attachments

```bash
docker compose up -d web        # builds the web image and starts it; Liquibase no-ops (same
                                # version), Initializer re-affirms config, no demo data regenerates
docker compose logs -f web      # watch for clean module startup, no errors/aborts

# restore attachments / complex-obs into the app-data volume (OMRS_DATA_DIR = /openmrs/data)
docker compose cp ~/migration/appdata-backup/complex_obs web:/openmrs/data/
docker compose exec web chown -R 1001:0 /openmrs/data/complex_obs   # match the image's runtime UID
```

Once `web` is up, the backend **and** the O3 frontend are served by that one container — the app
is reachable at `http://<host>:<mapped-port>/openmrs/spa` (there is no separate frontend/gateway
container in this distro).

> If you use the **Attachments** module, confirm its storage-directory global property points at
> `/openmrs/data/complex_obs` (or copy the files to wherever it is configured).
>
> **Custom modules** from Phase 0: drop their `.omod` files into the distro's modules directory
> (or add them to the distro definition) and rebuild/restart the `web` container.

## Phase 6 — Rebuild the search index and verify parity

- In the application: **Administration → Manage Search Index**. Patient and concept search do
  not work until the Lucene index is rebuilt on the new instance.
- Verify: log in as an existing user; open a known patient (search e.g. "Smith"); confirm a
  concept search ("malaria") returns hits; confirm an attachment renders; compare the
  patient / obs / user counts from Phase 4 against the standalone.

## Phase 7 — Production hardening

- Put the **`web`** container behind a reverse proxy with **HTTPS**, and set a real hostname.
- Give the `web` container's JVM adequate memory in the compose environment.
- **Automate backups**: a nightly `docker compose exec -T db mysqldump … openmrs | gzip > …`,
  plus a snapshot of the `openmrs-data` volume (attachments).
- Use the generated **`docker-compose.prod.yml`** for production rather than the dev
  `docker-compose.override.yml`: the override publishes dev ports (`MYSQL_DEV_PORT`,
  `TOMCAT_DEV_PORT`), while `docker-compose.prod.yml` exposes only the app on `${TOMCAT_PORT}`.
  Run with `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d` (Compose loads
  `docker-compose.override.yml` automatically only when you don't pass explicit `-f` files).

## Phase 8 — Cutover

1. Announce a short freeze and stop writes on the standalone.
2. Take a **final** database dump (repeat Phase 1a) and re-import it (repeat Phase 4) so no data
   entered after your test import is lost.
3. Re-run the Phase 6 verification.
4. Point users at the new URL. Keep the standalone archived (launcher jar + final dump +
   `appdata`) until the new instance is proven in real use.

## Rollback

If anything fails, the standalone is untouched and fully functional — restart it and re-point
users. Nothing in this runbook modifies the source standalone.

---

## Caveats

- **Service and volume names** come from *your* generated `docker-compose.yml` (Phase 2's
  `docker compose config --services`). This runbook uses `db` / `web` / `openmrs-data`; some SDK
  versions name the backend `backend`.
- The attachments path assumes the default `OMRS_DATA_DIR=/openmrs/data`; confirm it in the
  generated compose file.
- For higher availability you can point the distro at an **external managed database** instead of
  the bundled `db` container; for a single site the bundled container with a persistent named
  volume plus scheduled backups is sufficient.
