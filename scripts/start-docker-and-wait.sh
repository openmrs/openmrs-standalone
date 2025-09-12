#!/bin/bash
set -e

# Directory where the distro is located; default to target/distro
DISTRO_DIR="${1:-../target/distro}"
SERVER_ID="${2:-openmrs-example}"
# Project directory (one level up from script location)
PROJECT_DIR="$(cd "$(dirname "$0")/../" && pwd)"

# Ensure JAVA_HOME is set
if [ -z "$JAVA_HOME" ]; then
  echo "❌ JAVA_HOME is not set. Please set it to your Java installation."
  exit 1
fi

# Delete old server if it exists
mvn openmrs-sdk:delete -DserverId="$SERVER_ID" -B || true

echo "🚀 Starting OpenMRS using the SDK from $DISTRO_DIR..."


# Run SDK setup (automated, Docker-based MySQL if Docker available)
mvn openmrs-sdk:setup \
  -DserverId="$SERVER_ID" \
  -Ddistro="$DISTRO_DIR/web/openmrs-distro.properties" \
  -DjavaHome="$JAVA_HOME" \
  -DbatchAnswers="8080,1044,MySQL 8.4.1 and above in SDK docker container (requires pre-installed Docker),yes" \
  -B

# Copy pre-created runtime properties
SERVER_DIR="$HOME/openmrs/$SERVER_ID"
if [ -f "$PROJECT_DIR/src/main/config/openmrs-runtime.properties" ]; then
  # Generate runtime properties
cat > "$SERVER_DIR/openmrs-runtime.properties" <<EOF
connection.url=jdbc:mariadb://127.0.0.1:3308/openmrs-example?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8&zeroDateTimeBehavior=convertToNull
connection.username=root
connection.password=Admin123
auto_update_database=false
tomcatport=8080
EOF

echo "✅ Generated openmrs-runtime.properties at $SERVER_DIR"
else
  echo "⚠️ No openmrs-runtime.properties found. SDK will run first-time setup wizard."
fi

# Import pre-populated database dump
DB_DUMP=$(ls "$PROJECT_DIR/src/main/db/demo-db-"*.sql 2>/dev/null | head -n 1)
if [ -f "$DB_DUMP" ]; then
  echo "📦 Importing pre-populated database dump into SDK MySQL container..."
  MYSQL_CONTAINER=$(docker ps --filter "name=openmrs-sdk-mysql" -q | head -n 1)
  if [ -n "$MYSQL_CONTAINER" ]; then
    docker exec -i "$MYSQL_CONTAINER" sh -c "mysql -u root -pAdmin123 openmrs-example" < "$DB_DUMP"
    echo "✅ Database imported successfully."
  else
    echo "❌ MySQL SDK container not running. Database import skipped."
  fi
else
  echo "⚠️ No pre-populated database dump found at $DB_DUMP. SDK will create empty DB."
fi

# Start the server in the background
echo "▶️ Starting OpenMRS server..."
mvn openmrs-sdk:run -DserverId="$SERVER_ID" \
  -DjvmArgs='--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED' -B \
  > "$SERVER_DIR/openmrs.log" 2>&1 &

SERVER_PID=$!
echo "🔹 OpenMRS SDK started in background (PID: $SERVER_PID). Logs: $SERVER_DIR/openmrs.log"

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

echo "✅ OpenMRS is up and running."

# SDK stores servers under $SERVER_DIR. Openmrs-sdk generates config files automatically.
CHECKSUM_DIR="$SERVER_DIR/configuration_checksums"

if [ -d "$CHECKSUM_DIR" ]; then
  echo "📦 Copying configuration checksums to $DISTRO_DIR/web/openmrs_config_checksums..."
  mkdir -p "$DISTRO_DIR/web"
  cp -r "$CHECKSUM_DIR" "$DISTRO_DIR/web/openmrs_config_checksums"
  echo "✅ Checksums copied successfully."
else
  echo "⚠️ Checksums directory not found yet. It may be created later by the SDK."
fi

# Stop any existing server using 8080
echo "🔍 Checking if port 8080 is in use..."
PID=$(lsof -ti:8080 || true)
if [ -n "$PID" ]; then
  echo "⚠️ Port 8080 is in use by PID $PID. Stopping it..."
  kill -9 $PID
  echo "✅ Freed up port 8080."
fi
# echo "🧹 Stopping and deleting OpenMRS server..."
mvn openmrs-sdk:delete -DserverId="$SERVER_ID" -B
rm -rf $SERVER_DIR

echo "✅ Done. Server removed."
