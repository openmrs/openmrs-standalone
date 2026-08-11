#!/bin/bash
set -euo pipefail

# Strips the Reference Application demo content package's test scaffolding out of a built
# distro's Initializer configuration.
#
# Usage: strip-demo-fixtures.sh <config_dir>      (e.g. target/distro/web/openmrs_config)
#
# WHY THIS EXISTS
#
# The refapp distro composes two content packages: `referenceapplication` (a thin overlay -
# a handful of global properties, concept classes/sources and the location TAGS) and
# `referenceapplication-demo` (everything an implementation actually needs: all locations,
# patient identifier types + the idgen source, visit/encounter types, roles, order
# frequencies, programs, queues, forms, and the ~22 OCL packages that build the concept
# dictionary). So the standalone cannot simply drop the demo package - without it you cannot
# pick a session location, cannot register a patient, and have almost no concepts.
#
# What it CAN drop is the demo package's obvious test scaffolding, which otherwise shows up
# in the "Starter Implementation" database the standalone offers for new setups:
#
#   * locations `Site 1` ... `Site 50` - filler for exercising the login location picker.
#     All 50 are tagged both Login Location and Visit Location, so they swamp the login
#     screen's 7 real locations. They also skew demo data: referencedemodata's fixed-seed
#     randomizer put every generated visit at "Site 42".
#   * the developer forms - `Test Form 1` (published, so it appears in the chart's Forms
#     tab), `Form Engine Cookbook` and `Form Engine Cookbook Library`, plus the orphaned
#     `Test Form 1` French translations.
#
# WHY AT THE CONFIG LAYER, not with DELETEs against the finished dump: the standalone ships
# ONE `appdata/configuration` shared by both database options, and Initializer reloads any
# config file whose MD5 stops matching the shipped checksum. `locations-core_demo.csv` is
# the only locations CSV in the distro - exactly the file a starter implementation edits to
# add its own facility - and that edit would re-create all 50 sites. Filtering here keeps the
# shipped config, its checksums and both DB dumps consistent, and it re-applies itself on
# every refapp version bump.
#
# Runs from pom-step-01.xml in `process-resources`, after openmrs-sdk:build-distro and
# BEFORE scripts/generate-checksums.sh, so the checksums describe the filtered config.
# Idempotent - safe to re-run against an already-filtered distro.

CONFIG_DIR="${1:-}"

if [ -z "$CONFIG_DIR" ]; then
    echo "Usage: $0 <config_dir>"
    exit 1
fi

if [ ! -d "$CONFIG_DIR" ]; then
    echo "❌ Config directory does not exist: $CONFIG_DIR"
    exit 1
fi

CONFIG_DIR=$(cd "$CONFIG_DIR" && pwd)

echo "🧽 Stripping demo fixtures from $CONFIG_DIR"

# ── Placeholder locations ───────────────────────────────────────────────────
# The locations domain is structural: if it ever disappears the distro layout has changed
# and this filter is silently doing nothing, so fail rather than ship the fixtures.
if [ ! -d "$CONFIG_DIR/locations" ]; then
    echo "❌ No locations domain under $CONFIG_DIR - has the distro config layout changed?"
    exit 1
fi

LOCATION_CSVS=$(find "$CONFIG_DIR/locations" -type f -name '*.csv')
if [ -z "$LOCATION_CSVS" ]; then
    echo "❌ No locations CSV under $CONFIG_DIR/locations - has the distro config layout changed?"
    exit 1
fi

# Scratch files go to a temp dir, never beside the CSV: an interrupted run must not leave strays
# in the config tree, where generate-checksums.sh (a plain `find -type f`) would checksum them.
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

REMOVED_LOCATIONS=0
while IFS= read -r csv; do
    [ -n "$csv" ] || continue
    # Resolve the Name column from the header rather than assuming a position, then drop rows
    # whose Name is exactly "Site <n>". Splitting on bare commas is enough here: only Uuid and
    # Void/Retire precede Name, and neither is ever quoted. A quoted comma anywhere else can
    # only make a row's computed Name not match - i.e. the row is kept, never wrongly deleted.
    awk -F',' -v removed_file="$TMP_DIR/removed" '
        NR == 1 {
            for (i = 1; i <= NF; i++) if ($i == "Name") name_col = i
            print
            next
        }
        name_col && $name_col ~ /^Site [0-9]+$/ { removed++; next }
        { print }
        END { print removed + 0 > removed_file }
    ' "$csv" > "$TMP_DIR/filtered"

    removed=$(cat "$TMP_DIR/removed")

    if [ "$removed" -gt 0 ]; then
        cat "$TMP_DIR/filtered" > "$csv"
        echo "   - ${csv#$CONFIG_DIR/}: removed $removed 'Site N' location(s)"
    fi
    REMOVED_LOCATIONS=$((REMOVED_LOCATIONS + removed))
done <<< "$LOCATION_CSVS"

if [ "$REMOVED_LOCATIONS" -eq 0 ]; then
    echo "   ⚠️  No 'Site N' locations found - already filtered, or upstream renamed/dropped them."
fi

# ── Developer / test forms ──────────────────────────────────────────────────
# Basenames carry a content-package suffix (e.g. `-core_demo`), hence the trailing glob. The
# cookbook glob covers the Cookbook Library form too. `test_form-*` deliberately does NOT match
# `test_results_entry_form_v2-*` ("Laboratory Test Results"), which is a real form and stays.
REMOVED_FORMS=0
for pattern in \
    'ampathforms:test_form-*.json' \
    'ampathforms:form-engine-cookbook-*.json' \
    'ampathformstranslations:test_form_1_translations_*.json'
do
    domain="${pattern%%:*}"
    glob="${pattern#*:}"
    [ -d "$CONFIG_DIR/$domain" ] || continue
    while IFS= read -r form; do
        [ -n "$form" ] || continue
        rm -f "$form"
        echo "   - removed ${form#$CONFIG_DIR/}"
        REMOVED_FORMS=$((REMOVED_FORMS + 1))
    done <<< "$(find "$CONFIG_DIR/$domain" -type f -name "$glob")"
done

if [ "$REMOVED_FORMS" -eq 0 ]; then
    echo "   ⚠️  No developer form definitions found - already filtered, or upstream renamed them."
fi

echo "✅ Stripped $REMOVED_LOCATIONS placeholder location(s) and $REMOVED_FORMS developer form file(s)."
