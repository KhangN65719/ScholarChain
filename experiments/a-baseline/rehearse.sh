#!/usr/bin/env bash
# Rehearsal of the Experiment A table script on SYNTHETIC fixture files. No AWS, no
# network. Runs derive-table.py without and with MapReduce counter files and checks the
# tables with grep. Exit 0 and "REHEARSAL pass=<n> fail=0" mean the script behaves as
# specified; it says nothing about a real run.
#
# Usage: bash experiments/a-baseline/rehearse.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIX="$HERE/fixtures/synthetic-run"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sc-a-rehearse.XXXXXX")"
PASS=0
FAIL=0

check() {
  local name="$1" file="$2" pattern="$3"
  if grep -q -F -- "$pattern" "$file"; then
    PASS=$((PASS + 1))
    printf 'PASS %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL %s (expected to find: %s in %s)\n' "$name" "$pattern" "$file"
  fi
}

python3 -I "$HERE/derive-table.py" --reports-dir "$FIX/reports" --pipeline-record "$FIX/pipeline-record.env" --out-dir "$TMP_ROOT/plain"
python3 -I "$HERE/derive-table.py" --reports-dir "$FIX/reports" --pipeline-record "$FIX/pipeline-record.env" --counters-dir "$FIX/counters" --out-dir "$TMP_ROOT/counters"

python3 -I "$HERE/derive-table.py" --reports-dir "$FIX/reports" --pipeline-record "$FIX/pipeline-record.env" --spark-eventlog-dir "$FIX/eventlog" --out-dir "$TMP_ROOT/eventlog"

E="$TMP_ROOT/eventlog/fields.md"
check eventlog.tasks "$E" '| Spark tasks (event log task-end events) | 3 |'
check eventlog.failed "$E" '| Spark tasks not successful (event log) | 1 |'
check eventlog.shuffle-write "$E" '| Spark shuffle write bytes (event log) | 300 |'
check eventlog.shuffle-read "$E" '| Spark shuffle read bytes, remote + local (event log) | 75 |'

T="$TMP_ROOT/plain/fields.md"
check plain.tasks-finished "$T" '| Spark tasks finished (spark application) | 3 |'
check plain.tasks-started "$T" '| Spark tasks started (spark application) | 4 |'
check plain.stages "$T" '| Spark stages finished (spark application) | 2 |'
check plain.jobs "$T" '| Spark jobs finished (spark application) | 1 |'
check plain.shuffles "$T" '| Spark shuffle dependencies registered (spark application) | 1 |'
check plain.executors "$T" '| Spark executors registered (spark application) | 2 |'
check plain.failures "$T" '| Spark failure lines (spark application) | 1 |'
check plain.export-tasks "$T" '| Spark tasks finished (export application) | 1 |'
check plain.mr-jobs "$T" '| MapReduce jobs (applications) | 3 |'
check plain.er-candidates "$T" '["100", "90", "10"]'
check plain.cost "$T" '"estimated_spend_usd": "0.90"'
check plain.mr-shuffle-not-recorded "$T" '| MapReduce shuffle bytes (Reduce shuffle bytes) | - | - | NOT RECORDED |'
check plain.spark-shuffle-not-recorded "$T" '| Spark shuffle read and write bytes | - | - | NOT RECORDED |'
check plain.input-records-not-recorded "$T" '| input records (per input file) | - | - | NOT RECORDED |'
check plain.sha256-listed "$T" 'reports/run-record.json'

C="$TMP_ROOT/counters/fields.md"
check counters.map-tasks "$C" '| MapReduce counter: Launched map tasks (sum over 2 job file(s)) | 5 |'
check counters.shuffle-bytes "$C" '| MapReduce counter: Reduce shuffle bytes (sum over 2 job file(s)) | 1500 |'
check counters.shuffled-maps "$C" '| MapReduce counter: Shuffled Maps (sum over 1 job file(s)) | 2 |'
check counters.missing-name "$C" '| MapReduce counter: Map input records | - | counters-dir | NOT RECORDED |'

# ---- R5 / M4: input records from the "Map input records" counter, output records from the
# manifest copies (reports/spark-run-manifest.json, reports/export-manifest.json)
check_absent() {
  local name="$1" file="$2" pattern="$3"
  if grep -q -F -- "$pattern" "$file"; then
    FAIL=$((FAIL + 1))
    printf 'FAIL %s (must NOT contain: %s in %s)\n' "$name" "$pattern" "$file"
  else
    PASS=$((PASS + 1))
    printf 'PASS %s\n' "$name"
  fi
}

REC="$HERE/fixtures/synthetic-run-records"
python3 -I "$HERE/derive-table.py" --reports-dir "$REC/reports" --pipeline-record "$FIX/pipeline-record.env" --counters-dir "$REC/counters" --out-dir "$TMP_ROOT/records"
python3 -I "$HERE/derive-table.py" --reports-dir "$REC/reports" --pipeline-record "$FIX/pipeline-record.env" --out-dir "$TMP_ROOT/records-nocounters"
python3 -I "$HERE/derive-table.py" --reports-dir "$REC/reports" --pipeline-record "$FIX/pipeline-record.env" --counters-dir "$REC/counters-partial" --out-dir "$TMP_ROOT/records-partial"

R="$TMP_ROOT/records/fields.md"
check records.input-records-raw-jobs "$R" "| input records (MapReduce 'Map input records' of the raw-input jobs) | 90 |"
check records.input-records-jobs-named "$R" 'job_1_0001=dblp-extraction, job_1_0002=source-normalization/crossref, job_1_0003=source-normalization/openalex'
check_absent records.intermediate-job-not-in-input-records "$R" 'job_1_0004=identifier-join, '
check records.per-job "$R" '| Map input records per job | {"job_1_0001": 40, "job_1_0002": 30, "job_1_0003": 20, "job_1_0004": 70} |'
check records.sum-all-jobs-labelled "$R" '| MapReduce counter: Map input records (sum over 4 job file(s)) | 160 |'
check records.sum-all-jobs-warning "$R" 'NOT the input record count'
check_absent records.no-not-recorded-input-row "$R" '| input records (per input file) |'
check records.input-lines-are-line-counts "$R" '| input lines (4 inputs, `wc -l`: line counts only, NOT record counts) |'
check records.input-lines-values "$R" '"INPUT_LINES_dblp_subset_xml": "900"'
check records.export-rows-per-table "$R" '| output records (export tables, rows per table) | {"edges-coauthored": 250, "nodes-researcher": 80, "nodes-work": 120} |'
check records.export-total "$R" '| output records (export tables, total rows) | 450 |'
check records.spark-manifest-total "$R" '| graph rows (Spark run-manifest tables, total rows) | 127 |'
check records.spark-manifest-rows-per-table "$R" '| graph rows (Spark run-manifest tables, rows per table) | {"components": 7, "nodes-work": 120} |'
check records.spark-manifest-source-shape "$R" 'reports/spark-run-manifest.json outputs[].table / outputs[].rows'
check records.manifest-sha256-listed "$R" 'reports/export-manifest.json'
check_absent records.output-not-recorded-absent "$R" '| output records (export tables, graph rows) |'

RN="$TMP_ROOT/records-nocounters/fields.md"
check records-nocounters.input-records-not-recorded "$RN" '| input records (per input file) | - | - | NOT RECORDED |'
check records-nocounters.input-lines-still-line-counts "$RN" '| input lines (4 inputs, `wc -l`: line counts only, NOT record counts) |'

RP="$TMP_ROOT/records-partial/fields.md"
check records-partial.input-records-not-recorded "$RP" "| input records (MapReduce 'Map input records' of the raw-input jobs) | - | counters-dir + reports/mapreduce-job-ids.txt | NOT RECORDED |"
check records-partial.names-missing-jobs "$RP" "no 'Map input records' counter for: job_1_0002, job_1_0003"

# N1: the old guessed `tables` shape is not the real Spark run-manifest shape and must not be read as rows
LEGACY="$TMP_ROOT/legacy-reports"
cp -R "$REC/reports" "$LEGACY"
cat > "$LEGACY/spark-run-manifest.json" <<'JSON'
{
  "_synthetic_fixture": "SYNTHETIC. The OLD guessed shape (tables list), not the real writer shape.",
  "tables": [{"name": "nodes-work", "rows": 120}, {"name": "components", "rows": 7}]
}
JSON
python3 -I "$HERE/derive-table.py" --reports-dir "$LEGACY" --pipeline-record "$FIX/pipeline-record.env" --out-dir "$TMP_ROOT/legacy"
LG="$TMP_ROOT/legacy/fields.md"
check legacy-tables-shape.not-recorded "$LG" '| graph rows (Spark run-manifest tables) | - | - | NOT RECORDED |'
check_absent legacy-tables-shape.no-total "$LG" '| graph rows (Spark run-manifest tables, total rows) |'
check_absent legacy-tables-shape.no-per-table "$LG" '| graph rows (Spark run-manifest tables, rows per table) |'

check plain.output-records-not-recorded "$T" '| output records (export tables, graph rows) | - | - | NOT RECORDED |'
check plain.spark-manifest-not-recorded "$T" '| graph rows (Spark run-manifest tables) | - | - | NOT RECORDED |'

if python3 -I "$HERE/derive-table.py" --reports-dir "$FIX/reports" --pipeline-record "$FIX/pipeline-record.env" --out-dir "$TMP_ROOT/plain" >/dev/null 2>&1; then
  FAIL=$((FAIL + 1))
  printf 'FAIL the table script overwrote an existing output directory\n'
else
  PASS=$((PASS + 1))
  printf 'PASS an existing output directory is refused\n'
fi

printf 'OUTPUT_DIR=%s\n' "$TMP_ROOT"
printf 'REHEARSAL pass=%s fail=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
