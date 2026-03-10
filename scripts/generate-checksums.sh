#!/bin/bash
set -e

# Pre-computes configuration checksums for the Initializer module.
# This replicates the exact checksum mechanism used by openmrs-module-initializer
# (ConfigDirUtil.computeChecksum) which is a plain MD5 of the file content.
#
# Usage: generate-checksums.sh <config_dir> <checksums_output_dir>

CONFIG_DIR="$1"
CHECKSUMS_DIR="$2"

if [ -z "$CONFIG_DIR" ] || [ -z "$CHECKSUMS_DIR" ]; then
    echo "Usage: $0 <config_dir> <checksums_output_dir>"
    exit 1
fi

if [ ! -d "$CONFIG_DIR" ]; then
    echo "❌ Config directory does not exist: $CONFIG_DIR"
    exit 1
fi

# Determine md5 command (macOS uses md5, Linux uses md5sum)
if command -v md5sum &> /dev/null; then
    md5cmd() { md5sum "$1" | cut -d' ' -f1; }
elif command -v md5 &> /dev/null; then
    md5cmd() { md5 -q "$1"; }
else
    echo "❌ Neither md5sum nor md5 found!"
    exit 1
fi

echo "📦 Generating configuration checksums..."
echo "   Config dir:    $CONFIG_DIR"
echo "   Checksums dir: $CHECKSUMS_DIR"

FILE_COUNT=0

for domain_dir in "$CONFIG_DIR"/*/; do
    [ -d "$domain_dir" ] || continue
    domain=$(basename "$domain_dir")
    checksums_domain_dir="$CHECKSUMS_DIR/$domain"
    mkdir -p "$checksums_domain_dir"

    find "$domain_dir" -type f | while read -r file; do
        # Compute relative path from domain dir
        rel_path="${file#$domain_dir}"
        # Replace / with _ and remove extension (matches Initializer's getLocatedFilename)
        located_filename=$(echo "$rel_path" | tr '/' '_' | sed 's/\.[^.]*$//')
        # Compute MD5 (matches Initializer's computeChecksum using MessageDigest MD5)
        checksum=$(md5cmd "$file")
        # Write checksum file without trailing newline (matches Initializer's writeChecksum)
        printf '%s' "$checksum" > "$checksums_domain_dir/${located_filename}.checksum"
    done

    count=$(find "$checksums_domain_dir" -name "*.checksum" | wc -l | tr -d ' ')
    FILE_COUNT=$((FILE_COUNT + count))
done

echo "✅ Generated $FILE_COUNT checksum files across $(ls -d "$CHECKSUMS_DIR"/*/ 2>/dev/null | wc -l | tr -d ' ') domains."
