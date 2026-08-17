#!/bin/bash
set -uo pipefail

# Clamps every ConceptNumeric into its reference-range intersection, then proves it worked.
#
# Usage: clamp-concept-numeric.sh <db_container> <root_password>
#
# WHY THIS EXISTS AT ALL
#
# referencedemodata's DemoObsGenerator clamps a generated numeric obs to the concept's
# ConceptNumeric absolute bounds, but core >= 2.8 validates that obs against the concept's
# ConceptReferenceRange absolute bounds. Where those diverge, a clamped value can still fall outside
# the reference range, saveObs throws, and the activator aborts the WHOLE remaining run - with a
# fixed RNG seed, identically every time. Clamping into the intersection first is version-independent:
# no offending concept ids to re-derive by hand on the next refapp bump. On refapp 3.7.1 it moves
# exactly two rows - concept_id 4184, Respiratory rate (CIEL 5242), hi_absolute 999 -> 99, and
# concept_id 210, Alkaline phosphatase (CIEL 785), low_absolute unset -> 0. The ids and the CIEL codes
# are different numbers; concept_numeric is keyed by the former.
#
# WHY IT IS A SHARED SCRIPT RATHER THAN A COPY IN EACH GENERATOR
#
# It used to live only in generate-demo-data-locally.sh, and generate-empty-db-locally.sh had nothing
# equivalent - so the starter dump shipped the unclamped bounds while the demo dump shipped the clamped
# ones, reproducibly on every regeneration, because two scripts cut the two dumps from two separate
# boots. The two shipped databases then disagreed about what a clinician may record, and every row
# count on both sides still added up. Note what the actual defect was: an omission, not a divergence
# between copies. De-duplicating would not have caught it. What catches it is the verification below,
# which is why that lives here in the producer rather than only in the detectors. One shared copy is
# for the next edit, so the two callers cannot drift from each other later.
#
# docs/releasing.md §2 step (c) is the same statement inline, for the Docker runbook that cuts both
# dumps from one database and so only needs it once. Change this and change that.
#
# BundledDbDumpImportTest.bothDumpsShouldAgreeOnClinicalBounds and scripts/verify-no-demo-fixtures.sh
# are the detectors on the other side: they compare the two shipped dumps to each other, so they
# catch this reaching one boot and not the other. They cannot catch it being skipped on both, which
# is the other reason the verification belongs here.

DB_CONTAINER="${1:-}"
DB_ROOT_PASSWORD="${2:-}"

if [ -z "$DB_CONTAINER" ] || [ -z "$DB_ROOT_PASSWORD" ]; then
    echo "Usage: $0 <db_container> <root_password>" >&2
    exit 1
fi

mysql_exec() { docker exec "$DB_CONTAINER" mysql -uroot -p"$DB_ROOT_PASSWORD" "$@" openmrs; }

# The join and the "too wide" test, defined once and interpolated into both the UPDATE that fixes and
# the SELECT that checks. Two hand-kept copies twenty lines apart is how a verification ends up
# passing while the rows it checks are still unclamped - and the dangerous direction is the quiet one,
# a narrowed check against an unchanged UPDATE. docs/releasing.md §2 states the same test in a
# GROUP BY/HAVING shape for use as a standalone diagnostic; it is equivalent because concept_numeric
# is keyed by concept_id.
RR_JOIN="JOIN (SELECT concept_id, MAX(low_absolute) AS rr_low, MIN(hi_absolute) AS rr_hi
                 FROM concept_reference_range GROUP BY concept_id) rr
           ON rr.concept_id = cn.concept_id"
TOO_WIDE="(rr.rr_low IS NOT NULL AND (cn.low_absolute IS NULL OR cn.low_absolute < rr.rr_low))
       OR (rr.rr_hi  IS NOT NULL AND (cn.hi_absolute  IS NULL OR cn.hi_absolute  > rr.rr_hi))"

echo "🔧 Clamping ConceptNumeric bounds into their reference-range intersection..."
mysql_exec -e "
UPDATE concept_numeric cn
  $RR_JOIN
   SET cn.low_absolute = CASE WHEN rr.rr_low IS NOT NULL
                              THEN GREATEST(COALESCE(cn.low_absolute, rr.rr_low), rr.rr_low)
                              ELSE cn.low_absolute END,
       cn.hi_absolute  = CASE WHEN rr.rr_hi IS NOT NULL
                              THEN LEAST(COALESCE(cn.hi_absolute, rr.rr_hi), rr.rr_hi)
                              ELSE cn.hi_absolute END
 WHERE $TOO_WIDE;" \
  || { echo "❌ Could not clamp ConceptNumeric bounds." >&2; exit 1; }

# Now prove it, and prove it NON-VACUOUSLY. The divergence count alone is worthless on its own: with
# no concept_reference_range rows loaded there is nothing for a ConceptNumeric to diverge FROM, so the
# count is 0 and a clamp that updated nothing reports success. That is reachable - initializer 2.12.0's
# Domain enum orders CONCEPT_REFERENCE_RANGE after CONCEPTS, so a clamp run early in a boot can land
# before there is anything to clamp against - and it is silent, which is the one failure shape a
# producer must not have. Hence both numbers: reference ranges must exist, AND nothing may still
# exceed them. The ids come back too, because the caller is about to lose this database (see below)
# and a bare count would leave nothing to act on.
COUNTS=$(mysql_exec -N -B -e "
SELECT (SELECT COUNT(*) FROM concept_reference_range),
       (SELECT COUNT(*) FROM concept_numeric cn $RR_JOIN WHERE $TOO_WIDE),
       (SELECT COALESCE(GROUP_CONCAT(cn.concept_id ORDER BY cn.concept_id), '-')
          FROM concept_numeric cn $RR_JOIN WHERE $TOO_WIDE);") || {
    echo "❌ Could not query the database to verify the clamp. The UPDATE above may or may not have" >&2
    echo "   applied, so this database is not safe to dump." >&2
    exit 1
}
read -r RR_ROWS DIVERGENT OFFENDERS <<<"$COUNTS"

# Every failure here is terminal for the run, not something to retry in place: both callers exit on a
# non-zero status and their EXIT trap runs `docker compose down -v`, which deletes the db-data volume.
# So the advice has to be about the NEXT boot - there is no waiting, re-running or inspecting after
# this returns, and 14 minutes are already spent.
[ "${RR_ROWS:-0}" -gt 0 ] || {
    echo "❌ concept_reference_range is empty, so the clamp had nothing to clamp against and this" >&2
    echo "   check proves nothing. Two causes, and which one depends on the caller:" >&2
    echo "   - called too early in a boot, before initializer's conceptreferencerange domain ran" >&2
    echo "     (generate-demo-data-locally.sh clamps mid-boot, so wait on more than concepts);" >&2
    echo "   - the domain never loaded at all. Those CSVs ship in the referenceapplication-demo" >&2
    echo "     content package, so check strip-demo-fixtures.sh has not started removing them" >&2
    echo "     (generate-empty-db-locally.sh clamps after two full boots, so this is the likely one)." >&2
    exit 1
}
[ "${DIVERGENT:-1}" = "0" ] || {
    echo "❌ ${DIVERGENT:-?} ConceptNumeric row(s) still exceed their reference-range intersection" >&2
    echo "   after the clamp: concept_id ${OFFENDERS:-unknown}." >&2
    echo "   Demo generation would abort part-way, so this database is not safe to dump. The clamp" >&2
    echo "   only narrows bounds it can compute, so a row surviving it means that concept's reference" >&2
    echo "   ranges disagree among themselves (MIN hi below MAX low) - fix the CSV, then re-run." >&2
    exit 1
}
echo "✅ ConceptNumeric is within its reference-range intersection ($RR_ROWS reference range(s) checked)."
