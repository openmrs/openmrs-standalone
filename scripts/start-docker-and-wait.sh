#!/bin/bash
set -e

# Directory where docker-compose.yml lives; default to target/distro if not passed
DISTRO_DIR="${1:-../target/distro}"

echo "🚀 Starting OpenMRS in Docker from $DISTRO_DIR..."
# Fix the auto-generated Dockerfile base image tag (nightly-amazoncorretto-11 was removed from Docker Hub)
sed -i.bak 's|openmrs/openmrs-core:nightly-amazoncorretto-11|openmrs/openmrs-core:2.8.x|g' "$DISTRO_DIR/web/Dockerfile" && rm -f "$DISTRO_DIR/web/Dockerfile.bak"
docker-compose -f "$DISTRO_DIR/docker-compose.yml" up -d --build web

# Wait for OpenMRS to start (max 180 seconds)
echo "⏳ Waiting for OpenMRS to initialize..."
START_TIME=$(date +%s)
TIMEOUT=600

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

# Copy checksums from the container. Use trailing /. on source to copy CONTENTS
# into the destination directory (avoids nesting configuration_checksums/ inside
# openmrs_config_checksums/ when the destination already exists).
echo "📦 Attempting to extract openmrs_config_checksums..."
mkdir -p "$DISTRO_DIR/web/openmrs_config_checksums"
if docker cp "$CONTAINER_ID":/openmrs/data/configuration_checksums/. "$DISTRO_DIR/web/openmrs_config_checksums/"; then
  echo "✅ Checksums copied successfully."
else
  echo "⚠️  Failed to copy checksums from Docker. Pre-computed checksums will be used."
fi

echo "🧹 Shutting down Docker containers..."
docker-compose -f "$DISTRO_DIR/docker-compose.yml" down
