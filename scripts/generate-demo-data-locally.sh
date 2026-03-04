#!/bin/bash
# Generates the demo data SQL dump locally by:
#   1. Building the OpenMRS distribution (if not already built)
#   2. Starting OpenMRS in Docker with demo data enabled
#   3. Monitoring Docker logs — waiting for teleconsultation module messages to stop
#   4. Dumping the database to src/main/db/
#
# Usage:
#   ./scripts/generate-demo-data-locally.sh [--skip-build] [--timeout 1800] [--quiet-period 120]
#
# Options:
#   --skip-build      Skip the Maven distribution build (reuse existing target/distro)
#   --timeout N       Max seconds to wait for OpenMRS to fully load (default: 1800)
#   --quiet-period N  Seconds of no teleconsultation log activity before considering
#                     initialization complete (default: 120)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DISTRO_DIR="$PROJECT_ROOT/target/distro"
OUTPUT_DIR="$PROJECT_ROOT/src/main/db"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-openmrs}"

# Defaults
SKIP_BUILD=false
TIMEOUT=1800
QUIET_PERIOD=120

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)   SKIP_BUILD=true; shift ;;
    --timeout)      TIMEOUT="$2"; shift 2 ;;
    --quiet-period) QUIET_PERIOD="$2"; shift 2 ;;
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

# ── Step 4: Wait for HTTP readiness ─────────────────────────────────────────
echo "⏳ Waiting for OpenMRS HTTP endpoint..."
START_TIME=$(date +%s)

while true; do
  if curl -sf http://localhost:8080/openmrs > /dev/null 2>&1; then
    echo "✅ OpenMRS HTTP endpoint is responding."
    break
  fi

  NOW=$(date +%s)
  ELAPSED=$((NOW - START_TIME))
  if [ "$ELAPSED" -gt "$TIMEOUT" ]; then
    echo "❌ Timeout after ${TIMEOUT}s waiting for OpenMRS HTTP endpoint."
    docker compose "${COMPOSE_ARGS[@]}" logs --tail=50 web
    exit 1
  fi

  sleep 10
done

# ── Step 5: Monitor logs for teleconsultation module activity ───────────────
echo "⏳ Monitoring Docker logs for teleconsultation module activity..."
echo "   Will consider initialization complete after ${QUIET_PERIOD}s of no teleconsultation log messages."
echo "   Overall timeout: ${TIMEOUT}s"

LAST_TELECON_TIME=$(date +%s)
SEEN_TELECON=false

# Follow Docker logs in background, filtering for teleconsultation
LOG_FIFO=$(mktemp -u)
mkfifo "$LOG_FIFO"

docker compose "${COMPOSE_ARGS[@]}" logs -f --no-log-prefix web > "$LOG_FIFO" 2>&1 &
LOG_PID=$!

# Read log lines with a timeout, watching for teleconsultation references
while true; do
  # Read with a 10-second timeout
  if read -r -t 10 LINE < "$LOG_FIFO" 2>/dev/null; then
    if echo "$LINE" | grep -qi "teleconsultation"; then
      SEEN_TELECON=true
      LAST_TELECON_TIME=$(date +%s)
      echo "   📡 [$(date +%H:%M:%S)] Teleconsultation activity detected"
    fi
  fi

  NOW=$(date +%s)
  TOTAL_ELAPSED=$((NOW - START_TIME))

  # Check overall timeout
  if [ "$TOTAL_ELAPSED" -gt "$TIMEOUT" ]; then
    echo "❌ Overall timeout reached (${TIMEOUT}s). Proceeding with dump anyway."
    break
  fi

  # If we've seen teleconsultation messages and they've been quiet long enough, we're done
  if [ "$SEEN_TELECON" = true ]; then
    QUIET_ELAPSED=$((NOW - LAST_TELECON_TIME))
    if [ "$QUIET_ELAPSED" -ge "$QUIET_PERIOD" ]; then
      echo "✅ No teleconsultation log activity for ${QUIET_PERIOD}s — initialization appears complete."
      break
    fi
  fi
done

# Clean up log follower
kill "$LOG_PID" 2>/dev/null || true
rm -f "$LOG_FIFO"

# ── Step 6: Determine version ──────────────────────────────────────────────
cd "$PROJECT_ROOT"
REFAPP_VERSION=$(mvn help:evaluate -Dexpression=refapp.version -q -DforceStdout -B 2>/dev/null || echo "unknown")
echo "📦 RefApp version: $REFAPP_VERSION"

# ── Step 7: Dump the database ──────────────────────────────────────────────
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
