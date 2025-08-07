#!/bin/bash
set -e

# Directory where docker-compose.yml lives; default to target/distro if not passed
DISTRO_DIR="${1:-../target/distro}"

echo "🚀 Starting OpenMRS in Docker from $DISTRO_DIR..."
docker-compose -f "$DISTRO_DIR/docker-compose.yml" up -d web

# Wait for OpenMRS to start (max 180 seconds)
echo "⏳ Waiting for OpenMRS to initialize..."
START_TIME=$(date +%s)
TIMEOUT=180

while true; do
  if command -v curl &> /dev/null; then
    curl -sf http://localhost:8080/openmrs && break
  elif command -v wget &> /dev/null; then
    wget -q --spider http://localhost:8080/openmrs && break
  else
    echo "❌ Neither curl nor wget found! Please install one of them." >&2
    exit 1
  fi

  NOW=$(date +%s)
  ELAPSED=$((NOW - START_TIME))
  if [ "$ELAPSED" -gt "$TIMEOUT" ]; then
    echo "❌ Timeout reached while waiting for OpenMRS to start."
    exit 1
  fi

  sleep 5
done

echo "✅ OpenMRS is up. Proceeding to copy configuration checksums..."

CONTAINER_ID=$(docker-compose -f "$DISTRO_DIR/docker-compose.yml" ps -q web)

# Try to copy the checksum file
echo "📦 Attempting to extract openmrs_config_checksums..."
if docker cp "$CONTAINER_ID":/openmrs/data/configuration_checksums "$DISTRO_DIR/web/openmrs_config_checksums"; then
  echo "✅ Checksums copied successfully."
else
  echo "❌ Failed to copy checksums file. File may not exist yet."
fi

echo "🧹 Shutting down Docker containers..."
docker-compose -f "$DISTRO_DIR/docker-compose.yml" down
