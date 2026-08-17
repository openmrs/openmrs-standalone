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
# scheme, visit types, programs, queues, appointment services and billing. `referenceapplication`
# alone gives you a system you can log into and then do nothing with (measured: 378 concepts, no visit
# type, no identifier source). So the Starter option ships the demo package WHOLE and edits out the list
# below rather than dropping the package.
#
# Forms are deliberately absent from that enumeration, and the Forms section below carries the evidence:
# they are the one part of the package O3 does not need in order to work, because every clinical feature
# that wants structured entry ships its own React workspace instead of a form.
#
# WHAT COMES OUT, AND WHY EACH ONE IS SAFE
#
#   * locations `Site 1`..`Site 50` - filler for exercising the login location picker. All 50 are tagged
#     Login + Visit Location, so they swamp the real ones. They also skew demo data: referencedemodata's
#     fixed-seed randomizer put every generated visit at "Site 42".
#   * locations `Ward 1`..`Ward N`, `Mobile Clinic` and `Community Outreach`, plus the `Community Outreach`
#     cash point that named one of them - see the Locations section, which also records why each of the
#     four survivors in that CSV cannot go. The database ends up with 6 rather than 4, and 11 before:
#     `Main Pharmacy` and `Main Store` are created by the stockmanagement module, not by this config.
#   * every form except `Ward Admission`, and any translation left without its form - see the allowlist
#     further down, which carries the per-form reasoning. `Test Form 1` (published, so it shows in the
#     chart), `Form Engine Cookbook` and its Library were always going; the clinical ones followed once
#     the only argument for keeping them turned out to be "as an example to learn from", which is the
#     same argument the Cookbook was cut for.
#   * the `addresshierarchy` domain - `addressConfiguration.xml` plus 344 rows of **Cambodian** provinces,
#     districts and communes. Removed as a precaution rather than a fix: the domain does not currently
#     reach the database at all. Initializer hands it to the addresshierarchy module's
#     AddressConfigurationLoader, which looks for the fixed name `addressConfiguration.xml` directly under
#     <config>/addresshierarchy/, while build-distro nests every content package's files a level deeper as
#     <domain>/<package>/<file> - so the loader never finds it. Both shipped dumps carried zero
#     address_hierarchy_entry rows and core's default address template before this ever removed anything.
#     It comes out because the day those paths line up, `<wipe>true</wipe>` in that XML replaces the
#     address template with Cambodian province, district and commune fields.
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
# DELIBERATELY LEFT ALONE: the programs (HIV Care and Treatment, PMTCT, PEP/PrEP are among the most
# widely run OpenMRS programs), the billable services, appointment services and queues. Those are
# plausible clinical content, and each is what makes its feature usable out of the box. Removing them
# would leave a clinician with empty tabs to configure.
#
# Forms are the one place that reasoning does NOT reach, which is why they get an allowlist below rather
# than a place on this list. A program with no enrolments is an empty tab a site fills in; a published
# form is a live data-entry path in every patient's chart from first boot, and its questions encode one
# site's protocol. Every O3 clinical feature that needs structured entry ships its own React workspace
# instead of a form, so removing them costs no feature - measured, not assumed, per-form below.
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

# `Ward 1`..`Ward N` are the `Site N` pattern in different words: enumerated placeholders carrying the
# exact tag set `Inpatient Ward` already has (Login + Admission + Transfer), with no children and no beds -
# all six `bed*` tables ship empty, so bed management has nothing mapped to them. One Admission Location is
# all the Ward Admission form's `admitToLocation` picker and esm-ward-app need. The pattern is anchored so
# it cannot touch `Inpatient Ward`. `Mobile Clinic` and `Community Outreach` are Login + Visit Location
# with no children and nothing pointing at them; a site's own facilities go there instead.
#
# WHERE THE TRIM STOPS, and why each survivor is load-bearing rather than merely plausible:
#   * `Outpatient Clinic` - the only Queue Location (/home Service Queues throws with none), the only
#     Appointment Location and Facility Location, and the location both `queue` rows name by uuid.
#   * `My Hospital` - the Visit Location ANCESTOR that lets a visit start at either remaining Login
#     Location, and the parent of the rest of the hierarchy. Dropping the last Visit Location above a
#     Login Location is the silent way to make this build unable to start a visit.
#   * `Inpatient Ward` - the one Admission and Transfer Location left, and the IPD cash point's location.
#   * `Unknown Location` - core's own fallback, not content-package content.
#   * `Main Pharmacy` and `Main Store` are not reachable from here at all: the stockmanagement module
#     creates them at startup, and its operation scopes are keyed on their location TAGS
#     (`stockmgmt_operation_type_location_scope.location_tag`), so removing them would break stock
#     operations rather than tidy anything.
drop_rows locations '^(Ward [0-9]+|Mobile Clinic|Community Outreach)$' "demo location(s)"

rename_row locations 'Ubuntu Hospital' "$HOSPITAL_PLACEHOLDER"

# ── Other single-item removals ──────────────────────────────────────────────
drop_rows paymentmodes '^Paypal$' "payment mode 'Paypal'"
drop_rows patientidentifiertypes '^SSN$' "identifier type 'SSN'"
drop_rows relationshiptypes '^(Uncle\/Nephew|Aunt\/Niece|Friend\/Friend)$' "demo relationship type(s)"

# Goes with the location above, because cashpoints name their location as free TEXT rather than by uuid:
# leaving this row would ship a cash point whose location cannot resolve. That is not hypothetical - it is
# the state `OPD Cash Point` already ships in, since its CSV names `Opd Clinic` and the location is called
# `Outpatient Clinic`, so it lands with location_id NULL. One dangling cash point is an upstream bug to
# report; adding a second on purpose is ours to avoid.
drop_rows cashpoints '^Community Outreach$' "cash point for the removed 'Community Outreach' location"

# ── Country-specific address hierarchy ──────────────────────────────────────
if [ -d "$CONFIG_DIR/addresshierarchy" ]; then
    n=$(find "$CONFIG_DIR/addresshierarchy" -type f | grep -c . || true)
    rm -rf "${CONFIG_DIR:?}/addresshierarchy"
    echo "   - removed addresshierarchy/ ($n file(s) - Cambodian provinces and districts)"
else
    warn "no addresshierarchy domain - already removed, or upstream dropped it."
fi

# ── Forms ───────────────────────────────────────────────────────────────────
# An ALLOWLIST of stems to KEEP, not a list to remove, because "drop" is now the common case: a denylist
# would silently ship whatever forms the next Reference Application release adds, and a form arrives
# published, so it is live in every patient's chart before anyone reviews it.
#
# The rule for earning a place: removing the form has to stop a shipped feature working. Being plausible
# clinical content is not enough, and neither is being a good example of form authoring - `esm-form-
# builder-app` ships in the frontend for that, and the Cookbook was already cut on exactly that ground.
#
# `ipd_admission_request` ("Ward Admission") is the only one that passes. It is not documentation: it
# writes the inpatient disposition construct (CIEL:169405) carrying disposition = ADMIT TO HOSPITAL
# (CIEL:169402 -> 168619, the `ADMIT` entry in dispositions/dispositionConfig.json) plus admitToLocation
# from the `Admission Location` location tag. esm-ward-app reads precisely that, over
# emrapi/inpatient/request filtered on dispositionType ADMIT, and nothing else in the shipped config
# produces one - so dropping it would ship the ward and bed-management apps with a queue nothing can
# fill.
#
# What comes out, and what covers it instead. Each was checked against the shipped frontend's
# routes.json, not assumed:
#   * `Test Form 1`, `Form Engine Cookbook`, `Form Engine Cookbook Library` - developer scaffolding.
#   * `SOAP Note Template` and `Structured SOAP note` - two SOAP forms on different encounter types
#     (Visit Note and Consultation). Clinical notes are esm-patient-notes' job and it needs no form. The
#     Template also stood on four bespoke concepts with no CIEL mapping, and was the only form carrying
#     translations, i.e. it existed to demonstrate the translation feature.
#   * `Laboratory Test Results` - lab result entry is `esm-patient-orders-app#
#     exportedTestResultsFormWorkspace`, declared in esm-laboratory-app's routes.json. Not this form.
#   * `Surgical Operation` - esm-patient-procedures-app ships its own `proceduresFormWorkspace` over the
#     emrapi procedure types. Not this form.
#   * `Covid 19` - epidemic-specific, and the only reason its concept CSV ships.
#   * `Mental Health Assessment Form` - PHQ-2/PHQ-9 is a real instrument, but nothing stops working
#     without it, and a site that screens for depression will author its own anyway.
#
# Basenames carry a content-package suffix (e.g. `-core_demo`), hence both globs per stem.
FORMS_KEEP="ipd_admission_request"

if [ ! -d "$CONFIG_DIR/ampathforms" ]; then
    echo "❌ No ampathforms domain under $CONFIG_DIR - has the distro config layout changed?"
    exit 1
fi

form_is_kept() { # $1 = basename; true if an allowlisted stem claims it
    local base="$1" stem
    for stem in $FORMS_KEEP; do
        case "$base" in "$stem"-*.json | "$stem".json) return 0 ;; esac
    done
    return 1
}

REMOVED_FORMS=0
KEPT_FORMS=0
while IFS= read -r form; do
    [ -n "$form" ] || continue
    if form_is_kept "$(basename "$form")"; then
        KEPT_FORMS=$((KEPT_FORMS + 1))
    else
        rm -f "$form"
        echo "   - removed ${form#"$CONFIG_DIR"/}"
        REMOVED_FORMS=$((REMOVED_FORMS + 1))
    fi
done <<< "$(find "$CONFIG_DIR/ampathforms" -type f -name '*.json')"
[ "$REMOVED_FORMS" -gt 0 ] || warn "no forms to remove - already filtered, or upstream moved the domain."
# A warn, not a failure, for the same reason every other miss here warns: an upstream rename must not
# break the build. The publish gate turns this into a hard failure, where it can say which feature dies.
[ "$KEPT_FORMS" -gt 0 ] \
    || warn "none of the allowlisted forms ($FORMS_KEEP) is present - upstream renamed them, and esm-ward-app now has no way to receive an admission request."

# Translations name their form in a top-level `"form"` field, and there is no reliable mapping from a
# translation's FILENAME to its form's (`soap_note_translations_en-*` belongs to
# `soap_note_template-*`), so match on that field. An orphan is exactly the shape of the `Test Form 1`
# French translations this has always removed: Initializer has nothing to attach it to.
if [ -d "$CONFIG_DIR/ampathformstranslations" ]; then
    while IFS= read -r translation; do
        [ -n "$translation" ] || continue
        form_name=$(perl -0ne 'print $1 if m{"form"\s*:\s*"([^"]*)"}' "$translation")
        if [ -n "$form_name" ] && grep -rqF "\"$form_name\"" "$CONFIG_DIR/ampathforms"; then
            continue
        fi
        rm -f "$translation"
        echo "   - removed ${translation#"$CONFIG_DIR"/} (translates '${form_name:-?}', which no longer ships)"
    done <<< "$(find "$CONFIG_DIR/ampathformstranslations" -type f -name '*.json')"
fi

# ── Demo patient generation ─────────────────────────────────────────────────
# Set to 0, not deleted - see the header. Matched across newlines because the property and its value sit
# on separate lines.
# Every file that carries it, not just the first: today only the demo package sets this property, but
# the header already anticipates upstream moving it, and setting it in the base package too would give
# two occurrences. Patching only the first would leave the other at 50, silently, which is the one
# outcome this whole script exists to prevent.
DEMO_GP_PATCHED=0
while IFS= read -r gp; do
    [ -n "$gp" ] || continue
    before=$(perl -0ne 'print $1 if m{<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>(\d+)</value>}s' "$gp")
    perl -0pi -e 's{(<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>)\d+(</value>)}{${1}0${2}}s' "$gp"
    after=$(perl -0ne 'print $1 if m{<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>(\d+)</value>}s' "$gp")
    if [ "$after" = "0" ]; then
        echo "   ~ ${gp#"$CONFIG_DIR"/}: demo patient generation ${before:-?} -> 0"
        DEMO_GP_PATCHED=$((DEMO_GP_PATCHED + 1))
    else
        echo "❌ Could not set createDemoPatientsOnNextStartup to 0 in $gp (value is '${after:-unset}')"
        exit 1
    fi
done <<< "$(grep -rl 'referencedemodata.createDemoPatientsOnNextStartup' "$CONFIG_DIR/globalproperties" 2>/dev/null || true)"
[ "$DEMO_GP_PATCHED" -gt 0 ] \
    || warn "createDemoPatientsOnNextStartup not found - upstream may have moved it; a starter database could generate demo patients."

echo "✅ Demo fixtures stripped ($WARNINGS warning(s))."
