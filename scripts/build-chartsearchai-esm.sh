#!/bin/bash
set -euo pipefail

# Build the chartsearchai ESM from source and integrate it into the frontend directory.
# Usage: build-chartsearchai-esm.sh <frontend-dir>
#   frontend-dir: path to the assembled frontend directory (e.g., target/distro/web/openmrs_spa)

FRONTEND_DIR="$1"

if [ -z "$FRONTEND_DIR" ]; then
  echo "Usage: $0 <frontend-dir>"
  exit 1
fi

REPO_URL="https://github.com/openmrs/openmrs-esm-chartsearchai.git"
WORK_DIR="$(mktemp -d)"
MODULE_NAME="openmrs-esm-chartsearchai-app"

echo "Cloning ${REPO_URL} into ${WORK_DIR}..."
git clone --depth 1 "$REPO_URL" "$WORK_DIR/esm-chartsearchai"

echo "Installing dependencies..."
cd "$WORK_DIR/esm-chartsearchai"
yarn install

echo "Building production bundle..."
yarn build

echo "Copying build output to ${FRONTEND_DIR}/${MODULE_NAME}/..."
mkdir -p "$FRONTEND_DIR/$MODULE_NAME"
cp -r dist/* "$FRONTEND_DIR/$MODULE_NAME/"

# Update importmap.json to register the chartsearchai ESM
IMPORTMAP="$FRONTEND_DIR/importmap.json"
if [ -f "$IMPORTMAP" ]; then
  echo "Updating importmap.json..."
  # Add the chartsearchai entry to the imports object
  TEMP_FILE="$(mktemp)"
  jq '.imports["@openmrs/esm-chartsearchai-app"] = "./openmrs-esm-chartsearchai-app/openmrs-esm-chartsearchai-app.js"' "$IMPORTMAP" > "$TEMP_FILE"
  mv "$TEMP_FILE" "$IMPORTMAP"
  echo "importmap.json updated."
else
  echo "WARNING: importmap.json not found at $IMPORTMAP"
fi

# Update routes.registry.json to register the chartsearchai routes
ROUTES_REGISTRY="$FRONTEND_DIR/routes.registry.json"
ROUTES_JSON="$FRONTEND_DIR/$MODULE_NAME/routes.json"
if [ -f "$ROUTES_REGISTRY" ] && [ -f "$ROUTES_JSON" ]; then
  echo "Updating routes.registry.json..."
  TEMP_FILE="$(mktemp)"
  jq --slurpfile routes "$ROUTES_JSON" '.["@openmrs/esm-chartsearchai-app"] = $routes[0]' "$ROUTES_REGISTRY" > "$TEMP_FILE"
  mv "$TEMP_FILE" "$ROUTES_REGISTRY"
  echo "routes.registry.json updated."
else
  echo "WARNING: routes.registry.json or routes.json not found"
fi

# Clean up
rm -rf "$WORK_DIR"

echo "Done."
