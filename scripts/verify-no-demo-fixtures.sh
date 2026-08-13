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

CFG="$ART_DIR/appdata/configuration"

# Every other edit strip-demo-fixtures.sh makes, asserted by its visible effect. Each one is silent if
# the filter stops matching: the script only WARNS on a miss so an upstream rename cannot break the
# build, which is exactly why the gate has to check the outcome.
for probe in \
    "Ubuntu Hospital:the demo hospital was not renamed" \
    "Paypal:payment mode 'Paypal' is still configured" \
    "Cambodia:the Cambodian address hierarchy is still shipped"
do
    needle="${probe%%:*}"; why="${probe#*:}"
    hits=$(grep -rlF "$needle" "$CFG" 2>/dev/null | wc -l | tr -d ' ')
    [ "${hits:-0}" = "0" ] || fail "$why — found '$needle' in $hits shipped config file(s)"
done

[ -d "$CFG/addresshierarchy" ] \
    && fail "shipped config still has an addresshierarchy domain — it is Cambodian and wrong for any other site"

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
DEMO_GP=$(grep -rl 'createDemoPatientsOnNextStartup' "$CFG" 2>/dev/null | head -1)
if [ -n "$DEMO_GP" ]; then
    VAL=$(perl -0ne 'print $1 if m{<property>referencedemodata\.createDemoPatientsOnNextStartup</property>\s*<value>(\d+)</value>}s' "$DEMO_GP")
    [ "${VAL:-x}" = "0" ] \
        || fail "shipped config sets createDemoPatientsOnNextStartup=${VAL:-unset}, not 0 — a starter implementation could generate ${VAL:-?} demo patients into production"
fi

# Pruning nothing here, so these tags come from the demo package's own locations. Resolve the column
# from the header and count rows that actually set it: matching the header text alone would pass
# whenever the column merely exists, which is no check at all.
count_tagged() { # $1 = tag name
    local tag="$1" total=0 n csv
    while IFS= read -r csv; do
        [ -n "$csv" ] || continue
        n=$(awk -F',' -v want="Tag|$tag" '
            NR==1{hdr=NF; for(i=1;i<=NF;i++) if($i==want) col=i; next}
            NF==0{next}
            NF!=hdr{ragged=1; exit 3}
            col && toupper($col)=="TRUE"{c++}
            END{if(!ragged) print c+0}' "$csv") \
          || fail "$csv has a row whose field count differs from its header — a quoted comma is shifting the columns, so this gate cannot tell which column is 'Tag|$tag'"
        total=$((total + n))
    done <<< "$(find "$LOC" -type f -name '*.csv')"
    echo "$total"
}

LOGIN_TAGGED=$(count_tagged "Login Location")
[ "${LOGIN_TAGGED:-0}" -ge 1 ] \
    || fail "no shipped location is tagged 'Login Location' — the login screen would offer an empty picker and nobody could sign in"

# /home resolves to the Service Queues dashboard, and esm-service-queues-app throws
# `Cannot read properties of undefined (reading 'id')` when no location carries this tag — so the first
# screen after login would be an error page. Measured, not theorised.
QUEUE_TAGGED=$(count_tagged "Queue Location")
[ "${QUEUE_TAGGED:-0}" -ge 1 ] \
    || fail "no shipped location is tagged 'Queue Location' — /home (Service queues) would throw on load"

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

# The rename and the demo-patient switch have to be visible in the DATABASES too, not just the config:
# both dumps are cut from a boot of that config, so a stale dump is the likely way this regresses.
# Read the placeholder from strip-demo-fixtures.sh rather than repeating it: whoever changes the name
# there would otherwise get a publish failure here with no clue why. Fail loudly if it cannot be read,
# because defaulting would make this check pass for the wrong reason.
PLACEHOLDER=$(sed -n 's/^HOSPITAL_PLACEHOLDER="\(.*\)"$/\1/p' "$(dirname "$0")/strip-demo-fixtures.sh" 2>/dev/null | head -1)
[ -n "$PLACEHOLDER" ] \
    || fail "could not read HOSPITAL_PLACEHOLDER from $(dirname "$0")/strip-demo-fixtures.sh — this gate cannot tell what the demo hospital should have been renamed to"

check_renamed() { # $1 = label, $2 = dump path
    grep -oaF "'Ubuntu Hospital'" "$2" | grep -q . \
        && fail "bundled $1 database still contains 'Ubuntu Hospital' — it was dumped before strip-demo-fixtures.sh renamed it"
    grep -oaF "'$PLACEHOLDER'" "$2" | grep -q . \
        || fail "bundled $1 database has no '$PLACEHOLDER' location — was it dumped from an unfiltered config?"
}

check_no_sites starter "$EMPTY_SQL"
check_no_sites demo "$DEMO_SQL"
check_renamed starter "$EMPTY_SQL"
check_renamed demo "$DEMO_SQL"
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
