#!/bin/bash
# Generates the demo data SQL dump locally, mirroring docs/releasing.md §2:
#   1. Building the OpenMRS distribution (if not already built) and stripping the demo content
#      package's test fixtures from its Initializer config
#   2. Booting OpenMRS in Docker with demo generation OFF, so concepts load first
#   3. Clamping ConceptNumeric bounds into their reference-range intersection — without this,
#      demo generation aborts part-way and yields a partial dataset
#   4. Turning demo generation on, restarting, and waiting for patients/observations to settle
#   5. Restarting once more so startup-time metadata (role_privilege, stock parties) converges
#   6. Dumping the database to src/main/db/
#
# Usage:
#   ./scripts/generate-demo-data-locally.sh [--skip-build] [--timeout 1800] [--poll-interval 30]
#
# Options:
#   --skip-build      Skip the Maven distribution build (reuse existing target/distro)
#   --timeout N       Max seconds to wait for OpenMRS to fully load (default: 1800)
#   --poll-interval N Seconds between REST API session polls (default: 30)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DISTRO_DIR="$PROJECT_ROOT/target/distro"
OUTPUT_DIR="$PROJECT_ROOT/src/main/db"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-openmrs}"

# Defaults
SKIP_BUILD=false
TIMEOUT=1800
POLL_INTERVAL=30

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)     SKIP_BUILD=true; shift ;;
    --timeout)        TIMEOUT="$2"; shift 2 ;;
    --poll-interval)  POLL_INTERVAL="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,/^$/p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ── Step 1: Build the distribution ──────────────────────────────────────────
if [ "$SKIP_BUILD" = false ]; then
  echo "🔨 Building OpenMRS distribution..."
  cd "$PROJECT_ROOT"
  # Remove previous distro so the SDK build-distro goal doesn't prompt for confirmation
  rm -rf "$DISTRO_DIR"
  mvn org.openmrs.maven.plugins:openmrs-sdk-maven-plugin:setup-sdk -B
  mvn -f pom-step-01.xml process-resources -Pci -B
  echo "✅ Distribution built at $DISTRO_DIR"
else
  if [ ! -f "$DISTRO_DIR/docker-compose.yml" ]; then
    echo "❌ No distribution found at $DISTRO_DIR. Run without --skip-build first."
    exit 1
  fi
  echo "⏭️  Skipping build (reusing $DISTRO_DIR)"
fi

# Drop the demo content package's test scaffolding, then refresh the checksums so they still
# describe the config. Both are idempotent: the Maven build already did this, so this only bites
# for --skip-build against a distro built before the filter existed.
"$SCRIPT_DIR/strip-demo-fixtures.sh" "$DISTRO_DIR/web/openmrs_config"
# Drop the checksums directory rather than regenerating over it: generate-checksums.sh only ever
# writes, so a checksum left behind for a file the filter removed would silently suppress that file
# if it ever returned with the same content (Initializer skips a file whose checksum still matches).
rm -rf "$DISTRO_DIR/web/openmrs_config_checksums"
"$SCRIPT_DIR/generate-checksums.sh" "$DISTRO_DIR/web/openmrs_config" \
  "$DISTRO_DIR/web/openmrs_config_checksums"

# ── Step 2: Prepare Docker Compose ──────────────────────────────────────────
COMPOSE_FILE="$DISTRO_DIR/docker-compose.yml"
OVERRIDE_FILE="$DISTRO_DIR/docker-compose.override.yml"

COMPOSE_ARGS=(-f "$COMPOSE_FILE")

cleanup() {
  echo ""
  echo "🧹 Cleaning up Docker containers..."
  docker compose "${COMPOSE_ARGS[@]}" down -v 2>/dev/null || true
  rm -f "$OVERRIDE_FILE"
}
trap cleanup EXIT

# Two local-only fixes to the SDK-generated Dockerfile, neither touching source or CI:
#   1. It is generated as `FROM openmrs/openmrs-core:nightly-amazoncorretto-11`, a tag that no longer
#      exists on Docker Hub — pin it to the core version this distro was actually built against.
#   2. That image ships /usr/bin/mysql but NOT `mariadb`, which the distro's startup.sh calls in its
#      DB-auth pre-check; without the symlink the web container exits BEFORE creating the schema.
# Kept identical to the copy in generate-empty-db-locally.sh — these scripts are deliberate mirrors.
DOCKERFILE="$DISTRO_DIR/web/Dockerfile"
# `|| true` so a missing/unreadable file reaches the check below instead of `set -e` killing the
# script with a bare exit code and no explanation.
CORE_VERSION=$(grep -E '^war\.openmrs=' "$DISTRO_DIR/web/openmrs-distro.properties" 2>/dev/null | cut -d= -f2 | tr -d ' \r' || true)
if [ -z "$CORE_VERSION" ]; then
  echo "❌ Could not read war.openmrs from $DISTRO_DIR/web/openmrs-distro.properties"
  exit 1
fi
sed -i.bak "s|openmrs/openmrs-core:nightly-amazoncorretto-11|openmrs/openmrs-core:${CORE_VERSION}-amazoncorretto-11|g" \
  "$DOCKERFILE" && rm -f "$DOCKERFILE.bak"
grep -q 'ln -sf /usr/bin/mysql' "$DOCKERFILE" || { awk '{print}
  /^FROM /{print ""; print "USER root"; print "RUN ln -sf /usr/bin/mysql /usr/bin/mariadb"; print "USER 1001"}' \
  "$DOCKERFILE" > "$DOCKERFILE.tmp" && mv "$DOCKERFILE.tmp" "$DOCKERFILE"; }
echo "🔧 Dockerfile pinned to openmrs-core:${CORE_VERSION}-amazoncorretto-11 with the mariadb symlink."

# Demo data is driven by the `referencedemodata` module via the Initializer global property
# `referencedemodata.createDemoPatientsOnNextStartup` (shipped as 50), NOT by this environment
# variable — it is kept only because the SDK images have historically read it. Boot 1 therefore
# turns the GP OFF in the config, so concepts load with no demo data and step 5 gets a window to
# clamp the ConceptNumeric bounds before generation runs. Without that window, generation aborts
# part-way on 3.7.1 and there is no way to recover it in-flight. Mirrors docs/releasing.md §2.
rm -f "$OVERRIDE_FILE"
cat > "$OVERRIDE_FILE" <<'EOF'
services:
  web:
    environment:
      OMRS_CONFIG_ADD_DEMO_DATA: "true"
EOF
COMPOSE_ARGS+=(-f "$OVERRIDE_FILE")

DEMO_GP_FILE="$DISTRO_DIR/web/openmrs_config/globalproperties/referenceapplication-demo/globalproperties-core_demo.xml"
if [ ! -f "$DEMO_GP_FILE" ]; then
  echo "❌ Demo global-property config not found at $DEMO_GP_FILE — has the distro config layout changed?"
  exit 1
fi
# Match the property by name so an unrelated <value>50</value> elsewhere in the file cannot be hit.
perl -0pi -e 's{(<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>)\d+(</value>)}{${1}0${2}}s' \
  "$DEMO_GP_FILE"
echo "🔧 Demo generation disabled for boot 1 (so the ConceptNumeric clamp can be applied first)."

# ── Step 3: Start OpenMRS ───────────────────────────────────────────────────
# Tear down volumes BEFORE booting, not only in the exit trap. `openmrs-data:/openmrs/data/` is a named
# volume and Initializer reads `/openmrs/data/configuration`, which the distro's startup populates by
# copying over whatever is already there rather than replacing the tree. So a volume surviving an earlier
# run — a crash, a Ctrl-C, or any `docker compose` invocation that stopped without `-v` — keeps the config
# files that this build DELETED, and Initializer loads them again.
#
# That is not hypothetical: it silently put all 7 forms into a dump cut from a config shipping 1. The
# asymmetry is what makes it so quiet — a MODIFIED file (the locations CSV) is overwritten and looks
# correct, while a DELETED one (the six form JSONs) is simply left behind, so the same dump can show a
# trimmed location list and an untrimmed form list and every other check still passes.
# Kept identical to the copy in generate-empty-db-locally.sh — these scripts are deliberate mirrors.
echo "🧹 Removing any volumes left by an earlier run (stale appdata config would be re-applied)..."
docker compose "${COMPOSE_ARGS[@]}" down -v 2>/dev/null || true

echo "🚀 Starting OpenMRS in Docker (boot 1 — concepts only, demo generation off)..."
docker compose "${COMPOSE_ARGS[@]}" up -d --build web

# ── Step 4: Poll REST API until OpenMRS is fully initialized ────────────────
# NB: an authenticated session means the webapp is serving — it does NOT mean demo data has
# finished generating. referencedemodata keeps creating patients and observations for several
# minutes after REST starts answering, so this is only the first of three waits below.
wait_for_openmrs() {
  local START_TIME HTTP_CODE AUTHENTICATED NOW ELAPSED
  START_TIME=$(date +%s)
  echo "⏳ Polling OpenMRS REST API for a valid authenticated session..."
  echo "   Endpoint: http://localhost:8080/openmrs/ws/rest/v1/session"
  echo "   Poll interval: ${POLL_INTERVAL}s | Overall timeout: ${TIMEOUT}s"

  while true; do
    HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
      -u admin:Admin123 \
      http://localhost:8080/openmrs/ws/rest/v1/session 2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then
      AUTHENTICATED=$(curl -sf -u admin:Admin123 \
        http://localhost:8080/openmrs/ws/rest/v1/session 2>/dev/null \
        | grep -o '"authenticated":[a-z]*' | head -1 || echo "")

      if [ "$AUTHENTICATED" = '"authenticated":true' ]; then
        echo "✅ REST API returned authenticated session — OpenMRS is serving."
        return 0
      fi
      echo "   [$(date +%H:%M:%S)] HTTP 200 but not yet authenticated (startup in progress)..."
    else
      echo "   [$(date +%H:%M:%S)] HTTP $HTTP_CODE — waiting..."
    fi

    NOW=$(date +%s)
    ELAPSED=$((NOW - START_TIME))
    if [ "$ELAPSED" -gt "$TIMEOUT" ]; then
      echo "❌ Timeout after ${TIMEOUT}s waiting for OpenMRS REST API."
      docker compose "${COMPOSE_ARGS[@]}" logs --tail=50 web
      exit 1
    fi

    sleep "$POLL_INTERVAL"
  done
}

# Polls a scalar SQL expression until it stops changing across 3 consecutive reads. Bounded, so an
# unreachable database fails the dump instead of spinning here forever.
# $1 = label, $2 = SQL expression, $3 = minimum value the result must reach
wait_until_stable() {
  local label="$1" sql="$2" minimum="$3"
  local start prev value stable
  start=$(date +%s); prev=""; stable=0
  echo "⏳ Waiting for $label to settle..."
  while [ "$stable" -lt 3 ]; do
    value=$(docker exec "$DB_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" -N -B \
      -e "SELECT $sql;" openmrs 2>/dev/null | tr -d ' ')
    if [ -n "$value" ] && [ "$value" = "$prev" ] && [ "${value:-0}" -ge "$minimum" ]; then
      stable=$((stable + 1))
    else
      stable=0
    fi
    echo "   [$(date +%H:%M:%S)] $label=${value:-?} (stable=$stable)"
    prev="$value"
    if [ "$stable" -lt 3 ]; then
      if [ "$(( $(date +%s) - start ))" -gt "$TIMEOUT" ]; then
        echo "❌ $label never settled within ${TIMEOUT}s (last=${value:-<no reply from db>})."
        docker compose "${COMPOSE_ARGS[@]}" logs --tail=50 web
        exit 1
      fi
      sleep 15
    fi
  done
}

wait_for_openmrs

DB_CONTAINER=$(docker compose "${COMPOSE_ARGS[@]}" ps -q db)
if [ -z "$DB_CONTAINER" ]; then
  echo "❌ Could not find database container."
  docker compose "${COMPOSE_ARGS[@]}" ps
  exit 1
fi

# ── Step 5: Clamp ConceptNumeric bounds, then turn demo generation on ───────
# Load-bearing here, unlike in generate-empty-db-locally.sh: without it referencedemodata aborts
# part-way through generating the 50 patients. See the shared script's header for the mechanism.
#
# Waiting on concepts is not by itself a guarantee that the reference ranges are in: initializer loads
# the conceptreferencerange domain after the concepts it refers to. Whether the REST-session wait above
# has already put us past that is not something this repo settles, so the shared script asserts the
# outcome rather than trusting the ordering - it fails if concept_reference_range is empty instead of
# reporting a vacuous success.
wait_until_stable "concepts" "(SELECT COUNT(*) FROM concept)" 1000

"$SCRIPT_DIR/clamp-concept-numeric.sh" "$DB_CONTAINER" "$DB_ROOT_PASSWORD" \
  || { echo "❌ ConceptNumeric clamp failed; demo generation would abort part-way."; exit 1; }

# Now switch demo generation on and restart so referencedemodata runs with the clamp in place.
# `docker compose up -d web` would NOT recreate an already-running container — it must be `restart`.
docker exec "$DB_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" openmrs -e \
  "UPDATE global_property SET property_value='50'
    WHERE property='referencedemodata.createDemoPatientsOnNextStartup';" \
  || { echo "❌ Could not re-enable demo generation."; exit 1; }
echo "🔁 Restarting web to generate demo data..."
docker compose "${COMPOSE_ARGS[@]}" restart web
wait_for_openmrs

# ── Step 6: Wait for demo generation, then converge, before dumping ─────────
# Dumping as soon as REST answers yields a PARTIAL demo dataset — the classic symptom is a dump
# with fewer patients than expected, or patients with no encounters.
wait_until_stable "demo patients" "(SELECT COUNT(*) FROM patient)" 1
wait_until_stable "demo observations" "(SELECT COUNT(*) FROM obs)" 1

# referencedemodata aborts its whole remaining run on a single obs validation failure, leaving a
# healthy-looking container and a half-built dataset. Fail loudly instead of shipping that.
# Grepping the log file inside the container rather than `docker compose logs` — same evidence, but
# the latter re-reads the whole stream and takes minutes on a loaded machine.
WEB_CONTAINER=$(docker compose "${COMPOSE_ARGS[@]}" ps -q web)
if [ -n "$WEB_CONTAINER" ] && docker exec "$WEB_CONTAINER" \
     grep -q "Exception caught while creating demo data" /openmrs/data/openmrs.log 2>/dev/null; then
  echo "❌ Demo generation aborted part-way (see docs/releasing.md §2 — ConceptNumeric vs"
  echo "   ConceptReferenceRange bounds). Refusing to write a partial demo dump."
  exit 1
fi

ORPHANS=$(docker exec "$DB_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" -N -B \
  -e "SELECT (SELECT COUNT(*) FROM patient) - (SELECT COUNT(DISTINCT patient_id) FROM encounter);" \
  openmrs 2>/dev/null | tr -d ' ')
if [ "${ORPHANS:-1}" != "0" ]; then
  echo "❌ ${ORPHANS} demo patient(s) have no encounters — generation did not complete."
  exit 1
fi

# Restart with demo already generated (the GP is consumed, so no second batch) so that startup-time
# metadata converges: module privileges are created by each module's Liquibase changesets, but the
# grants come from Initializer's roles CSV, which runs before the modules that follow it. Without
# this the demo dump ships ~180 fewer role_privilege rows. See docs/releasing.md §2 step (d).
echo "🔁 Restarting web so startup-time metadata converges..."
docker compose "${COMPOSE_ARGS[@]}" restart web
wait_for_openmrs
wait_until_stable "role_privilege" "(SELECT COUNT(*) FROM role_privilege)" 1

# ── Step 7: Determine version ──────────────────────────────────────────────
cd "$PROJECT_ROOT"
REFAPP_VERSION=$(mvn help:evaluate -Dexpression=refapp.version -q -DforceStdout -B 2>/dev/null || echo "unknown")
echo "📦 RefApp version: $REFAPP_VERSION"

# ── Step 8: Dump the database ──────────────────────────────────────────────
# DB_CONTAINER was resolved and checked in step 4.
OUTPUT_FILE="$OUTPUT_DIR/demo-db-${REFAPP_VERSION}.sql"
echo "📤 Dumping database to: $OUTPUT_FILE"
mkdir -p "$OUTPUT_DIR"

docker exec "$DB_CONTAINER" mysqldump \
  --single-transaction \
  --routines \
  --triggers \
  -u root -p"$DB_ROOT_PASSWORD" \
  openmrs \
  > "$OUTPUT_FILE"

FILE_SIZE=$(wc -c < "$OUTPUT_FILE" | tr -d ' ')
echo "✅ SQL dump generated: $OUTPUT_FILE ($FILE_SIZE bytes)"

if [ "$FILE_SIZE" -lt 1000 ]; then
  echo "⚠️  Warning: SQL dump is suspiciously small. Check for errors."
  head -20 "$OUTPUT_FILE"
  exit 1
fi

echo ""
echo "🎉 Done! Demo data SQL dump is at:"
echo "   $OUTPUT_FILE"
