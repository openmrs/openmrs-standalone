#!/bin/bash
# Patches the OpenMRS WAR to fix Infinispan's Subject.getSubject() incompatibility
# with Java 21+. Compiles a patched Security.class and injects it into the WAR's
# WEB-INF/classes/ directory where the Servlet spec guarantees it takes precedence
# over the original class in WEB-INF/lib/infinispan-core-13.0.22.Final.jar.

set -euo pipefail

BASEDIR="$(cd "$(dirname "$0")/.." && pwd)"
WAR_PATH="${BASEDIR}/target/distro/web/openmrs_core/openmrs.war"
PATCH_SRC="${BASEDIR}/src/main/patches/org/infinispan/security/Security.java"
WORK_DIR="${BASEDIR}/target/patch-work"

if [ ! -f "$WAR_PATH" ]; then
    echo "WAR not found at $WAR_PATH — skipping Infinispan patch"
    exit 0
fi

if [ ! -f "$PATCH_SRC" ]; then
    echo "ERROR: Patch source not found at $PATCH_SRC"
    exit 1
fi

echo "Patching Infinispan Security class in OpenMRS WAR for Java 21+ compatibility..."

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/lib" "$WORK_DIR/classes"

# Extract the Infinispan JARs needed for compilation
jar xf "$WAR_PATH" WEB-INF/lib/infinispan-commons-13.0.22.Final.jar \
                    WEB-INF/lib/infinispan-core-13.0.22.Final.jar 2>/dev/null || true
mv WEB-INF/lib/*.jar "$WORK_DIR/lib/" 2>/dev/null || true
rm -rf WEB-INF

# Build classpath from extracted JARs
CP=$(echo "$WORK_DIR/lib/"*.jar | tr ' ' ':')

# Compile the patched Security.java
javac -source 11 -target 11 -cp "$CP" \
    -d "$WORK_DIR/classes" \
    "$PATCH_SRC"

# Inject the compiled class into the WAR under WEB-INF/classes/
cd "$WORK_DIR/classes"
jar uf "$WAR_PATH" org/infinispan/security/Security.class

echo "Infinispan Security class patched successfully."

# Clean up
rm -rf "$WORK_DIR"
