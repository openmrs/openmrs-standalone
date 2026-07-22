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
