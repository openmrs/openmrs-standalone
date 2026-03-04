#!/bin/bash
# Generates the demo data SQL dump locally by:
#   1. Building the OpenMRS distribution (if not already built)
#   2. Starting OpenMRS in Docker with demo data enabled
#   3. Polling the REST API until a valid authenticated session is returned
#   4. Dumping the database to src/main/db/
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

# Fix the auto-generated Dockerfile base image tag if needed
if [ -f "$DISTRO_DIR/web/Dockerfile" ] && grep -q 'nightly-amazoncorretto-11' "$DISTRO_DIR/web/Dockerfile"; then
  sed -i.bak 's|openmrs/openmrs-core:nightly-amazoncorretto-11|openmrs/openmrs-core:2.8.x|g' \
    "$DISTRO_DIR/web/Dockerfile" && rm -f "$DISTRO_DIR/web/Dockerfile.bak"
fi

# Enable demo data via compose override
rm -f "$OVERRIDE_FILE"
cat > "$OVERRIDE_FILE" <<'EOF'
services:
  web:
    environment:
      OMRS_CONFIG_ADD_DEMO_DATA: "true"
EOF
COMPOSE_ARGS+=(-f "$OVERRIDE_FILE")

# ── Step 3: Start OpenMRS ───────────────────────────────────────────────────
echo "🚀 Starting OpenMRS in Docker (demo mode)..."
docker compose "${COMPOSE_ARGS[@]}" up -d --build web

START_TIME=$(date +%s)

# ── Step 4: Poll REST API until OpenMRS is fully initialized ────────────────
echo "⏳ Polling OpenMRS REST API for a valid authenticated session..."
echo "   Endpoint: http://localhost:8080/openmrs/ws/rest/v1/session"
echo "   Poll interval: ${POLL_INTERVAL}s | Overall timeout: ${TIMEOUT}s"

while true; do
  # A successful response with "authenticated":true means OpenMRS is fully up
  # and all modules (including demo data loading) have finished initializing.
  HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
    -u admin:Admin123 \
    http://localhost:8080/openmrs/ws/rest/v1/session 2>/dev/null || echo "000")

  if [ "$HTTP_CODE" = "200" ]; then
    AUTHENTICATED=$(curl -sf -u admin:Admin123 \
      http://localhost:8080/openmrs/ws/rest/v1/session 2>/dev/null \
      | grep -o '"authenticated":[a-z]*' | head -1 || echo "")

    if [ "$AUTHENTICATED" = '"authenticated":true' ]; then
      echo "✅ REST API returned authenticated session — OpenMRS is fully initialized."
      break
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

# ── Step 5: Determine version ──────────────────────────────────────────────
cd "$PROJECT_ROOT"
REFAPP_VERSION=$(mvn help:evaluate -Dexpression=refapp.version -q -DforceStdout -B 2>/dev/null || echo "unknown")
echo "📦 RefApp version: $REFAPP_VERSION"

# ── Step 6: Dump the database ──────────────────────────────────────────────
DB_CONTAINER=$(docker compose "${COMPOSE_ARGS[@]}" ps -q db)
if [ -z "$DB_CONTAINER" ]; then
  echo "❌ Could not find database container."
  docker compose "${COMPOSE_ARGS[@]}" ps
  exit 1
fi

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
