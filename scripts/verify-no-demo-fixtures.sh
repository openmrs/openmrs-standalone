#!/bin/bash
set -uo pipefail

# Refuses to publish a standalone that still carries the demo content the build is supposed to have
# filtered out, that cannot be logged into, or whose Starter database is less complete than the demo one.
#
# Usage: verify-no-demo-fixtures.sh <extracted_artifact_dir>
#   e.g. verify-no-demo-fixtures.sh target/artifact/referenceapplication-standalone-3.7.1
#
# Every regression this guards is SILENT, which is why it needs a gate rather than a review:
#   * scripts/strip-demo-fixtures.sh only WARNS when a pattern stops matching (an upstream rename must
#     not break the build), so a renamed or moved fixture would sail straight through. This gate checks
#     each of its edits by its OUTCOME instead — in the shipped config AND in both databases, because
#     the dumps are cut from a boot of that config and a stale dump is the likely way this regresses.
#   * `createDemoPatientsOnNextStartup` back at 50 is the worst case: only the pre-computed checksums
#     stop Initializer applying it, so a site that edits any config file would find 50 demo patients in
#     its production database on the next boot.
#   * with no location tagged `Login Location` nobody can sign in, and with none tagged `Queue Location`
#     /home (Service queues) throws on load — both measured, and both invisible to every other check.
#   * an empty dump taken before the convergence restart in docs/releasing.md §2 step (d) looks
#     perfectly healthy while shipping privilege-level roles ~180 grants short — nobody notices
#     until someone creates a user in a starter implementation.
#   * a clinical bound that reached one dump's boot and not the other's — step (c)'s ConceptNumeric
#     clamp is the one that moves. Each dump then looks healthy on its own, and only holding the two
#     against each other shows that the options would validate the same observation differently.
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

# `Ward N` is the same shape of placeholder and gets the same check. The digit is load-bearing in the
# pattern: an unanchored "Ward" would also match `Inpatient Ward`, which has to stay as the one Admission
# Location, and the `Ward Admission` form, which is the one form that ships.
WARDS=$(grep -rho "Ward [0-9]" "$LOC" 2>/dev/null | wc -l | tr -d ' ')
[ "${WARDS:-0}" = "0" ] \
    || fail "shipped Initializer config still defines 'Ward N' placeholder locations ($WARDS hits) — they duplicate the tags 'Inpatient Ward' already carries, and no bed is mapped to any of them"

CFG="$ART_DIR/appdata/configuration"

# The strip edits a plain content probe can assert; the ones needing column- or filename-awareness follow
# below, so that between them every edit the script makes is checked by its outcome. Each is silent if the
# filter stops matching: the script only WARNS on a miss so an upstream rename cannot break the build,
# which is exactly why the gate has to check the outcome.
# `Mobile Clinic` and `Community Outreach` are checked across the whole config rather than just the
# locations domain on purpose: `Community Outreach` was also the name of a cash point, which cashpoints
# reference by free text, so one probe catches both the location and the row that pointed at it.
for probe in \
    "Ubuntu Hospital:the demo hospital was not renamed" \
    "Paypal:payment mode 'Paypal' is still configured" \
    "Cambodia:the Cambodian address hierarchy is still shipped" \
    "Mobile Clinic:the 'Mobile Clinic' demo location is still shipped" \
    "Community Outreach:the 'Community Outreach' demo location, or the cash point named after it, is still shipped"
do
    needle="${probe%%:*}"; why="${probe#*:}"
    hits=$(grep -rlF "$needle" "$CFG" 2>/dev/null | wc -l | tr -d ' ')
    [ "${hits:-0}" = "0" ] || fail "$why — found '$needle' in $hits shipped config file(s)"
done

[ -d "$CFG/addresshierarchy" ] \
    && fail "shipped config still has an addresshierarchy domain — it is Cambodian and wrong for any other site"

# The demo relationship types. This edit went unchecked on the config side for a while, and it is not
# visible to anything else here: the bundled dumps are committed files rather than products of this build,
# so a strip that stopped matching would ship them in the config while the dumps stayed clean. Initializer
# would not load them on first boot either — the checksums generated after strip mark them already-applied
# — so they would sit inert until the first config edit re-applied the domain and materialised
# Uncle/Nephew in a production system. The form checks below carry the same reasoning.
if [ -d "$CFG/relationshiptypes" ]; then
    DEMO_RT=$(find "$CFG/relationshiptypes" -name '*.csv' -exec awk -F',' '
        function trim(s){gsub(/^[ \t]+|[ \t]+$/,"",s);return s}
        NR==1{for(i=1;i<=NF;i++) if(tolower(trim($i))=="name") c=i; next}
        c && (trim($c)=="Uncle/Nephew" || trim($c)=="Aunt/Niece" || trim($c)=="Friend/Friend"){n++}
        END{print n+0}' {} \; | paste -sd+ - | bc)
    [ "${DEMO_RT:-0}" = "0" ] \
        || fail "shipped config still defines $DEMO_RT demo relationship type(s) — Uncle/Nephew, Aunt/Niece or Friend/Friend survived strip-demo-fixtures.sh"
fi

# ── Forms ───────────────────────────────────────────────────────────────────
# strip-demo-fixtures.sh keeps an ALLOWLIST of forms rather than removing a named list, so the outcome to
# assert is "exactly the allowlist, nothing orphaned" — not "none of yesterday's fixtures". Read the
# allowlist from that script rather than repeating it: whoever adds a form there would otherwise get a
# publish failure here with no clue why. Fail loudly if it cannot be read, because defaulting would make
# this check pass for the wrong reason.
#
# Both directions matter, and they fail differently. An EXTRA form means the filter stopped matching and a
# form nobody vetted is live in every patient's chart from first boot. A MISSING one is the worse half:
# `Ward Admission` is the only shipped producer of the inpatient admission requests esm-ward-app lists
# (emrapi/inpatient/request, filtered on dispositionType ADMIT), so losing it to an upstream rename ships
# the ward and bed-management apps with a queue nothing can fill — and the strip only warns.
[ -d "$CFG/ampathforms" ] \
    || fail "no ampathforms domain in the shipped config — the layout changed, so the form checks below are not looking at anything"

FORMS_KEEP=$(sed -n 's/^FORMS_KEEP="\(.*\)"$/\1/p' "$(dirname "$0")/strip-demo-fixtures.sh" 2>/dev/null | head -1)
[ -n "$FORMS_KEEP" ] \
    || fail "could not read FORMS_KEEP from $(dirname "$0")/strip-demo-fixtures.sh — this gate cannot tell which forms are supposed to ship"

EXTRA_FORMS=""
while IFS= read -r form; do
    [ -n "$form" ] || continue
    base=$(basename "$form")
    keep=0
    for stem in $FORMS_KEEP; do
        case "$base" in "$stem"-*.json | "$stem".json) keep=1 ;; esac
    done
    [ "$keep" = 1 ] || EXTRA_FORMS="$EXTRA_FORMS $base"
done <<< "$(find "$CFG/ampathforms" -type f -name '*.json')"
[ -z "$EXTRA_FORMS" ] \
    || fail "shipped config carries form(s) that are not on strip-demo-fixtures.sh's allowlist ($FORMS_KEEP):${EXTRA_FORMS} — each ships published, so it is a live data-entry path in every patient's chart"

for stem in $FORMS_KEEP; do
    find "$CFG/ampathforms" -type f \( -name "$stem-*.json" -o -name "$stem.json" \) | grep -q . \
        || fail "allowlisted form '$stem' is missing from the shipped config — upstream renamed or dropped it and strip-demo-fixtures.sh only warned. For 'ipd_admission_request' that means esm-ward-app can never receive an admission request"
done

# A translation whose form is gone cannot load, and its filename does not name its form
# (`soap_note_translations_en-*` belongs to `soap_note_template-*`), so check the field the strip
# matches on. Guarded by the same reasoning as everything else here: the strip warns, this fails.
if [ -d "$CFG/ampathformstranslations" ]; then
    while IFS= read -r translation; do
        [ -n "$translation" ] || continue
        FORM_NAME=$(perl -0ne 'print $1 if m{"form"\s*:\s*"([^"]*)"}' "$translation")
        [ -n "$FORM_NAME" ] \
            || fail "shipped translation ${translation#"$CFG"/} declares no \"form\" field — this gate cannot tell whether the form it belongs to still ships"
        grep -rqF "\"$FORM_NAME\"" "$CFG/ampathforms" \
            || fail "shipped translation ${translation#"$CFG"/} is orphaned — it translates '$FORM_NAME', which no shipped form declares"
    done <<< "$(find "$CFG/ampathformstranslations" -type f -name '*.json')"
fi

# `SSN` needs a column-aware check: the bare string appears in unrelated prose.
if [ -d "$CFG/patientidentifiertypes" ]; then
    SSN=$(find "$CFG/patientidentifiertypes" -name '*.csv' -exec awk -F',' '
        function trim(s){gsub(/^[ \t]+|[ \t]+$/,"",s);return s}
        NR==1{for(i=1;i<=NF;i++) if(tolower(trim($i))=="name") c=i; next}
        c && trim($c)=="SSN"{n++} END{print n+0}' {} \; | paste -sd+ - | bc)
    [ "${SSN:-0}" = "0" ] || fail "shipped config still defines the US-specific 'SSN' identifier type"
fi

# The single most dangerous shipped value. Only the pre-computed checksums stop Initializer applying it,
# so if it is ever back at 50 a starter implementation that edits any config file gets 50 demo patients
# in its production database on the next boot.
# Checks EVERY file that carries it, not just the first. strip-demo-fixtures.sh patches all of them, and
# a gate that only inspected one would pass an artifact whose second globalproperties file still said 50 —
# exactly the silent outcome both scripts exist to prevent.
while IFS= read -r gp; do
    [ -n "$gp" ] || continue
    VAL=$(perl -0ne 'print $1 if m{<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>(\d+)</value>}s' "$gp")
    [ "${VAL:-x}" = "0" ] \
        || fail "shipped config file ${gp#"$CFG"/} sets createDemoPatientsOnNextStartup=${VAL:-unset}, not 0 — a starter implementation could generate ${VAL:-?} demo patients into production"
done <<< "$(grep -rl 'createDemoPatientsOnNextStartup' "$CFG" 2>/dev/null || true)"

# Pruning nothing here, so these tags come from the demo package's own locations. Resolve the column
# from the header and count rows that actually set it: matching the header text alone would pass
# whenever the column merely exists, which is no check at all.
# Runs inside $( ), so it CANNOT use fail(): stdout is captured by the caller, and `exit` would only
# end the subshell. The diagnosis therefore goes to stderr, and raggedness comes back as the sentinel
# -1 for the call site to act on. Getting this wrong hid the real cause behind a wrong ::error::
# annotation, which sent the release-cutter looking for a missing location tag that was present.
count_tagged() { # $1 = tag name; echoes the count, or -1 if a CSV is ragged
    local tag="$1" total=0 n csv
    while IFS= read -r csv; do
        [ -n "$csv" ] || continue
        if ! n=$(awk -F',' -v want="Tag|$tag" '
            NR==1{hdr=NF; for(i=1;i<=NF;i++) if($i==want) col=i; next}
            NF==0{next}
            NF!=hdr{ragged=1; exit 3}
            col && toupper($col)=="TRUE"{c++}
            END{if(!ragged) print c+0}' "$csv"); then
            # Carries the ::error:: prefix itself: GitHub parses workflow commands out of the step's
            # log regardless of stream, so the release-cutter gets the real cause as an annotation
            # rather than as a plain line they have to go hunting for below the failure.
            echo "::error::$csv has a row whose field count differs from its header — a quoted comma is shifting the columns, so this gate cannot tell which column is 'Tag|$tag'" >&2
            echo "-1"
            return
        fi
        total=$((total + n))
    done <<< "$(find "$LOC" -type f -name '*.csv')"
    echo "$total"
}

# $1 = tag name, $2 = why it matters
require_tagged() {
    local tag="$1" why="$2" n
    n=$(count_tagged "$tag")
    if [ "$n" = "-1" ]; then
        fail "cannot verify the '$tag' tag — see the parse diagnosis above; fix the locations CSV before publishing"
    fi
    [ "${n:-0}" -ge 1 ] || fail "$why"
}

require_tagged "Login Location" \
    "no shipped location is tagged 'Login Location' — the login screen would offer an empty picker and nobody could sign in"

# /home resolves to the Service Queues dashboard, and esm-service-queues-app throws
# `Cannot read properties of undefined (reading 'id')` when no location carries this tag — so the first
# screen after login would be an error page. Measured, not theorised.
require_tagged "Queue Location" \
    "no shipped location is tagged 'Queue Location' — /home (Service queues) would throw on load"

# The Admission Location the inpatient feature needs. `Ward Admission` sources its `admitToLocation`
# picker from this tag, and esm-ward-app lists the wards carrying it, so with none the form cannot be
# completed and the ward app has nothing to show. One is enough, which is why the strip keeps only
# `Inpatient Ward` and drops `Ward 1`..`Ward N`.
require_tagged "Admission Location" \
    "no shipped location is tagged 'Admission Location' — 'Ward Admission' has nothing to offer in its required admitToLocation field, and esm-ward-app has no ward to list"

# Every Login Location must have a Visit Location at or above it. O3 resolves a visit's location by
# walking up from the session location to the nearest one tagged `Visit Location`; with none in that
# chain, a user signs in perfectly well and then cannot start a visit — nothing else here notices,
# because `require_tagged "Visit Location"` would pass on a Visit Location parked somewhere irrelevant.
#
# This became worth guarding when the strip dropped `Mobile Clinic` and `Community Outreach`, which were
# Visit Locations in their own right: the shipped config went from three Visit Locations to one, and that
# one — `My Hospital` — is now the sole reason a visit can start at either remaining Login Location. It
# also reads as the most disposable name in the file, being a placeholder a site is told to rename.
#
# Parents are matched by NAME because that is how the CSV references them (the same reason
# strip-demo-fixtures.sh rewrites the Parent column when it renames). A chain that leaves the CSV counts
# as unreachable: it may well resolve against a module-created location at runtime, but this gate cannot
# prove a visit can start, and for a publish gate that is the safe direction to fail in.
UNREACHABLE=$(find "$LOC" -type f -name '*.csv' -exec awk -F',' '
    function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
    FNR == 1 {
        nc = pc = lc = vc = 0
        for (i = 1; i <= NF; i++) {
            t = trim($i)
            if (t == "Name") nc = i
            else if (t == "Parent") pc = i
            else if (t == "Tag|Login Location") lc = i
            else if (t == "Tag|Visit Location") vc = i
        }
        next
    }
    nc && NF > 1 {
        n = trim($nc)
        if (n == "") next
        parent[n] = pc ? trim($pc) : ""
        if (vc && toupper(trim($vc)) == "TRUE") visit[n] = 1
        if (lc && toupper(trim($lc)) == "TRUE") login[n] = 1
    }
    END {
        for (n in login) {
            cur = n; hops = 0; ok = 0
            # Bounded so a Parent cycle cannot hang the publish.
            while (cur != "" && hops++ < 20) {
                if (cur in visit) { ok = 1; break }
                cur = parent[cur]
            }
            if (!ok) print n
        }
    }' {} \; | sort -u | paste -sd, -)
[ -z "$UNREACHABLE" ] \
    || fail "shipped Login Location(s) with no 'Visit Location' at or above them: $UNREACHABLE — a user can sign in there and then cannot start a visit. Either tag one of their ancestors 'Visit Location' or give them a Parent that has it"

# ── Initializer checksums ───────────────────────────────────────────────────
# Every config file Initializer can load must sit beside a checksum file holding the MD5 of its CURRENT
# content. That is the only thing stopping Initializer re-applying the whole configuration on first boot
# — confirmed on a real boot of a built artifact, where it loaded nothing at all.
#
# scripts/generate-checksums.sh runs in the same process-resources phase as strip-demo-fixtures.sh and
# AFTER it, so the checksums describe the filtered files. A future filter added in a later phase, or the
# two executions reordered in pom-step-01.xml, would leave every checksum it touched stale and silently
# change first-boot behaviour. Nothing above notices, because every content check still passes: the file
# is exactly what we wanted, and only the checksum beside it disagrees.
CHKSUM_DIR="$ART_DIR/appdata/configuration_checksums"
[ -d "$CHKSUM_DIR" ] \
    || fail "no appdata/configuration_checksums in the artifact — Initializer would re-apply the entire configuration on first boot"
if command -v md5sum >/dev/null 2>&1; then
    md5_of() { md5sum "$1" | cut -d' ' -f1; }
elif command -v md5 >/dev/null 2>&1; then
    md5_of() { md5 -q "$1"; }
else
    fail "neither md5sum nor md5 is available — cannot verify the Initializer checksums"
fi
STALE=0
VERIFIED=0
while IFS= read -r cf; do
    [ -n "$cf" ] || continue
    # Only what Initializer loads, which is the extension list below. The two .gitkeep placeholders
    # are skipped because nothing loads them - NOT because they have no checksum: generate-checksums.sh
    # is a plain `find -type f` and does emit one for each, landing as `<domain>/<package>_.checksum`
    # and `<package>/.checksum` (empty stem, since .gitkeep has no basename). Harmless, since
    # Initializer looks a checksum up by its config file and so never reads those two, but do not read
    # a missing entry here as evidence that none was written.
    case "$cf" in *.csv | *.xml | *.json | *.zip) ;; *) continue ;; esac
    rel=${cf#"$CFG"/}
    case "$rel" in */*/*) ;; *) continue ;; esac   # <domain>/<package>/<file>
    domain=${rel%%/*}
    rest=${rel#*/}
    stem=${rest#*/}
    expected="$CHKSUM_DIR/$domain/${rest%%/*}_${stem%.*}.checksum"
    if [ ! -f "$expected" ]; then
        echo "::error::shipped config $rel has no checksum file — Initializer would apply it on first boot"
        STALE=$((STALE + 1))
    elif [ "$(cat "$expected")" = "$(md5_of "$cf")" ]; then
        VERIFIED=$((VERIFIED + 1))
    else
        echo "::error::stale checksum for $rel — it was edited after generate-checksums.sh ran, so Initializer will re-apply it on first boot"
        STALE=$((STALE + 1))
    fi
done <<< "$(find "$CFG" -type f)"
[ "$STALE" -eq 0 ] \
    || fail "$STALE shipped config file(s) disagree with their Initializer checksum — see the annotations above"
# Guard against the check silently matching nothing, which is how two earlier checks in this file
# managed to pass while verifying zero rows. The real config carries ~86 loadable files — it was ~94
# before the form allowlist and the addresshierarchy removal, so treat the number as a rough scale check
# rather than a count to keep in step; the floor is what does the work.
[ "$VERIFIED" -ge 50 ] \
    || fail "only $VERIFIED config checksums verified — this check matched almost nothing, so it is not guarding anything"
echo "Initializer checksums: $VERIFIED config file(s) match, none stale."

# ── Bundled databases ──────────────────────────────────────────────────────
# Start from a clean scratch dir: an earlier failed run leaves extracted SQL behind, and `unzip -o`
# only overwrites same-named files, so a stale dump could be compared instead of the one shipping.
# `unzip -d` also creates only the leaf directory, hence the mkdir.
DB_CHECK=$(mktemp -d)
trap 'rm -rf "$DB_CHECK"' EXIT
mkdir -p "$DB_CHECK/empty" "$DB_CHECK/demo"

unzip -o -q "$ART_DIR/emptydatabase.zip" -d "$DB_CHECK/empty" || fail "could not read bundled emptydatabase.zip"
unzip -o -q "$ART_DIR/demodatabase.zip"  -d "$DB_CHECK/demo"  || fail "could not read bundled demodatabase.zip"

# Exactly one dump per zip, not just at least one. StandaloneUtil imports sqlFiles[0] from
# File.listFiles(), whose order is filesystem-dependent and unspecified, and the `head -1` below makes
# its own independent pick — so a second .sql would let the standalone import one dump while this gate
# verified the other, silently. The assembly descriptors pin an exact filename per version, so this
# cannot happen today; the check is here to keep it that way, since the two ways of choosing would
# disagree without either side complaining.
count_sql() { find "$1" -name '*.sql' | wc -l | tr -d ' '; }
for z in empty:emptydatabase.zip demo:demodatabase.zip; do
    n=$(count_sql "$DB_CHECK/${z%%:*}")
    [ "$n" = "1" ] \
        || fail "bundled ${z#*:} holds $n .sql files, expected exactly 1 — the standalone imports whichever File.listFiles() returns first, which need not be the one this gate checks"
done

EMPTY_SQL=$(find "$DB_CHECK/empty" -name '*.sql' | head -1)
DEMO_SQL=$(find "$DB_CHECK/demo" -name '*.sql' | head -1)
[ -n "$EMPTY_SQL" ] || fail "bundled emptydatabase.zip contains no .sql"
[ -n "$DEMO_SQL" ]  || fail "bundled demodatabase.zip contains no .sql"

# `-a` on the two greps that COUNT matches in a dump, here and in full_grants. One NUL byte anywhere
# in the file makes GNU grep treat it as binary, and `grep -o` then emits nothing at all — no matches,
# and no "Binary file matches" line on stdout or stderr either — so `wc -l` reads 0 and this check
# would pass a dump carrying all 50 placeholders. Measured on grep 3.11, along with the two things
# that are NOT the trigger: `grep -q` still exits 0 on a binary match (so the -qaF checks below never
# depended on their -a), and an encoding error alone does not flip grep to binary, which matters
# because both dumps ARE invalid UTF-8 from openconceptlab's hash column. Only a NUL does it, and
# mysqldump escapes those - both dumps measure zero - so this is insurance rather than a live bug. It
# buys the one failure shape a publish gate must never have: a check that silently counts nothing.
check_no_sites() { # $1 = label, $2 = dump path
    local hits
    hits=$(grep -oa "'Site [0-9]" "$2" | wc -l | tr -d ' ')
    [ "${hits:-0}" = "0" ] \
        || fail "bundled $1 database still contains 'Site N' placeholder locations ($hits hits)"
}

# The rest of the location trim, on the dump side. Quoted SQL literals rather than bare words: `Ward` on
# its own also appears in `Inpatient Ward` (which must stay) and the `Ward Admission` form (the one form
# that ships), and `Community Outreach` was a cash point name as well as a location — the quotes keep this
# to actual column values, and catching the cash point too is deliberate.
check_no_demo_locations() { # $1 = label, $2 = dump path
    local needle hits
    for needle in "'Ward [0-9]" "'Mobile Clinic'" "'Community Outreach'"; do
        # `-a` for the reason spelled out above check_no_sites: one NUL byte would make `grep -o` emit
        # nothing at all, and this check would then pass a dump carrying every one of them.
        hits=$(grep -oa "$needle" "$2" | wc -l | tr -d ' ')
        [ "${hits:-0}" = "0" ] \
            || fail "bundled $1 database still contains $needle ($hits hits) — it was dumped before strip-demo-fixtures.sh trimmed the demo locations, so the login and admission pickers carry placeholders a site has to retire by hand"
    done
}

# The dump must carry EXACTLY the forms the shipped config declares. Compared as a set, in both
# directions, because the two failures mean different things: an extra form in the dump is a dump cut
# before the allowlist (or a stale one bundled), and a missing one is a config the dump never saw.
#
# This is the check that was missing when it mattered. A regeneration shipped all 7 forms from a config
# declaring 1, and passed everything: a surviving Docker volume re-applied the 6 deleted JSONs, because
# the distro copies config OVER `/openmrs/data/configuration` instead of replacing it. The same run's
# locations were correct, since a modified file is overwritten while a deleted one just stays — so no
# single-sided check could see it. scripts/generate-*-locally.sh now `down -v` before booting; this is
# the belt to that braces, and unlike the scripts it also covers a stale dump bundled by the assembly.
#
# Matched on NAMES, not filenames: `"name"` in the JSON is what lands in `form`.`name`, and they differ
# (ipd_admission_request -> "Ward Admission"). Reading the wrong `"name"` out of a JSON can only invent an
# expected form the dump lacks, i.e. fail loudly, never pass wrongly.
config_form_names() {
    find "$CFG/ampathforms" -type f -name '*.json' -exec \
        perl -0ne 'print "$1\n" if m{"name"\s*:\s*"([^"]*)"}' {} \; | LC_ALL=C sort -u
}

# LC_ALL=C on the sed for the reason clinical_bounds() gives: it streams the whole dump, and both dumps
# are invalid UTF-8 in openconceptlab's hash column.
dump_form_names() { # $1 = dump path
    LC_ALL=C sed -n "/^INSERT INTO \`form\` VALUES\$/,/;\$/p" "$1" \
        | LC_ALL=C grep '^(' \
        | LC_ALL=C sed "s/^([0-9]*,'\([^']*\)'.*/\1/" | LC_ALL=C sort -u
}

check_forms_match_config() { # $1 = label, $2 = dump path
    local expected actual extra missing
    expected=$(config_form_names)
    actual=$(dump_form_names "$2")
    # Non-vacuity on both sides: two empty sets compare equal and would report agreement, which is the
    # one failure shape a publish gate must not have.
    [ -n "$expected" ] \
        || fail "the shipped config declares no form at all — the allowlist check above should have caught this, so this gate is not reading the forms it thinks it is"
    [ -n "$actual" ] \
        || fail "bundled $1 database has no \`form\` rows — the shipped config declares $(printf '%s' "$expected" | paste -sd, -), so this dump was cut from a different configuration"
    extra=$(comm -13 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | paste -sd, -)
    missing=$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") | paste -sd, -)
    [ -z "$extra" ] \
        || fail "bundled $1 database carries form(s) the shipped config does not declare: $extra — each is published, so it is live in every patient's chart. Either the dump predates the form allowlist, or it was cut on a Docker volume left by an earlier run (see the pre-boot 'down -v' in scripts/generate-*-locally.sh)"
    [ -z "$missing" ] \
        || fail "bundled $1 database is missing form(s) the shipped config declares: $missing — the dump was cut from an older configuration. If 'Ward Admission' is in that list, esm-ward-app can never receive an admission request"
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

# The rename and the demo-patient switch have to be visible in the DATABASES too, not just the config:
# both dumps are cut from a boot of that config, so a stale dump is the likely way this regresses.
# Read the placeholder from strip-demo-fixtures.sh rather than repeating it: whoever changes the name
# there would otherwise get a publish failure here with no clue why. Fail loudly if it cannot be read,
# because defaulting would make this check pass for the wrong reason.
PLACEHOLDER=$(sed -n 's/^HOSPITAL_PLACEHOLDER="\(.*\)"$/\1/p' "$(dirname "$0")/strip-demo-fixtures.sh" 2>/dev/null | head -1)
[ -n "$PLACEHOLDER" ] \
    || fail "could not read HOSPITAL_PLACEHOLDER from $(dirname "$0")/strip-demo-fixtures.sh — this gate cannot tell what the demo hospital should have been renamed to"

# `grep -qaF` directly, never `grep -oaF ... | grep -q .`: grep -q exits on its first match and closes
# the pipe, the upstream grep dies of SIGPIPE with status 141, and `pipefail` makes 141 the pipeline's
# status. That silently skipped the `&& fail` once a dump carried enough matches to fill the pipe buffer
# (~900 on macOS, ~3600 on the Linux runner), and flipped the other way on the `|| fail` below, failing
# a perfectly good artifact. A check that gets less reliable the more rows carry the old name is the
# wrong shape for a publish gate.
check_renamed() { # $1 = label, $2 = dump path
    grep -qaF "'Ubuntu Hospital'" "$2" \
        && fail "bundled $1 database still contains 'Ubuntu Hospital' — it was dumped before strip-demo-fixtures.sh renamed it"
    grep -qaF "'$PLACEHOLDER'" "$2" \
        || fail "bundled $1 database has no '$PLACEHOLDER' location — was it dumped from an unfiltered config?"
}

# The config is checked for this above, but the dumps are what actually ship the value, and this gate is
# the only thing that opens the bundled zips. BundledDbDumpImportTest asserts it too, but from
# src/main/db/, so an assembly that bundled the wrong file — the very failure this gate exists to catch —
# was the one case nothing covered.
check_demo_patients_off() { # $1 = label, $2 = dump path
    grep -qaF "createDemoPatientsOnNextStartup','0'" "$2" \
        || fail "bundled $1 database does not ship createDemoPatientsOnNextStartup=0 — ReferenceDemoDataActivator generates that many patients whenever this is above 0 and runtime property referencedemodata.createDemoPatients is missing or true, and missing DEFAULTS to true"
}

# The only check here that can tell the two dumps APART. Every other one evaluates identically on
# both (measured), so a mis-copied mysqldump in docs/releasing.md §2 — two near-identical commands a
# few lines apart, differing only in the destination filename — would ship the demo database as the
# Starter option and pass this whole gate. mysqldump omits the INSERT statement entirely for an empty
# table, so the presence of that line is the population test. Nothing else covers it: the assertion
# on patient counts lives in BundledDbDumpImportTest, which reads src/main/db/ directly rather than
# the assembled zip.
check_patient_rows() { # $1 = label, $2 = dump path, $3 = none|some
    local label="$1" sql="$2" want="$3" t populated=""
    for t in patient obs visit; do
        # `INTO \`table\`` rather than `INSERT INTO \`table\``: --insert-ignore and --replace make
        # mysqldump write `INSERT IGNORE INTO` and `REPLACE INTO`, either of which would slip past the
        # longer literal and hand back a false pass. The trailing backtick keeps the table name exact,
        # so patient_identifier_type - which both dumps do carry - is not mistaken for patient data.
        grep -qaF "INTO \`$t\`" "$sql" && populated="$populated $t"
    done
    populated=${populated# }
    if [ "$want" = none ] && [ -n "$populated" ]; then
        fail "bundled $label database carries rows in: $populated — the whole point of the Starter option is that it does not. The demo dump was almost certainly bundled as $label"
    fi
    if [ "$want" = some ] && [ -z "$populated" ]; then
        fail "bundled $label database has no patient, obs or visit rows — the Starter dump was almost certainly bundled as $label"
    fi
}

# The two strip edits whose database side nothing checked. The config-side probes for these sit near the
# top of this file and only look at $CFG, so a dump cut before the Paypal edit would carry that payment
# mode in its tables and pass everything else here — and the dumps are exactly where this regresses, since
# they are committed files that a maintainer regenerates by hand.
#
# Only the Paypal one can fail today. The address-hierarchy check is a floor rather than a live guard: that
# domain never reaches the database, because Initializer's loader resolves the fixed name
# `addressConfiguration.xml` directly under <config>/addresshierarchy/ while build-distro nests content
# package files a level deeper as <domain>/<package>/<file>. Both dumps already carried zero
# address_hierarchy_entry rows and core's default address template before strip-demo-fixtures.sh removed
# the domain, so do not read a pass here as evidence the removal did anything. It stays because the day
# those paths line up, `<wipe>true</wipe>` in that XML swaps the address template for Cambodian province,
# district and commune fields, and this is the only check that would notice.
# Row presence, not the table's existence — the schema ships the tables either way, empty.
check_no_stripped_content() { # $1 = label, $2 = dump path
    # The single quotes are load-bearing and shellcheck's SC2016 hint is a trap here: those backticks
    # are MySQL identifier quoting, so taking the advice and switching to double quotes would turn
    # `address_hierarchy_entry` into a command substitution, leaving the needle as "INTO " and this
    # check silently matching every dump. Suppressed rather than "fixed", with the reason attached.
    # shellcheck disable=SC2016
    if grep -qaF 'INTO `address_hierarchy_entry`' "$2"; then
        fail "bundled $1 database carries address_hierarchy_entry rows — it was dumped before strip-demo-fixtures.sh removed the Cambodian address hierarchy, so registration would offer Cambodian provinces"
    fi
    if grep -qaF 'Paypal' "$2"; then
        fail "bundled $1 database still contains the 'Paypal' payment mode — dumped before strip-demo-fixtures.sh removed it"
    fi
}

check_no_sites starter "$EMPTY_SQL"
check_no_sites demo "$DEMO_SQL"
check_no_demo_locations starter "$EMPTY_SQL"
check_no_demo_locations demo "$DEMO_SQL"
check_forms_match_config starter "$EMPTY_SQL"
check_forms_match_config demo "$DEMO_SQL"
check_renamed starter "$EMPTY_SQL"
check_renamed demo "$DEMO_SQL"
check_demo_patients_off starter "$EMPTY_SQL"
check_demo_patients_off demo "$DEMO_SQL"
check_patient_rows starter "$EMPTY_SQL" none
check_patient_rows demo "$DEMO_SQL" some
check_no_stripped_content starter "$EMPTY_SQL"
check_no_stripped_content demo "$DEMO_SQL"
check_complete starter "$EMPTY_SQL"
check_complete demo "$DEMO_SQL"

# The clinical bounds must be IDENTICAL in the two dumps, for the same reason the grants below must
# be: one converged database, two cuts of it. This is the one difference that is invisible to every
# other check here, because each of those evaluates one dump at a time and both sides pass on their
# own — a concept simply carries a different number in each.
#
# It has happened. docs/releasing.md §2 step (c) clamps every ConceptNumeric into its
# reference-range intersection before either dump is cut, and generate-empty-db-locally.sh used to
# skip it while generate-demo-data-locally.sh applied it - so the starter dump shipped respiratory
# rate's declared hi_absolute of 999 where the demo dump shipped the 99 its reference-range bands
# carry, reproducibly rather than by drift. hi_absolute/low_absolute are what core validates an obs
# against when no reference-range criterion matches the patient, so the two options would have
# accepted different observations.
#
# Compares the two dumps to each other rather than to a number. That is deliberate and bounded: it
# catches the clamp reaching one boot and not the other, and it CANNOT catch the clamp being skipped
# on both - only step (c)'s divergence query settles that, and §2 says so. The unclamped side is the
# one that matches what the content package declares, so do not read "matches upstream" as "correct"
# here. BundledDbDumpImportTest asserts the same thing from src/main/db/, and also on pull requests,
# which this script never runs on; this is the copy that opens the bundled zips, so it additionally
# catches an assembly that shipped a stale dump.
#
# LC_ALL=C on the sed: it streams the whole dump, and both dumps are invalid UTF-8 in
# openconceptlab's hash column, which a locale-aware sed can error on. On sort it is belt and braces
# - both sides get the same locale and comparator, and neither of these two tables holds a
# non-ASCII byte - kept so the pipeline does not depend on that staying true.
clinical_bounds() { # $1 = dump path, $2 = table
    LC_ALL=C sed -n "/^INSERT INTO \`$2\` VALUES\$/,/;\$/p" "$1" | LC_ALL=C grep '^(' | LC_ALL=C sort
}

check_bounds_agree() { # $1 = table
    local table="$1" e d
    e=$(clinical_bounds "$EMPTY_SQL" "$table")
    d=$(clinical_bounds "$DEMO_SQL" "$table")
    # Non-vacuity, and on BOTH sides. Two empty results compare equal and would report agreement,
    # which is the one failure shape a publish gate must not have. One empty side is the subtler
    # half: it is not equal to the other, so without this it comes back as a value disagreement and
    # sends whoever is cutting the release to re-dump a database whose actual problem is that
    # mysqldump changed how it writes an INSERT.
    [ -n "$e" ] \
        || fail "no \`$table\` rows found in the bundled starter database — this check is not reading the table it thinks it is, so it is not guarding anything"
    [ -n "$d" ] \
        || fail "no \`$table\` rows found in the bundled demo database — this check is not reading the table it thinks it is, so it is not guarding anything"
    if [ "$e" != "$d" ]; then
        # Capped, and the message says so rather than trailing off, or the release-cutter fixes what
        # they can see and meets the rest on the next run. Counted in LINES, not rows: diff spends
        # about four lines on an isolated row (`NcN`, `<`, `---`, `>`) and can spend a whole run of
        # `<` lines before the first `>` when rows are contiguous, so a row-shaped cap here would be
        # a number that does not mean what it says.
        diff <(printf '%s\n' "$e") <(printf '%s\n' "$d") | head -40
        fail "the two bundled databases disagree on \`$table\` (starter '<' vs demo '>', first 40 lines of the diff above; re-run locally for the rest) — step (c) in docs/releasing.md §2 clamps ConceptNumeric into its reference-range intersection before either dump is cut, and a dump that missed it carries the wider bound; its divergence query says which side is right, and it is not automatically the fresher dump"
    fi
}

check_bounds_agree concept_numeric
check_bounds_agree concept_reference_range

# Counts the single `role` row plus every Privilege Level: Full grant. The absolute number is not the
# point — the two dumps must AGREE, since both are cut from the same converged database.
# `-a` for the reason on check_no_sites. Here it fails loudly rather than silently — both sides would
# count 0 and trip the floor below — but the diagnosis would send whoever is cutting the release to
# re-dump a database that was fine.
full_grants() { grep -oa "('Privilege Level: Full'," "$1" | wc -l | tr -d ' '; }
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
