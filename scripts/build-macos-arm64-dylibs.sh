#!/bin/bash
#
# Builds the bundled macOS arm64 dylibs that ship with the standalone.
#
# The MariaDB binaries inside ch.vorburger.mariaDB4j:mariaDB4j-db-macos-arm64
# are linked against Homebrew-prefixed dylib paths (/opt/homebrew/opt/...),
# so they fail with "Library not loaded" on any macOS machine that does not
# have Homebrew + pcre2 + openssl@3 installed. To make the standalone
# self-contained we bundle the three required dylibs and rewrite the load
# commands at runtime via install_name_tool.
#
# This script copies the three dylibs from a local Homebrew install and
# rewrites their internal install names so they can be relocated under
# any directory at runtime via @rpath / @loader_path:
#   - LC_ID_DYLIB         -> @rpath/<basename>
#   - libssl -> libcrypto -> @loader_path/libcrypto.3.dylib
#
# The output is committed to src/main/native/macos-arm64/lib/. Re-run this
# script when you need to refresh the bundled dylibs (e.g., security updates
# from upstream pcre2 / openssl@3).

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" || "$(uname -m)" != "arm64" ]]; then
    echo "ERROR: this script must be run on macOS arm64." >&2
    exit 1
fi

PCRE2_SRC="/opt/homebrew/opt/pcre2/lib/libpcre2-8.0.dylib"
SSL_SRC="/opt/homebrew/opt/openssl@3/lib/libssl.3.dylib"
CRYPTO_SRC="/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib"

for f in "$PCRE2_SRC" "$SSL_SRC" "$CRYPTO_SRC"; do
    if [[ ! -f "$f" ]]; then
        echo "ERROR: missing $f. Install with:" >&2
        echo "    brew install pcre2 openssl@3" >&2
        exit 1
    fi
done

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$REPO_ROOT/src/main/native/macos-arm64/lib"
mkdir -p "$OUT_DIR"

cp -f "$PCRE2_SRC"  "$OUT_DIR/libpcre2-8.0.dylib"
cp -f "$SSL_SRC"    "$OUT_DIR/libssl.3.dylib"
cp -f "$CRYPTO_SRC" "$OUT_DIR/libcrypto.3.dylib"
chmod u+w "$OUT_DIR"/*.dylib

# Rewrite install names so the dylibs are relocatable.
install_name_tool -id "@rpath/libpcre2-8.0.dylib" "$OUT_DIR/libpcre2-8.0.dylib"
install_name_tool -id "@rpath/libssl.3.dylib"     "$OUT_DIR/libssl.3.dylib"
install_name_tool -id "@rpath/libcrypto.3.dylib"  "$OUT_DIR/libcrypto.3.dylib"

# libssl references libcrypto via an absolute Homebrew Cellar path; rewrite
# to a sibling lookup so it resolves from whatever directory the dylibs
# are placed in at runtime.
CRYPTO_REF=$(otool -L "$OUT_DIR/libssl.3.dylib" \
    | awk '/libcrypto\.3\.dylib/ && $1 ~ /^\// {print $1; exit}')
if [[ -n "${CRYPTO_REF:-}" ]]; then
    install_name_tool -change "$CRYPTO_REF" \
        "@loader_path/libcrypto.3.dylib" \
        "$OUT_DIR/libssl.3.dylib"
fi

# Re-sign (ad-hoc) since rewriting load commands invalidates the existing
# code signature on Apple Silicon.
codesign --force --sign - "$OUT_DIR/libpcre2-8.0.dylib"
codesign --force --sign - "$OUT_DIR/libssl.3.dylib"
codesign --force --sign - "$OUT_DIR/libcrypto.3.dylib"

echo
echo "Wrote dylibs to $OUT_DIR:"
ls -lh "$OUT_DIR"
echo
echo "Verifying load commands:"
for f in "$OUT_DIR"/*.dylib; do
    echo "--- $(basename "$f") ---"
    otool -L "$f"
done
