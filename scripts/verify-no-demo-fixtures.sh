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

# The strip edits a plain content probe can assert; the ones needing column- or filename-awareness follow
# below, so that between them every edit the script makes is checked by its outcome. Each is silent if the
# filter stops matching: the script only WARNS on a miss so an upstream rename cannot break the build,
# which is exactly why the gate has to check the outcome.
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

# The demo relationship types and the developer forms. Both edits went unchecked on the config side for a
# while, and neither is visible to anything else here: the bundled dumps are committed files rather than
# products of this build, so a strip that stopped matching would ship them in the config while the dumps
# stayed clean. Initializer would not load them on first boot either — the checksums generated after strip
# mark them already-applied — so they would sit inert until the first config edit re-applied the domain and
# materialised a developer form, or Uncle/Nephew, in a production system.
if [ -d "$CFG/relationshiptypes" ]; then
    DEMO_RT=$(find "$CFG/relationshiptypes" -name '*.csv' -exec awk -F',' '
        function trim(s){gsub(/^[ \t]+|[ \t]+$/,"",s);return s}
        NR==1{for(i=1;i<=NF;i++) if(tolower(trim($i))=="name") c=i; next}
        c && (trim($c)=="Uncle/Nephew" || trim($c)=="Aunt/Niece" || trim($c)=="Friend/Friend"){n++}
        END{print n+0}' {} \; | paste -sd+ - | bc)
    [ "${DEMO_RT:-0}" = "0" ] \
        || fail "shipped config still defines $DEMO_RT demo relationship type(s) — Uncle/Nephew, Aunt/Niece or Friend/Friend survived strip-demo-fixtures.sh"
fi

# Fail if the forms domain itself is missing rather than reporting zero developer forms: the distro ships
# seven real forms, so an empty result means the layout moved and this check stopped looking at anything.
[ -d "$CFG/ampathforms" ] \
    || fail "no ampathforms domain in the shipped config — the layout changed, so the developer-form check below is not looking at anything"
DEV_FORMS=$(find "$CFG/ampathforms" "$CFG/ampathformstranslations" -type f \
    \( -name 'test_form-*' -o -name 'form-engine-cookbook-*' -o -name 'test_form_1_translations_*' \) 2>/dev/null \
    | wc -l | tr -d ' ')
[ "${DEV_FORMS:-0}" = "0" ] \
    || fail "shipped config still carries $DEV_FORMS developer form file(s) — 'Test Form 1', the Form Engine Cookbook or its orphan translation survived strip-demo-fixtures.sh"

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
# managed to pass while verifying zero rows. The real config carries ~94 loadable files.
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
# It has happened. The two dumps are cut from separate boots, and the boots do not always agree about
# which source wins for a concept that has both its own declared bounds and a conceptreferencerange
# CSV: on 2026-08-13 the starter boot ended with respiratory rate's declared hi_absolute of 999 and
# the demo boot, 90 minutes later on the same pinned config, with the 99 its reference-range bands
# carry. hi_absolute/low_absolute are what core validates an obs against when no reference-range
# criterion matches the patient, so the two options would have accepted different observations.
#
# Compared by content, not against a hardcoded number: the right value is whatever the content
# package declares, and pinning it here would be an upstream constant with nothing tying the two
# together. docs/releasing.md §2 says how to decide which side is right, and it is not automatically
# the dump that was cut most recently. BundledDbDumpImportTest asserts the same thing at PR time,
# from src/main/db/; this is the copy that opens the bundled zips, so it also catches an assembly
# that shipped a stale dump.
#
# LC_ALL=C throughout: both dumps are invalid UTF-8 in openconceptlab's hash column, and a locale-
# aware sed or sort would either error or order the rows differently on the two sides.
clinical_bounds() { # $1 = dump path, $2 = table
    LC_ALL=C sed -n "/^INSERT INTO \`$2\` VALUES\$/,/;\$/p" "$1" | LC_ALL=C grep '^(' | LC_ALL=C sort
}

check_bounds_agree() { # $1 = table
    local table="$1" e d
    e=$(clinical_bounds "$EMPTY_SQL" "$table")
    d=$(clinical_bounds "$DEMO_SQL" "$table")
    # Non-vacuity: a dump-format change that stopped this matching would otherwise compare two empty
    # strings and report agreement, which is the one failure shape a publish gate must not have.
    [ -n "$e" ] \
        || fail "no \`$table\` rows found in the bundled starter database — this check is not reading the table it thinks it is, so it is not guarding anything"
    if [ "$e" != "$d" ]; then
        diff <(printf '%s\n' "$e") <(printf '%s\n' "$d") | head -20
        fail "the two bundled databases disagree on \`$table\` (starter '<' vs demo '>' above) — both are cut from the same config, so one was written from a source the other did not use; decide which matches the content package before publishing (docs/releasing.md §2)"
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
