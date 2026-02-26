#!/bin/bash
# Generates an SQL database dump from a Docker-based OpenMRS instance.
#
# Usage: ./generate-db-dumps.sh <distro-dir> <refapp-version> <output-dir> <mode>
#   distro-dir:     Path to the SDK-generated distribution (e.g., target/distro)
#   refapp-version: Reference Application version (e.g., 3.6.0-SNAPSHOT)
#   output-dir:     Where to save the SQL dump (e.g., src/main/db)
#   mode:           "demo" for demo data, "empty" for schema only

set -euo pipefail

DISTRO_DIR="${1:?Usage: $0 <distro-dir> <refapp-version> <output-dir> <mode>}"
REFAPP_VERSION="${2:?Missing refapp version}"
OUTPUT_DIR="${3:?Missing output directory}"
MODE="${4:?Missing mode (demo or empty)}"

TIMEOUT="${TIMEOUT:-600}"
DB_ROOT_PASSWORD="${DB_ROOT_PASSWORD:-openmrs}"
COMPOSE_FILE="$DISTRO_DIR/docker-compose.yml"
OVERRIDE_FILE="$DISTRO_DIR/docker-compose.override.yml"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "❌ Docker Compose file not found: $COMPOSE_FILE"
  exit 1
fi

# Build compose command args
COMPOSE_ARGS=(-f "$COMPOSE_FILE")

cleanup() {
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

# Configure demo data via compose override
rm -f "$OVERRIDE_FILE"
if [ "$MODE" = "demo" ]; then
  cat > "$OVERRIDE_FILE" <<'EOF'
services:
  web:
    environment:
      OMRS_CONFIG_ADD_DEMO_DATA: "true"
EOF
  COMPOSE_ARGS+=(-f "$OVERRIDE_FILE")
  echo "📦 Mode: demo (with demo data)"
else
  echo "📦 Mode: empty (schema only, no demo data)"
fi

echo "🚀 Starting OpenMRS in Docker from $DISTRO_DIR..."
docker compose "${COMPOSE_ARGS[@]}" up -d --build web

echo "⏳ Waiting for OpenMRS to initialize (timeout: ${TIMEOUT}s)..."
START_TIME=$(date +%s)

while true; do
  if curl -sf http://localhost:8080/openmrs > /dev/null 2>&1; then
    echo "✅ OpenMRS is responding."
    echo "⏳ Waiting 60s for background initialization tasks to complete..."
    sleep 60
    break
  fi

  NOW=$(date +%s)
  ELAPSED=$((NOW - START_TIME))
  if [ "$ELAPSED" -gt "$TIMEOUT" ]; then
    echo "❌ Timeout reached after ${TIMEOUT}s waiting for OpenMRS."
    echo "--- Docker logs (web) ---"
    docker compose "${COMPOSE_ARGS[@]}" logs --tail=50 web
    exit 1
  fi

  sleep 10
done

# Find the database container
DB_CONTAINER=$(docker compose "${COMPOSE_ARGS[@]}" ps -q db)
if [ -z "$DB_CONTAINER" ]; then
  echo "❌ Could not find database container (service: db)."
  docker compose "${COMPOSE_ARGS[@]}" ps
  exit 1
fi

# Generate SQL dump
OUTPUT_FILE="$OUTPUT_DIR/${MODE}-db-${REFAPP_VERSION}.sql"
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
