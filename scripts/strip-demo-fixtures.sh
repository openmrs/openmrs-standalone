#!/bin/bash
set -euo pipefail

# Makes the Reference Application demo content package fit for a real implementation, by removing the
# handful of items in it that are filler, developer scaffolding, or wrong outside the O3 demo.
#
# Usage: strip-demo-fixtures.sh <config_dir>      (e.g. target/distro/web/openmrs_config)
#
# WHY IT FILTERS RATHER THAN DROPS THE PACKAGE
#
# `referenceapplication-demo` is not "the data the demo needs" - it is the worked example of a content
# package an implementation should write, and it holds nearly everything that makes O3 usable without
# configuration: the concept dictionary, the formulary, the lab and diagnosis catalogs, the identifier
# scheme, visit types, programs, forms, queues, appointment services and billing. `referenceapplication`
# alone gives you a system you can log into and then do nothing with (measured: 378 concepts, no visit
# type, no identifier source). So the Starter option ships the demo package WHOLE and edits out the
# short list below - everything else is content a clinician needs on day one.
#
# WHAT COMES OUT, AND WHY EACH ONE IS SAFE
#
#   * locations `Site 1`..`Site 50` - filler for exercising the login location picker. All 50 are tagged
#     Login + Visit Location, so they swamp the real ones. They also skew demo data: referencedemodata's
#     fixed-seed randomizer put every generated visit at "Site 42".
#   * the developer forms `Test Form 1` (published, so it shows in the chart), `Form Engine Cookbook` and
#     `Form Engine Cookbook Library`, plus the orphaned `Test Form 1` French translations.
#   * the `addresshierarchy` domain - 344 rows of **Cambodian** provinces, districts and communes. Wrong
#     everywhere else; registration falls back to core's default address template.
#   * payment mode `Paypal`, leaving Cash and Bank transfer.
#   * patient identifier type `SSN` - US Social Security, with a `^[A-Z]{1}-[0-9]{7}$` format regex.
#   * relationship types `Uncle/Nephew`, `Friend/Friend` and `Aunt/Niece`. Only `Aunt/Niece` ships
#     retired upstream (`Void/Retire` = true), so dropping that one changes nothing observable; the
#     other two are active upstream and do disappear from the relationship-type picker, which is the
#     point - they are demo flavour, not metadata an implementation needs. `Clinician/Patient`,
#     `Community Health Worker/Patient` and `Other/Other` stay.
#
# AND WHAT IS EDITED RATHER THAN REMOVED
#
#   * `Ubuntu Hospital` is RENAMED, not deleted: it is the parent of the whole facility hierarchy and the
#     only Visit Location among the demo locations, so deleting it would break its children and the
#     hierarchy. Renaming keeps the uuid and every reference to it intact.
#   * `referencedemodata.createDemoPatientsOnNextStartup` is set to 0 rather than deleted. Deleting it
#     would break scripts/generate-demo-data-locally.sh, which patches that exact property to build the
#     DEMO database. Setting it to 0 removes the hazard: today only the shipped checksums stop
#     Initializer applying `50`, so a starter implementation that edits any config file could find 50
#     demo patients appearing in its production database on the next boot.
#
# DELIBERATELY LEFT ALONE: the clinical forms (`SOAP Note`, `Ward Admission`, `Laboratory Test Results`,
# `Surgical Operation`, `Mental Health Assessment`, `Covid 19`), the programs (HIV Care and Treatment,
# PMTCT, PEP/PrEP are among the most widely run OpenMRS programs), the billable services, appointment
# services and queues. Those are plausible clinical content, and each is what makes its feature usable
# out of the box. Removing them would leave a clinician with empty tabs to configure.
#
# WHY AT THE CONFIG LAYER, not with DELETEs against the finished dump: the standalone ships ONE
# `appdata/configuration` shared by both database options, and Initializer reloads any config file whose
# MD5 stops matching the shipped checksum. `locations-core_demo.csv` is exactly the file a starter
# implementation edits to add its own facility - and that edit would re-create all 50 sites. Filtering
# here keeps the shipped config, its checksums and both DB dumps consistent, and it re-applies itself on
# every refapp version bump.
#
# Runs from pom-step-01.xml in `process-resources`, after openmrs-sdk:build-distro and BEFORE
# scripts/generate-checksums.sh, so the checksums describe the filtered config.
# Idempotent - safe to re-run against an already-filtered distro.

CONFIG_DIR="${1:-}"

# The placeholder a new implementation is expected to rename. Change here if you want a different one.
HOSPITAL_PLACEHOLDER="My Hospital"

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

# Scratch files go to a temp dir, never beside the CSV: an interrupted run must not leave strays in the
# config tree, where generate-checksums.sh (a plain `find -type f`) would checksum them.
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

WARNINGS=0
warn() { echo "   ⚠️  $1"; WARNINGS=$((WARNINGS + 1)); }

# Resolves the name column from each CSV's header rather than assuming a position, so an upstream column
# reorder cannot make this edit the wrong field. Header spellings vary across domains (`Name` in
# locations, `name` in paymentModes) and some files pad fields with spaces, hence the trim and the
# case-insensitive match. Splitting on bare commas is enough: only Uuid and Void/Retire precede the name
# in every file touched here, and neither is ever quoted. A quoted comma later can only make a computed
# name fail to match - i.e. the row is kept, never wrongly dropped.
#
# $1 = domain, $2 = awk regex the name must match to be DROPPED, $3 = human label
drop_rows() {
    local domain="$1" pattern="$2" label="$3" total=0 csv removed
    [ -d "$CONFIG_DIR/$domain" ] || { warn "no '$domain' domain - upstream moved or renamed it?"; return; }
    while IFS= read -r csv; do
        [ -n "$csv" ] || continue
        awk -F',' -v pat="$pattern" -v removed_file="$TMP_DIR/removed" '
            function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
            NR == 1 {
                for (i = 1; i <= NF; i++) if (tolower(trim($i)) == "name") name_col = i
                print; next
            }
            name_col && trim($name_col) ~ pat { removed++; next }
            { print }
            END { print removed + 0 > removed_file }
        ' "$csv" > "$TMP_DIR/filtered"
        removed=$(cat "$TMP_DIR/removed")
        if [ "$removed" -gt 0 ]; then
            cat "$TMP_DIR/filtered" > "$csv"
            echo "   - ${csv#"$CONFIG_DIR"/}: removed $removed $label"
        fi
        total=$((total + removed))
    done <<< "$(find "$CONFIG_DIR/$domain" -type f -name '*.csv')"
    [ "$total" -gt 0 ] || warn "no $label found in $domain - already filtered, or upstream renamed them."
}

# Renames a location everywhere it appears by name: its own Name, its Description when that merely
# repeats the name, and the Parent column of every child row. Renaming only Name would orphan the
# children - the demo hierarchy references its parent by NAME, not by uuid.
# $1 = domain, $2 = exact current name, $3 = replacement
rename_row() {
    local domain="$1" from="$2" to="$3" total=0 csv changed
    [ -d "$CONFIG_DIR/$domain" ] || { warn "no '$domain' domain to rename '$from' in"; return; }
    while IFS= read -r csv; do
        [ -n "$csv" ] || continue
        awk -F',' -v OFS=',' -v from="$from" -v to="$to" -v changed_file="$TMP_DIR/changed" '
            function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
            NR == 1 {
                for (i = 1; i <= NF; i++) {
                    if (tolower(trim($i)) == "name") name_col = i
                    if (tolower(trim($i)) == "description") desc_col = i
                    if (tolower(trim($i)) == "parent") parent_col = i
                }
                print; next
            }
            {
                if (name_col && trim($name_col) == from) { $name_col = to; changed++ }
                if (desc_col && trim($desc_col) == from) { $desc_col = to }
                if (parent_col && trim($parent_col) == from) { $parent_col = to; reparented++ }
                print
            }
            END { print (changed + 0) " " (reparented + 0) > changed_file }
        ' "$csv" > "$TMP_DIR/renamed"
        read -r changed reparented < "$TMP_DIR/changed"
        if [ "$changed" -gt 0 ] || [ "$reparented" -gt 0 ]; then
            cat "$TMP_DIR/renamed" > "$csv"
            echo "   ~ ${csv#"$CONFIG_DIR"/}: renamed '$from' -> '$to' ($changed row(s), $reparented child reference(s))"
        fi
        total=$((total + changed))
    done <<< "$(find "$CONFIG_DIR/$domain" -type f -name '*.csv')"
    [ "$total" -gt 0 ] || warn "'$from' not found in $domain - already renamed, or upstream renamed it first."
}

# ── Locations ───────────────────────────────────────────────────────────────
# Structural: if the domain ever disappears the distro layout has changed and this filter is silently
# doing nothing, so fail rather than ship the fixtures.
if [ ! -d "$CONFIG_DIR/locations" ]; then
    echo "❌ No locations domain under $CONFIG_DIR - has the distro config layout changed?"
    exit 1
fi
if ! find "$CONFIG_DIR/locations" -type f -name '*.csv' | grep -q .; then
    echo "❌ No locations CSV under $CONFIG_DIR/locations - has the distro config layout changed?"
    exit 1
fi

drop_rows locations '^Site [0-9]+$' "'Site N' placeholder location(s)"
rename_row locations 'Ubuntu Hospital' "$HOSPITAL_PLACEHOLDER"

# ── Other single-item removals ──────────────────────────────────────────────
drop_rows paymentmodes '^Paypal$' "payment mode 'Paypal'"
drop_rows patientidentifiertypes '^SSN$' "identifier type 'SSN'"
drop_rows relationshiptypes '^(Uncle\/Nephew|Aunt\/Niece|Friend\/Friend)$' "demo relationship type(s)"

# ── Country-specific address hierarchy ──────────────────────────────────────
if [ -d "$CONFIG_DIR/addresshierarchy" ]; then
    n=$(find "$CONFIG_DIR/addresshierarchy" -type f | grep -c . || true)
    rm -rf "${CONFIG_DIR:?}/addresshierarchy"
    echo "   - removed addresshierarchy/ ($n file(s) - Cambodian provinces and districts)"
else
    warn "no addresshierarchy domain - already removed, or upstream dropped it."
fi

# ── Developer / test forms ──────────────────────────────────────────────────
# Basenames carry a content-package suffix (e.g. `-core_demo`), hence the trailing glob. The cookbook
# glob covers the Cookbook Library form too. `test_form-*` deliberately does NOT match
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
        echo "   - removed ${form#"$CONFIG_DIR"/}"
        REMOVED_FORMS=$((REMOVED_FORMS + 1))
    done <<< "$(find "$CONFIG_DIR/$domain" -type f -name "$glob")"
done
[ "$REMOVED_FORMS" -gt 0 ] || warn "no developer form definitions found - already filtered, or upstream renamed them."

# ── Demo patient generation ─────────────────────────────────────────────────
# Set to 0, not deleted - see the header. Matched across newlines because the property and its value sit
# on separate lines.
DEMO_GP=$(grep -rl 'referencedemodata.createDemoPatientsOnNextStartup' "$CONFIG_DIR/globalproperties" 2>/dev/null | head -1)
if [ -n "$DEMO_GP" ]; then
    before=$(perl -0ne 'print $1 if m{<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>(\d+)</value>}s' "$DEMO_GP")
    perl -0pi -e 's{(<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>)\d+(</value>)}{${1}0${2}}s' "$DEMO_GP"
    after=$(perl -0ne 'print $1 if m{<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>(\d+)</value>}s' "$DEMO_GP")
    if [ "$after" = "0" ]; then
        echo "   ~ ${DEMO_GP#"$CONFIG_DIR"/}: demo patient generation ${before:-?} -> 0"
    else
        echo "❌ Could not set createDemoPatientsOnNextStartup to 0 in $DEMO_GP (value is '${after:-unset}')"
        exit 1
    fi
else
    warn "createDemoPatientsOnNextStartup not found - upstream may have moved it; a starter database could generate demo patients."
fi

echo "✅ Demo fixtures stripped ($WARNINGS warning(s))."
