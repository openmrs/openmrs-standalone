# Release runbook — upgrading the standalone to a new Reference Application release

[← Back to the README](../README.md)

This is the end-to-end runbook for *building and publishing* the O3 standalone package for a new
Reference Application release (e.g. `3.7.0` → `3.7.1`). Read it fully before starting — the
DB-dump regeneration has non-obvious timing.

> Running an existing install and just want to move your data onto a new build? See
> [Upgrading a standalone that is already in production](user-guide.md#upgrading-a-standalone-that-is-already-in-production)
> instead — this runbook is for maintainers rebuilding the distributable.

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

**The build makes the demo content package fit for a real implementation** —
`scripts/strip-demo-fixtures.sh`, wired into `pom-step-01.xml` as the `strip-demo-fixtures` exec
execution, which must stay ahead of `generate-checksums` so the shipped checksums describe the
filtered config. Read that script's header for the reasoning; the short version is:

| edit | why it is safe |
|---|---|
| drops the 50 `Site N` locations | filler; all tagged Login + Visit, so they buried the real ones |
| drops `Test Form 1`, `Form Engine Cookbook`, `Cookbook Library`, the orphan FR translation | developer scaffolding |
| drops the `addresshierarchy` domain | `addressConfiguration.xml` + 344 rows of **Cambodian** provinces. A no-op today — Initializer's loader wants that XML directly under `addresshierarchy/` and build-distro nests it under `addresshierarchy/<package>/`, so both dumps already carried core's address template and no `address_hierarchy_entry` rows. Removed so it cannot start applying: `<wipe>true</wipe>` in that XML would swap the address template for province/district/commune fields |
| drops payment mode `Paypal`, identifier type `SSN` | Paypal is odd for a hospital, `SSN` is US-specific with a format regex |
| drops relationship types `Uncle/Nephew`, `Friend/Friend`, `Aunt/Niece` | the last is already retired upstream; `Clinician/Patient` and `CHW/Patient` stay |
| **renames** `Ubuntu Hospital` → `My Hospital` | it is the hierarchy's parent and the only Visit Location, so deleting it would orphan its children. The rename also rewrites the `Parent` column of all 5 children and the description — they reference the parent by NAME, not uuid |
| **sets** `createDemoPatientsOnNextStartup` to 0 | not deleted, because `generate-demo-data-locally.sh` patches that same property. Today only the shipped checksums stop Initializer applying `50`, so a site that edits any config file could find 50 demo patients in production |

**Do not extend this into dropping the whole `referenceapplication-demo` package.** It is not "the
data the demo needs" — it is the worked example of a content package an implementation should write,
and it holds nearly everything that makes O3 usable without configuration. Measured on a distro built
from `referenceapplication` alone: 378 concepts, no visit type, no identifier source, no diagnoses and
no formulary — you can log in and then do nothing. What *is* deliberately kept: the clinical forms, the
programs (HIV Care and Treatment, PMTCT and PEP/PrEP are among the most widely run OpenMRS programs),
the billable services, appointment services and queues. Each is what makes its feature work on day one.

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

# Three boots: init demo-free → patch bounds → restart to converge metadata → EMPTY dump →
# turn demo on and restart → DEMO dump. The split lets you inject the ConceptNumeric fix between
# concept-load and demo generation, which a single boot can't (demo runs in the same startup as
# concept load), and the extra restart is what makes the starter DB complete (see step d).
cd target/distro

# Three local-only fixes to the SDK-generated distro, all needed before the first `docker compose
# up`. None touch source or CI (CI builds with -Pci and never boots Docker):
#   1. The generated Dockerfile pins `openmrs/openmrs-core:nightly-amazoncorretto-11`, a tag that no
#      longer exists on Docker Hub.
#   2. That image ships /usr/bin/mysql but NOT `mariadb`, which the distro's startup.sh calls in its
#      DB-auth pre-check. Without the symlink the web container exits BEFORE creating the schema —
#      symptom: the `openmrs` DB exists with 0 tables and the log says "Database not accepting
#      credentials … after 30 attempts", while `mysql -uopenmrs -popenmrs` works fine from the host.
#      Restarting does not help; only the symlink does. The image runs as UID 1001, hence root/back.
#   3. docker-compose.override.yml publishes the db on ${MYSQL_DEV_PORT} (default 3306). The dumps go
#      through `docker exec`, not the host port, so any free port will do when 3306 is taken.
sed -i '' "s|openmrs-core:nightly-amazoncorretto-11|openmrs-core:$CORE-amazoncorretto-11|" web/Dockerfile
grep -q 'ln -sf /usr/bin/mysql' web/Dockerfile || { awk '{print}
  /^FROM /{print ""; print "USER root"; print "RUN ln -sf /usr/bin/mysql /usr/bin/mariadb"; print "USER 1001"}' \
  web/Dockerfile > web/Dockerfile.tmp && mv web/Dockerfile.tmp web/Dockerfile; }
sed -i '' 's/^MYSQL_DEV_PORT=.*/MYSQL_DEV_PORT=3399/' .env   # only if the host already uses 3306

# b) Boot 1 — init WITHOUT demo (demo is driven by the `referencedemodata` module via the
#    Initializer GP `referencedemodata.createDemoPatientsOnNextStartup`, NOT by
#    OMRS_CONFIG_ADD_DEMO_DATA). Set it to 0 so only concepts load.
sed -i '' 's#<value>50</value>#<value>0</value>#' \
  web/openmrs_config/globalproperties/referenceapplication-demo/globalproperties-core_demo.xml
docker compose up -d --build web
DB=$(docker compose ps -q db)
# wait until concepts stabilize (~4254) — patient/obs stay 0; watch with:
#   docker exec $DB mysql -uroot -popenmrs -N -B -e \
#     "SELECT (SELECT COUNT(*) FROM concept),(SELECT COUNT(*) FROM patient),(SELECT COUNT(*) FROM obs);" openmrs

# c) Clamp every ConceptNumeric whose absolute bounds are wider than its reference-range
# intersection, so the module's clamp can only produce values core will accept. This is
# version-independent — no need to re-derive the offending concept ids by hand (on 3.7.1 it changes
# exactly the two the query above reports: 210 low NULL→0, 4184 hi 999→99).
docker exec $DB mysql -uroot -popenmrs openmrs -e "
UPDATE concept_numeric cn
  JOIN (SELECT concept_id, MAX(low_absolute) AS rr_low, MIN(hi_absolute) AS rr_hi
          FROM concept_reference_range GROUP BY concept_id) rr
    ON rr.concept_id = cn.concept_id
   SET cn.low_absolute = CASE WHEN rr.rr_low IS NOT NULL
                              THEN GREATEST(COALESCE(cn.low_absolute, rr.rr_low), rr.rr_low)
                              ELSE cn.low_absolute END,
       cn.hi_absolute  = CASE WHEN rr.rr_hi IS NOT NULL
                              THEN LEAST(COALESCE(cn.hi_absolute, rr.rr_hi), rr.rr_hi)
                              ELSE cn.hi_absolute END
 WHERE (rr.rr_low IS NOT NULL AND (cn.low_absolute IS NULL OR cn.low_absolute < rr.rr_low))
    OR (rr.rr_hi  IS NOT NULL AND (cn.hi_absolute  IS NULL OR cn.hi_absolute  > rr.rr_hi));"
# Re-run the divergence query above afterwards: it must return no rows.

# d) Boot 2 — restart with demo STILL OFF so startup-time metadata converges, and only then dump.
#    Each module creates its own privileges from its Liquibase changesets as it starts, but the
#    grants come from Initializer's roles_core-demo.csv, which names them as strings. Initializer
#    runs before the modules that start after it (billing/cashier, appointments, reporting), so
#    those privileges do not exist yet when the roles file is processed and the grants are silently
#    dropped. Likewise stockmanagement creates one `stockmgmt_party` per location that exists when
#    it starts. A second startup, with every privilege and location already present, completes both.
#    Measured on 3.7.1: role_privilege 488 → 668 (Privilege Level: Full 215 → 307, High 213 → 301)
#    and stockmgmt_party 3 → 11. Dumping after boot 1 - which is what earlier releases did - shipped
#    a starter DB whose privilege-level roles were missing 180 grants the demo DB had.
docker compose restart web
# poll until role_privilege stops growing (~1 min; concepts are already loaded and are not re-imported)

# EMPTY dump (concepts + fix, converged metadata, no demo):
docker exec $DB mysqldump --single-transaction --routines --triggers -u root -popenmrs openmrs \
  > ../../src/main/db/empty-db-$VER.sql

# e) Boot 3 — turn demo back on and restart so referencedemodata regenerates with the fix in place.
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

| dump  | concept | location | `Site N` | form | program | drug | patient | obs    | pts w/ enc | role_privilege | size    |
|-------|---------|----------|----------|------|---------|------|---------|--------|------------|----------------|---------|
| demo  | ~4254   | 11       | **0**    | 7    | 4       | 323  | 50      | ~5800  | 50         | 668            | ~18 MB  |
| empty | ~4254   | 11       | **0**    | 7    | 4       | 323  | **0**   | 0      | 0          | 668            | ~15 MB  |

Both dumps carry the full dictionary and the same metadata — the *only* intended difference is patient
data. If the empty dump's concept, form, program or drug counts drop, the filter has over-reached and a
clinician will find empty tabs.

**Counts are not enough on their own: diff the previous dumps against the new ones.** A regeneration
can move a value without moving a row, and every check on this page would still pass. It has already
happened once. Against the dumps this replaced, two `concept_numeric` rows changed content with the
row count identical:

| concept | column | old dump | new dump | what the shipped config declares |
|---|---|---|---|---|
| CIEL 5242, Respiratory rate | `hi_absolute` | 99 | 999 | 999, in `concepts/…/findings-core_demo.csv` |
| CIEL 785, Alkaline phosphatase | `low_absolute` | 0 | NULL | unset, in the `BasicLabTests` OCL package |

The new values are the right ones: each matches the concept's own declared source. The old ones were
each the matching *reference range* (`conceptreferencerange/…/vitalsreferenceranges.csv` gives 5242 an
Absolute high of 99 in every band; `alpreferenceranges.csv` gives 785 an Absolute low of 0), so the
earlier dumps were cut from a boot where reference-range bounds were landing on the concept itself.
Initializer 2.12.0's `ConceptReferenceRangeLineProcessor` only ever writes a `ConceptReferenceRange`,
and core reads `concept_numeric` to *derive* a default range rather than writing back to it, so the
current behaviour is the correct one. `hi_absolute`/`low_absolute` are what `ObsValidator` enforces,
so a silent move here changes what a clinician is allowed to record.

Nothing automated catches this, and a hardcoded expectation would just be an upstream number copied
with nothing tying the two together. Compare the tables instead, before committing:

```bash
for t in concept_numeric concept_reference_range; do
  for d in demo empty; do
    git show HEAD:src/main/db/$d-db-$VER.sql | awk "/INSERT INTO \`$t\`/,/;\$/" > /tmp/$d-$t.old
    awk "/INSERT INTO \`$t\`/,/;\$/" src/main/db/$d-db-$VER.sql > /tmp/$d-$t.new
    diff /tmp/$d-$t.old /tmp/$d-$t.new && echo "$d/$t unchanged"
  done
done
```

Anything it prints is a clinical range that moved: account for it (upstream changed, or the load did)
before pushing, rather than discovering it in a hospital.

Also verify the filter's edits reached the DATABASES, not just the config — both dumps are cut from a
boot of that config, so a stale dump is how this regresses:

* **`Ubuntu Hospital` must appear in neither dump, and `My Hospital` in both.**
* **`createDemoPatientsOnNextStartup` must be `0`** in both:
  `SELECT property_value FROM global_property WHERE property LIKE '%createDemoPatients%';`
* **`Site N` must be 0 in both dumps** — a non-zero count means `strip-demo-fixtures.sh` did not run
  (or upstream renamed the fixtures and the filter warned instead of matching):
  `SELECT COUNT(*) FROM location WHERE name REGEXP '^Site [0-9]+$';`
* **`role_privilege` must MATCH between the two dumps.** Whichever side is lower was dumped before its
  convergence restart, so its `Privilege Level: Full`/`High` roles are missing grants — invisible
  until someone creates a user and finds they cannot reach reporting, billing or appointments. Both
  `scripts/generate-{empty-db,demo-data}-locally.sh` now restart before dumping for this reason.

All four of those are also enforced automatically, by `scripts/verify-no-demo-fixtures.sh` — run from
**both** publish paths (`build-o3-standalone.yml` on a branch push, `release.yml` on a tag) against
the *assembled artifact*: shipped config plus both bundled DB zips. It also rejects a **truncated**
dump (missing mysqldump's completion trailer, or implausibly small), which is the one failure the
row-count checks above cannot see. Run it yourself on an extracted artifact before pushing a
regeneration — it is much cheaper than a red release run:

```bash
unzip -q target/referenceapplication-standalone-$VER.zip -d /tmp/check
scripts/verify-no-demo-fixtures.sh "/tmp/check/referenceapplication-standalone-$VER"
```

Faster still, and it does not need a packaged zip: `BundledDbDumpImportTest` imports the starter dump
into a real embedded MariaDB through the standalone's own import path and asserts the same contract
(no patients, no `Site N`, dictionary present, a Login Location, converged grants) in ~25 s —

```bash
mvn -o -N compiler:compile compiler:testCompile surefire:test -Dtest=BundledDbDumpImportTest
```

That test exists because the starter dump is otherwise never executed anywhere: CI boots only the
*demo* dump (for the Lucene bake), and `OpenmrsUtil.importSqlFile` prints a failed import rather than
throwing, so a malformed starter dump would first surface as a user picking "Starter Implementation".

(3.7.1 actuals: demo = 4254 concept / 11 location / 7 form / 50 patient / 5821 obs / 278 visit /
1415 enc / 668 role_privilege / 11 stockmgmt_party; empty is identical minus the patient data.
Every demo visit lands on a single Visit Location — Community Outreach on 3.7.1 — because
`referencedemodata` draws it once from a fixed seed; that is upstream behaviour, not a bad dump.)

### 3. Bump the version strings

* `pom.xml` — the `refapp.version` and `openmrs.version` **defaults**. These are what a plain
  `mvn install` uses (CI's publish workflow passes both explicitly, but `standalone-ci.yml` and a
  local build do not), and `refapp.version` has to match the dumps in `src/main/db`: the
  `zip-{demo,empty}-database` assemblies include `{demo,empty}-db-${refapp.version}.sql`, so a stale
  default fails the build late, at packaging, with the unhelpful `archive cannot be empty`.
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
