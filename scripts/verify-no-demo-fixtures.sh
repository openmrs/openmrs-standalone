#!/bin/bash
set -uo pipefail

# Refuses to publish a standalone that carries the Reference Application demo content package's test
# scaffolding, or whose Starter database is less complete than the demo one.
#
# Usage: verify-no-demo-fixtures.sh <extracted_artifact_dir>
#   e.g. verify-no-demo-fixtures.sh target/artifact/referenceapplication-standalone-3.7.1
#
# Both regressions this guards are SILENT, which is why they need a gate rather than a review:
#   * scripts/strip-demo-fixtures.sh only WARNS when a pattern stops matching (an upstream rename
#     must not break the build), so a renamed fixture would sail straight through;
#   * an empty dump taken before the convergence restart in docs/releasing.md §2 step (d) looks
#     perfectly healthy while shipping privilege-level roles ~180 grants short — nobody notices
#     until someone creates a user in a starter implementation.
#
# Checks the ASSEMBLED artifact (shipped Initializer config + both bundled DB zips), not the repo, so
# it also catches an assembly that bundled the wrong file. Run it locally before pushing a dump
# regeneration; build-o3-standalone.yml runs it on every publish.

ART_DIR="${1:-}"

if [ -z "$ART_DIR" ]; then
    echo "Usage: $0 <extracted_artifact_dir>"
    exit 1
fi

fail() { echo "::error::$1"; exit 1; }

[ -d "$ART_DIR" ] || fail "artifact directory not found: $ART_DIR"

# ── Shipped Initializer config ──────────────────────────────────────────────
# Assert the path exists before grepping it: a missing directory would yield zero hits and pass the
# gate while checking nothing at all.
LOC="$ART_DIR/appdata/configuration/locations"
[ -d "$LOC" ] \
    || fail "$LOC not found in the artifact — the shipped config layout changed, so this gate is no longer looking at the locations it thinks it is"

SITES=$(grep -rho "Site [0-9]" "$LOC" 2>/dev/null | wc -l | tr -d ' ')
[ "${SITES:-0}" = "0" ] \
    || fail "shipped Initializer config still defines 'Site N' placeholder locations ($SITES hits) — has strip-demo-fixtures.sh stopped matching upstream's naming?"

# ── Bundled databases ──────────────────────────────────────────────────────
# Start from a clean scratch dir: an earlier failed run leaves extracted SQL behind, and `unzip -o`
# only overwrites same-named files, so a stale dump could be compared instead of the one shipping.
# `unzip -d` also creates only the leaf directory, hence the mkdir.
DB_CHECK=$(mktemp -d)
trap 'rm -rf "$DB_CHECK"' EXIT
mkdir -p "$DB_CHECK/empty" "$DB_CHECK/demo"

unzip -o -q "$ART_DIR/emptydatabase.zip" -d "$DB_CHECK/empty" || fail "could not read bundled emptydatabase.zip"
unzip -o -q "$ART_DIR/demodatabase.zip"  -d "$DB_CHECK/demo"  || fail "could not read bundled demodatabase.zip"

EMPTY_SQL=$(find "$DB_CHECK/empty" -name '*.sql' | head -1)
DEMO_SQL=$(find "$DB_CHECK/demo" -name '*.sql' | head -1)
[ -n "$EMPTY_SQL" ] || fail "bundled emptydatabase.zip contains no .sql"
[ -n "$DEMO_SQL" ]  || fail "bundled demodatabase.zip contains no .sql"

check_no_sites() { # $1 = label, $2 = dump path
    local hits
    hits=$(grep -o "'Site [0-9]" "$2" | wc -l | tr -d ' ')
    [ "${hits:-0}" = "0" ] \
        || fail "bundled $1 database still contains 'Site N' placeholder locations ($hits hits)"
}

# A dump cut short — the classic failure of dumping before initialization finished — still parses as
# SQL and passes every content check below; it simply stops mid-file. mysqldump's trailer is the
# cheap, reliable signal that the file is whole. The size floor is a floor, not a target: 3.7.1 runs
# ~14 MB (starter) / ~17 MB (demo), so 5 MB catches a truncated dump without tripping on a
# legitimately leaner future one. This matters most for the STARTER dump, which — unlike the demo
# dump under the Lucene bake — is never actually booted anywhere in CI, and whose import failure
# OpenmrsUtil.importSqlFile only prints rather than raises.
check_complete() { # $1 = label, $2 = dump path
    local bytes
    tail -5 "$2" | grep -q "^-- Dump completed" \
        || fail "bundled $1 database looks truncated — no mysqldump completion trailer (dumped before initialization finished?)"
    bytes=$(wc -c < "$2" | tr -d ' ')
    [ "${bytes:-0}" -ge 5000000 ] \
        || fail "bundled $1 database is only ${bytes} bytes — far below the ~14 MB a fully initialized O3 dump takes"
}

check_no_sites starter "$EMPTY_SQL"
check_no_sites demo "$DEMO_SQL"
check_complete starter "$EMPTY_SQL"
check_complete demo "$DEMO_SQL"

# Counts the single `role` row plus every Privilege Level: Full grant. The absolute number is not the
# point — the two dumps must AGREE, since both are cut from the same converged database.
full_grants() { grep -o "('Privilege Level: Full'," "$1" | wc -l | tr -d ' '; }
E=$(full_grants "$EMPTY_SQL")
D=$(full_grants "$DEMO_SQL")

echo "Privilege Level: Full rows — starter=$E demo=$D; 'Site N' placeholders: none in config or either database."
[ "${E:-0}" -gt 1 ] || fail "starter database has no Privilege Level: Full grants at all ($E)"
# Deliberately symmetric: either dump can be the under-converged one, depending on which was
# regenerated. Naming the lower side is the diagnosis; assuming it is the starter would send the
# next maintainer to re-dump the wrong database.
[ "$E" = "$D" ] \
    || fail "the two databases disagree on Privilege Level: Full rows (starter=$E, demo=$D) — whichever is lower was dumped before the convergence restart (docs/releasing.md §2 step d)"

echo "✅ No demo fixtures in the shipped config or databases; starter DB metadata matches demo."
