# shellcheck shell=bash
# Shared functions of the on-node EMR scripts (capability-check.sh and
# run-s-pipeline.sh). Source it, never run it. These scripts run on the EMR primary
# node as the hadoop user, started by a command-runner.jar step. Nothing here runs on
# the operator's machine, and nothing here was executed when it was written.
#
# Contract:
#   - bash strict mode; greppable markers STAGE[n] name: start|ok|FAIL
#   - KEY=VALUE result lines (printed by kv) that the caller also writes to a JSON file
#   - credentials are never read, printed or put in an argument: AWS access comes from
#     the instance profile through the AWS CLI. No script prints the environment, runs
#     with set -x, or passes --sse-c; uploads use the bucket's default encryption
#   - region is us-east-2 only (the account is locked to it, Phase 14 D1)
#
# Written for bash 4 or later on the node (Amazon Linux). Not for macOS.

set -euo pipefail

SC_HOME="${SC_HOME:-/home/hadoop/sc}"
SC_REGION="us-east-2"
export AWS_DEFAULT_REGION="$SC_REGION"
export AWS_REGION="$SC_REGION"
export AWS_PAGER=""

SPARK_VERSION="4.0.5"
SPARK_DIST="spark-${SPARK_VERSION}-bin-hadoop3"
SPARK_TARBALL="${SPARK_DIST}.tgz"
SPARK_ARCHIVE_NAME="spark-${SPARK_VERSION}-jars.zip"
SPARK_SUMS_NAME="spark-${SPARK_VERSION}.sha256"
SPARK_KEY_PREFIX="jars/spark-${SPARK_VERSION}"
HDFS_SPARK_DIR="/user/hadoop/sc/spark-${SPARK_VERSION}"

SC_SECRET_RE='AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----|aws_secret_''access_key|[Aa]uthorization:[[:space:]]*[Bb]earer|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}'

# Compression codecs handed to every Hadoop and Spark client that reads text input
# through the Hadoop InputFormat. EMR's core-site.xml lists the LZO codec classes
# (com.hadoop.compression.lzo.*), which the job class path does not carry, so the first
# CompressionCodecFactory the client builds fails with "Compression codec ... not found"
# (S run 1, Phase 14 D29; the cause is UNVERIFIED until the next S run). Our inputs and
# outputs are plain XML, JSONL, Parquet and text: no LZO and no Snappy (it needs native
# libraries) is wanted. Commas are part of the value, so it cannot live in the
# KEY=VALUE spark-yarn.conf.
SC_CODECS="org.apache.hadoop.io.compress.DefaultCodec,org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.BZip2Codec"
# Map-output codec for the MapReduce jobs: zlib needs no native library. The jobs set
# mapreduce.map.output.compress=true themselves (JobConfig.enableIntermediateCompression)
# and keep a codec that is already configured, so this -D is what replaces whatever
# EMR's mapred-site names (for example Snappy).
# shellcheck disable=SC2034  # read by run-s-pipeline.sh, which sources this file
SC_MAP_CODEC="org.apache.hadoop.io.compress.DefaultCodec"
# fs.permissions.umask-mode for the Hadoop command and the Spark applications: see
# write_spark_conf. Only the throwaway run directory is written through file: URIs.
SC_FS_UMASK="000"

SC_STAGE_N=0
SC_STAGE_NAME="init"
SC_STAGE_OPEN=0
SC_KV=""
# Elapsed-time bookkeeping (bash's SECONDS counter): the script start, and the start of
# the open stage. Every stage records STAGE_SECONDS_<NAME>, which sizes the step cap.
SC_T0=$SECONDS
SC_STAGE_T0=$SECONDS
# A checkpoint file larger than this is uploaded as its last SC_PROGRESS_MAX_BYTES bytes.
SC_PROGRESS_MAX_BYTES=2097152
# The whole checkpoint upload (every file together) gets at most this many seconds.
SC_PROGRESS_BUDGET_SECONDS=60
# The whole failure-path publish (sc_publish_failed_logs, every file together) gets at
# most this many seconds, a shared deadline; each upload call gets the smaller of 300 s
# and the time left. Override with SC_FAILED_PUBLISH_BUDGET_SECONDS (a whole number with no leading zero, else 600; a leading zero would be read as octal in the arithmetic).
SC_FAILED_PUBLISH_BUDGET_SECONDS="${SC_FAILED_PUBLISH_BUDGET_SECONDS:-600}"

# Set by register_failure_logs once a run has a reports directory: where sc_on_exit
# publishes the logs when a stage fails.
SC_FAIL_REP=""
SC_FAIL_DATA_BUCKET=""
SC_FAIL_RUN_ID=""

# Java and Spark state set by find_java, stage_spark and write_spark_conf.
SC_JAVA_HOME=""
SC_JAVA_MAJOR=""
SC_JAVA_SOURCE="none"
SPARK_HOME=""
SC_ARCHIVE_URI=""
SC_ARCHIVE_MODE="none"
SC_SPARK_CONF_DIR="$SC_HOME/spark-conf"

### Markers ------------------------------------------------------------------

stage() {
  SC_STAGE_N=$((SC_STAGE_N + 1))
  SC_STAGE_NAME="$1"
  SC_STAGE_OPEN=1
  SC_STAGE_T0=$SECONDS
  printf 'STAGE[%s] %s: start\n' "$SC_STAGE_N" "$SC_STAGE_NAME"
}

# stage_key: the open stage's name as a record key part (spark-stage -> SPARK_STAGE).
stage_key() {
  printf '%s' "$SC_STAGE_NAME" | tr 'a-z-' 'A-Z_'
}

# record_total_seconds KEY: KEY=<seconds since the script started>.
record_total_seconds() {
  kv "$1" "$((SECONDS - SC_T0))"
}

# stage_ok: the marker line is unchanged (the harness and the logs grep it); the elapsed
# seconds of the stage follow as STAGE_SECONDS_<NAME>=<n>. After a stage of a registered
# run (see register_failure_logs) a progress checkpoint is published, best effort, so a
# kill from outside (the state machine's step timeout) still leaves the evidence up to
# the last finished stage. The publish stage is the end of the run and is not checkpointed.
stage_ok() {
  local elapsed=$((SECONDS - SC_STAGE_T0))
  printf 'STAGE[%s] %s: ok\n' "$SC_STAGE_N" "$SC_STAGE_NAME"
  SC_STAGE_OPEN=0
  kv "STAGE_SECONDS_$(stage_key)" "$elapsed"
  if [[ "$SC_STAGE_NAME" != "publish" ]]; then
    sc_publish_progress "after-stage-$SC_STAGE_N-$SC_STAGE_NAME" || true
  fi
}

sc_on_exit() {
  local rc=$?
  trap - EXIT
  set +e
  if [[ $rc -ne 0 ]]; then
    if [[ $SC_STAGE_OPEN -eq 1 ]]; then
      printf 'STAGE[%s] %s: FAIL (exit %s)\n' "$SC_STAGE_N" "$SC_STAGE_NAME" "$rc"
      kv "STAGE_SECONDS_$(stage_key)" "$((SECONDS - SC_STAGE_T0)) (stage failed)"
    fi
    record_total_seconds STAGE_SECONDS_TOTAL_AT_FAILURE
    if [[ -n "$SC_FAIL_REP" ]]; then
      kv FAILED_STAGE "$SC_STAGE_N $SC_STAGE_NAME"
      kv FAILED_EXIT_CODE "$rc"
      sc_publish_failed_logs || true
    fi
  fi
  exit "$rc"
}

# register_failure_logs REPORTS_DIR DATA_BUCKET RUN_ID: from here on a failing run
# publishes REPORTS_DIR to s3://DATA_BUCKET/reports/runs/RUN_ID/failed/<UTC stamp>/.
register_failure_logs() {
  SC_FAIL_REP="$1"
  SC_FAIL_DATA_BUCKET="$2"
  SC_FAIL_RUN_ID="$3"
}

# sc_publish_failed_logs: best effort, called from the exit trap of a failed run. Writes
# the key=value lines so far to run-record.env, then copies each regular file of the
# reports directory (hadoop.log, er.log, spark.log, export.log, mapreduce-yarn-list.log,
# whatever exists) to the failed/<stamp>/ prefix. A file that matches the key-material
# patterns is skipped and its content is never printed. No sync, no delete. Every error
# here is reported and swallowed, so the original exit code stands. The emr_ec2 role may
# write reports/* (infra/terraform/modules/identity, WriteJobOutputs).
# Time budget: one shared deadline of SC_FAILED_PUBLISH_BUDGET_SECONDS (default 600) for
# the whole publish; each upload call gets the smaller of 300 s and the time left. Once the
# budget is spent the remaining files are not uploaded: each is named in a
# FAILED_LOGS_SKIPPED ... reason=time-budget-spent line. run-record.env goes first, so the
# key=value record is the file a spent budget is least likely to cost.
sc_publish_failed_logs() {
  local rep="$SC_FAIL_REP" bucket="$SC_FAIL_DATA_BUCKET" run="$SC_FAIL_RUN_ID"
  local stamp dest f name up=0 skipped=0 failed=0 budget_skipped=0 budget deadline remaining per files=()
  [[ -n "$rep" && -n "$bucket" && -n "$run" && -d "$rep" ]] || return 0
  stamp="$(date -u +%Y%m%dT%H%M%SZ)" || return 0
  dest="s3://$bucket/reports/runs/$run/failed/$stamp/"
  printf '%s' "$SC_KV" > "$rep/run-record.env" 2>/dev/null || true
  budget="$SC_FAILED_PUBLISH_BUDGET_SECONDS"
  [[ "$budget" =~ ^(0|[1-9][0-9]*)$ ]] || budget=600
  deadline=$((SECONDS + budget))
  files=("$rep/run-record.env")
  for f in "$rep"/*; do
    [[ -f "$f" ]] || continue
    [[ "${f##*/}" != "run-record.env" ]] || continue
    files+=("$f")
  done
  for f in "${files[@]}"; do
    [[ -f "$f" ]] || continue
    name="${f##*/}"
    if grep -qE "$SC_SECRET_RE" "$f" 2>/dev/null; then
      skipped=$((skipped + 1))
      printf 'FAILED_LOGS_SKIPPED file=%s reason=key-material-pattern (content not printed)\n' "$name"
      continue
    fi
    remaining=$((deadline - SECONDS))
    if [[ "$remaining" -le 0 ]]; then
      budget_skipped=$((budget_skipped + 1))
      printf 'FAILED_LOGS_SKIPPED file=%s reason=time-budget-spent (budget %s s)\n' "$name" "$budget"
      continue
    fi
    per=300
    if [[ "$remaining" -lt "$per" ]]; then
      per="$remaining"
    fi
    if timeout "$per" aws s3 cp "$f" "$dest$name" --only-show-errors; then
      up=$((up + 1))
    else
      failed=$((failed + 1))
    fi
  done
  if [[ "$up" -gt 0 ]]; then
    printf 'FAILED_LOGS_PUBLISHED=%s\n' "$dest"
  fi
  printf 'NOTE: failed-log publish: %s file(s) uploaded, %s skipped (key-material pattern), %s failed\n' "$up" "$skipped" "$failed"
  if [[ "$budget_skipped" -gt 0 ]]; then
    printf 'NOTE: failed-log publish: %s file(s) not uploaded, time budget of %s s spent\n' "$budget_skipped" "$budget"
  fi
  return 0
}

# sc_publish_progress LABEL: best-effort checkpoint of a run in progress (work order 48),
# to s3://<data>/reports/runs/<run-id>/progress/. The prefix holds the LATEST checkpoint:
# the same keys are overwritten each time (the bucket has versioning off), so after a
# kill from outside, which skips the exit trap, it shows the state at the last finished
# stage. Files: run-record.env (the key=value lines so far), progress.env (which
# checkpoint, when, seconds elapsed) and every regular file of the reports directory
# that exists (hadoop.log, er.log, mapreduce-*.log and so on). A file larger than
# SC_PROGRESS_MAX_BYTES is uploaded as its last SC_PROGRESS_MAX_BYTES bytes under the
# name <file>.tail. The large outputs (Hadoop, ER, Spark and export results) are never
# part of it. The same key-material scan as the failure path: a file that matches is
# skipped and its content is never printed. One aws s3 cp per file, no recursive copy
# and no sync, no delete; the whole upload gets at most SC_PROGRESS_BUDGET_SECONDS, each
# call under timeout. Every error is reported and swallowed: it returns 0 always, so it
# can never abort the run. It does nothing before register_failure_logs ran (the
# capability check never registers).
sc_publish_progress() {
  local label="${1:-checkpoint}" rep="$SC_FAIL_REP" bucket="$SC_FAIL_DATA_BUCKET" run="$SC_FAIL_RUN_ID"
  local dest f name src tmp sz remaining deadline up=0 skipped=0 failed=0 files=()
  [[ -n "$rep" && -n "$bucket" && -n "$run" && -d "$rep" ]] || return 0
  dest="s3://$bucket/reports/runs/$run/progress/"
  printf '%s' "$SC_KV" > "$rep/run-record.env" 2>/dev/null || true
  {
    printf 'CHECKPOINT=%s\n' "$label"
    printf 'STAGE=%s %s open=%s\n' "$SC_STAGE_N" "$SC_STAGE_NAME" "$SC_STAGE_OPEN"
    printf 'UPDATED_UTC=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"
    printf 'ELAPSED_SECONDS=%s\n' "$((SECONDS - SC_T0))"
    printf 'NOTE=latest checkpoint only; the final record is under reports/runs/%s/ when the run ends\n' "$run"
  } > "$rep/progress.env" 2>/dev/null || true
  files=("$rep/run-record.env" "$rep/progress.env")
  for f in "$rep"/*; do
    [[ -f "$f" ]] || continue
    name="${f##*/}"
    [[ "$name" != "run-record.env" && "$name" != "progress.env" ]] || continue
    files+=("$f")
  done
  deadline=$((SECONDS + SC_PROGRESS_BUDGET_SECONDS))
  for f in "${files[@]}"; do
    [[ -f "$f" ]] || continue
    name="${f##*/}"
    src="$f"
    tmp=""
    sz="$(wc -c < "$f" 2>/dev/null | tr -d ' ')" || sz=0
    if [[ "${sz:-0}" -gt "$SC_PROGRESS_MAX_BYTES" ]]; then
      tmp="$(mktemp 2>/dev/null)" || tmp=""
      if [[ -z "$tmp" ]]; then
        skipped=$((skipped + 1))
        continue
      fi
      tail -c "$SC_PROGRESS_MAX_BYTES" "$f" > "$tmp" 2>/dev/null || true
      src="$tmp"
      name="$name.tail"
    fi
    if grep -qE "$SC_SECRET_RE" "$src" 2>/dev/null; then
      skipped=$((skipped + 1))
      printf 'PROGRESS_SKIPPED file=%s reason=key-material-pattern (content not printed)\n' "$name"
    else
      remaining=$((deadline - SECONDS))
      if [[ "$remaining" -gt 0 ]]; then
        if timeout "$remaining" aws s3 cp "$src" "$dest$name" --only-show-errors; then
          up=$((up + 1))
        else
          failed=$((failed + 1))
        fi
      else
        failed=$((failed + 1))
      fi
    fi
    if [[ -n "$tmp" ]]; then
      rm -f "$tmp" 2>/dev/null || true
    fi
  done
  if [[ "$up" -gt 0 ]]; then
    printf 'PROGRESS_PUBLISHED=%s checkpoint=%s\n' "$dest" "$label"
  fi
  printf 'NOTE: progress checkpoint %s: %s file(s) uploaded, %s skipped, %s failed\n' "$label" "$up" "$skipped" "$failed"
  return 0
}

# Call once at the top of main: a trap set at file level is not seen inside the
# subshell that a pipeline gives main.
install_trap() {
  trap sc_on_exit EXIT
}

die() {
  printf 'EVIDENCE: %s\n' "$*" >&2
  exit 1
}

note() {
  printf 'NOTE: %s\n' "$*"
}

# clean_text TEXT: one line, control characters removed, at most 400 characters.
clean_text() {
  printf '%s' "$1" | tr '\n\r\t' '   ' | tr -d '\000-\010\013\014\016-\037' | cut -c1-400
}

# kv KEY VALUE: print KEY=VALUE and remember it for write_json.
kv() {
  local v
  v="$(clean_text "$2")"
  printf '%s=%s\n' "$1" "$v"
  SC_KV="${SC_KV}${1}=${v}"$'\n'
}

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# write_json FILE: every kv line as one string member.
write_json() {
  local f="$1" line key val first=1
  {
    printf '{\n'
    while IFS= read -r line; do
      [[ -n "$line" ]] || continue
      key="${line%%=*}"
      val="${line#*=}"
      if [[ "$first" -eq 1 ]]; then first=0; else printf ',\n'; fi
      printf '  "%s": "%s"' "$(json_escape "$key")" "$(json_escape "$val")"
    done <<EOF
$SC_KV
EOF
    printf '\n}\n'
  } > "$f"
}

### Validation ---------------------------------------------------------------

validate_id() {
  local name="$1" value="$2"
  [[ "$value" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "$name must be 1 to 64 characters of A-Za-z0-9._-"
  [[ "$value" != "." && "$value" != ".." ]] || die "$name must not be . or .."
}

# validate_run_id VALUE: the run id argument must be the path-safe slug of a stable run
# identifier, run-v1-<lower-case canonical UUID> (43 characters). The Hadoop jobs build
# provenance from -Dscholarchain.run.id and core's StableIds refuses anything but
# run:v1:<uuid> (S run 2, D29), so a free-form id is refused here, before any stage runs.
# Sets SC_STABLE_RUN_ID to run:v1:<uuid>. Call it directly, not in a command substitution,
# so the variable survives. The slug stays the S3 prefix component, the work directory
# name and the loader argument; only -Dscholarchain.run.id gets the stable form.
SC_RUN_SLUG_RE='^run-v1-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$'
# shellcheck disable=SC2034  # read by run-s-pipeline.sh, which sources this file
SC_STABLE_RUN_ID=""
validate_run_id() {
  local value="$1"
  [[ "$value" =~ $SC_RUN_SLUG_RE ]] || die "run-id must be exactly run-v1- followed by a lower-case canonical UUID (8-4-4-4-12 hex, 43 characters), for example run-v1-3f2b8c1e-5a47-4d0e-9b6a-1c2d3e4f5a6b; make one with: printf 'run-v1-%s' \"\$(uuidgen | tr 'A-Z' 'a-z')\""
  # shellcheck disable=SC2034  # read by run-s-pipeline.sh, which sources this file
  SC_STABLE_RUN_ID="run:v1:${BASH_REMATCH[1]}"
}

validate_bucket() {
  local name="$1" value="$2"
  [[ "$value" =~ ^[a-z0-9][a-z0-9.-]{2,62}$ ]] || die "$name is not a bucket name"
}

validate_snapshots() {
  [[ "$1" =~ ^[A-Za-z0-9._=,:-]{1,300}$ ]] || die "snapshots must be 1 to 300 characters of A-Za-z0-9._=,:-"
}

# scan_no_secrets FILE: refuse to upload a file in which key material patterns match.
# The matched text is never printed.
scan_no_secrets() {
  if grep -qE "$SC_SECRET_RE" "$1"; then
    die "possible credential material in $1: not uploading (matched text is not printed)"
  fi
}

### Java ---------------------------------------------------------------------

# java_major BIN: the major version of a java binary (8, 11, 17, 21), or fail.
java_major() {
  local out v
  out="$("$1" -version 2>&1)" || return 1
  v="$(printf '%s\n' "$out" | awk -F'"' '/version "/ { print $2; exit }')"
  [[ -n "$v" ]] || return 1
  case "$v" in
    1.*) v="${v#1.}" ;;
  esac
  printf '%s' "${v%%[.+_-]*}"
}

# find_java: the first JDK of major version 17 or later, looked for in the file the
# optional bootstrap writes (/etc/scholarchain/java.env), then JAVA_HOME, then
# /usr/lib/jvm. Sets SC_JAVA_HOME, SC_JAVA_MAJOR and SC_JAVA_SOURCE; SC_JAVA_HOME is
# empty when none is found. Spark 4 needs 17 or later.
# shellcheck disable=SC2034  # SC_JAVA_MAJOR and SC_JAVA_SOURCE are read by the sourcing scripts
find_java() {
  local cand="" m d src
  SC_JAVA_HOME=""
  SC_JAVA_MAJOR=""
  SC_JAVA_SOURCE="none"
  if [[ -r /etc/scholarchain/java.env ]]; then
    cand="$(awk -F= '$1 == "JAVA_HOME" { print $2; exit }' /etc/scholarchain/java.env)"
    src="bootstrap-env-file"
    if [[ -n "$cand" && -x "$cand/bin/java" ]] && m="$(java_major "$cand/bin/java")" && [[ "$m" -ge 17 ]]; then
      SC_JAVA_HOME="$cand"; SC_JAVA_MAJOR="$m"; SC_JAVA_SOURCE="$src"; return 0
    fi
  fi
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] && m="$(java_major "${JAVA_HOME}/bin/java")" && [[ "$m" -ge 17 ]]; then
    SC_JAVA_HOME="$JAVA_HOME"; SC_JAVA_MAJOR="$m"; SC_JAVA_SOURCE="JAVA_HOME"; return 0
  fi
  for d in /usr/lib/jvm/*; do
    [[ -x "$d/bin/java" ]] || continue
    if m="$(java_major "$d/bin/java")" && [[ "$m" -ge 17 ]]; then
      SC_JAVA_HOME="$d"; SC_JAVA_MAJOR="$m"; SC_JAVA_SOURCE="/usr/lib/jvm"; return 0
    fi
  done
  return 0
}

### Spark 4 distribution -----------------------------------------------------

# conf_value KEY DEFAULT PATTERN: the value of KEY in $SC_HOME/spark-yarn.conf (a
# KEY=VALUE file, never sourced), or DEFAULT. A value that does not match PATTERN
# is refused.
conf_value() {
  local key="$1" default="$2" pattern="$3" f="$SC_HOME/spark-yarn.conf" v=""
  if [[ -r "$f" ]]; then
    v="$(awk -F= -v k="$key" '/^[[:space:]]*(#|$)/ { next } $1 == k { sub(/^[^=]*=/, ""); print; exit }' "$f")"
  fi
  [[ -n "$v" ]] || v="$default"
  [[ "$v" =~ $pattern ]] || die "spark-yarn.conf: $key has an invalid value"
  printf '%s' "$v"
}

# stage_spark ARTIFACTS_BUCKET: copy the Spark 4 tarball, the jars archive and the
# checksum file from the artifacts bucket, verify them, extract the tarball, and put
# the jars archive where spark.yarn.archive can read it. Sets SPARK_HOME,
# SC_ARCHIVE_URI and SC_ARCHIVE_MODE (hdfs, file or none).
#
# Why HDFS for the archive: the Spark 4 client from the Apache tarball has no EMRFS
# and no hadoop-aws, so it cannot read an s3:// archive path (UNVERIFIED, from the
# tarball contents). A file: path works only while every container is on the node that
# holds the file, and the client then copies it to the staging directory on every
# submit. HDFS (the default filesystem of the cluster) works on one node and on many,
# and the client does not copy an archive that already sits on the default filesystem.
# A file: path is the fallback when the HDFS put fails, and the mode is reported.
stage_spark() {
  local bucket="$1" dl="$SC_HOME/spark-dl" rc=0
  mkdir -p "$dl"
  aws s3 cp "s3://$bucket/$SPARK_KEY_PREFIX/$SPARK_SUMS_NAME" "$dl/$SPARK_SUMS_NAME" --only-show-errors
  aws s3 cp "s3://$bucket/$SPARK_KEY_PREFIX/$SPARK_TARBALL" "$dl/$SPARK_TARBALL" --only-show-errors
  aws s3 cp "s3://$bucket/$SPARK_KEY_PREFIX/$SPARK_ARCHIVE_NAME" "$dl/$SPARK_ARCHIVE_NAME" --only-show-errors
  (cd "$dl" && sha256sum -c "$SPARK_SUMS_NAME") || die "sha256 check of the Spark distribution failed"
  kv SPARK_SHA256_VERIFIED yes
  rm -rf "${SC_HOME:?}/spark"
  mkdir -p "$SC_HOME/spark"
  tar -xzf "$dl/$SPARK_TARBALL" -C "$SC_HOME/spark" --no-same-owner
  SPARK_HOME="$SC_HOME/spark/$SPARK_DIST"
  [[ -x "$SPARK_HOME/bin/spark-submit" ]] || die "no spark-submit in the extracted distribution: $SPARK_HOME"
  kv SPARK_HOME "$SPARK_HOME"
  kv SPARK_JARS_COUNT "$(find "$SPARK_HOME/jars" -maxdepth 1 -name '*.jar' | wc -l | tr -d ' ')"
  chmod a+r "$dl/$SPARK_ARCHIVE_NAME"
  hdfs dfs -mkdir -p "$HDFS_SPARK_DIR" || rc=$?
  if [[ "$rc" -eq 0 ]]; then
    hdfs dfs -put -f "$dl/$SPARK_ARCHIVE_NAME" "$HDFS_SPARK_DIR/$SPARK_ARCHIVE_NAME" || rc=$?
  fi
  if [[ "$rc" -eq 0 ]]; then
    SC_ARCHIVE_MODE="hdfs"
    SC_ARCHIVE_URI="hdfs://$HDFS_SPARK_DIR/$SPARK_ARCHIVE_NAME"
  else
    SC_ARCHIVE_MODE="file"
    SC_ARCHIVE_URI="file://$dl/$SPARK_ARCHIVE_NAME"
    note "HDFS put failed (exit $rc): falling back to a file: archive path, which holds only while every container runs on this node"
  fi
  kv SPARK_ARCHIVE_MODE "$SC_ARCHIVE_MODE"
  kv SPARK_ARCHIVE_URI "$SC_ARCHIVE_URI"
}

# write_spark_conf POPULATE: write spark-defaults.conf in $SC_SPARK_CONF_DIR.
# POPULATE is the value of spark.yarn.populateHadoopClasspath (true or false).
write_spark_conf() {
  local populate="$1" dm em ec ei am
  dm="$(conf_value DRIVER_MEMORY 2g '^[0-9]+[gm]$')"
  em="$(conf_value EXECUTOR_MEMORY 3g '^[0-9]+[gm]$')"
  ec="$(conf_value EXECUTOR_CORES 2 '^[0-9]+$')"
  ei="$(conf_value EXECUTOR_INSTANCES 2 '^[0-9]+$')"
  am="$(conf_value AM_MEMORY 1g '^[0-9]+[gm]$')"
  mkdir -p "$SC_SPARK_CONF_DIR"
  {
    printf 'spark.yarn.archive %s\n' "$SC_ARCHIVE_URI"
    printf 'spark.yarn.populateHadoopClasspath %s\n' "$populate"
    printf 'spark.dynamicAllocation.enabled false\n'
    printf 'spark.driver.memory %s\n' "$dm"
    printf 'spark.yarn.am.memory %s\n' "$am"
    printf 'spark.executor.memory %s\n' "$em"
    printf 'spark.executor.cores %s\n' "$ec"
    printf 'spark.executor.instances %s\n' "$ei"
    printf 'spark.ui.enabled false\n'
    # EMR's core-site.xml lists the LZO codec, whose jar a Spark 4 container does not
    # have. Overriding the codec list avoids a class-not-found at startup (the problem
    # is known for Apache Spark on EMR Hadoop configuration, UNVERIFIED here). Same
    # value as the -Dio.compression.codecs of the Hadoop command (SC_CODECS).
    printf 'spark.hadoop.io.compression.codecs %s\n' "$SC_CODECS"
    # Directories and files that Hadoop file APIs create in the run directory get no
    # permission bits removed (default 022 would make a directory the driver creates
    # unwritable for executors that run as another user). Explicitly requested modes,
    # such as the 700 staging directory, are unaffected: a umask only removes bits.
    printf 'spark.hadoop.fs.permissions.umask-mode %s\n' "$SC_FS_UMASK"
    # The old mapred API (RDD saveAsTextFile, which JsonlExport uses for its JSON Lines
    # parts) reads its output committer from mapred.output.committer.class. S run 5 failed
    # in the export stage with "ClassNotFoundException: Class
    # org.apache.hadoop.mapred.DirectFileOutputCommitter not found": the class is EMR's own
    # (its mapred-site.xml names it, UNVERIFIED on the node: the failure names the class,
    # the file was not read) and our Spark 4.0.5 jars (the same set as the archive) do not
    # carry it. Hadoop's default
    # for this key is the committer below, so this line only restores the default and
    # changes nothing for the Dataset and Parquet writers, which use the new mapreduce API.
    # spark.hadoop.* is applied after the site files, so it wins over the node's value.
    printf 'spark.hadoop.mapred.output.committer.class %s\n' "org.apache.hadoop.mapred.FileOutputCommitter"
    if [[ -n "$SC_JAVA_HOME" ]]; then
      printf 'spark.yarn.appMasterEnv.JAVA_HOME %s\n' "$SC_JAVA_HOME"
      printf 'spark.executorEnv.JAVA_HOME %s\n' "$SC_JAVA_HOME"
    fi
  } > "$SC_SPARK_CONF_DIR/spark-defaults.conf"
}

# run_spark_submit LOG MODE CLASS APPJAR JARS_CSV [ARGS...]: spark-submit --master yarn
# from the Spark 4 distribution, with SPARK_HOME, SPARK_CONF_DIR and HADOOP_CONF_DIR
# set explicitly and EMR's own Hadoop and Spark class path variables removed. The
# output goes to LOG only. Returns the exit status (124 on the time limit).
# The time limit is SC_SPARK_TIMEOUT seconds, default 3000 (was 1500). S run 4 (Phase 14
# D32) was cut by the old 1500 s limit with about 70% of the roughly 5,850 Spark stages
# done, so the pipeline application needs about 2,100 to 2,400 s on this node. The limit
# applies to every call of this function, so it also bounds the export stage and each
# SparkPi of the capability check. It is a ceiling per call, not an expected duration;
# the Terraform step cap is the outer bound of the whole script. The limit sends TERM and,
# 30 s later, KILL (timeout -k 30), so a spark-submit that ignores TERM cannot outlive it.
# Per the coreutils manual, the exit status is 124 when the command ended on the TERM sent
# at the limit and 137 when it needed the KILL after the 30 s grace. Neither is observed on
# a node: S run 4 exited cleanly on TERM, so 124 has not been seen either.
run_spark_submit() {
  local log="$1" mode="$2" class="$3" appjar="$4" jars="$5"
  shift 5
  local cmd=("$SPARK_HOME/bin/spark-submit" --master yarn --deploy-mode "$mode" --class "$class")
  if [[ -n "$jars" ]]; then
    cmd+=(--jars "$jars")
  fi
  cmd+=("$appjar" "$@")
  env -u HADOOP_CLASSPATH -u SPARK_DIST_CLASSPATH -u SPARK_EXTRA_CLASSPATH \
    SPARK_HOME="$SPARK_HOME" SPARK_CONF_DIR="$SC_SPARK_CONF_DIR" \
    HADOOP_CONF_DIR=/etc/hadoop/conf YARN_CONF_DIR=/etc/hadoop/conf \
    JAVA_HOME="$SC_JAVA_HOME" \
    timeout -k 30 "${SC_SPARK_TIMEOUT:-3000}" "${cmd[@]}" > "$log" 2>&1
}

app_id_from_log() {
  grep -oE 'application_[0-9]+_[0-9]+' "$1" | tail -n 1 || true
}

# yarn_final_state APP_ID: SUCCEEDED, FAILED, KILLED, UNDEFINED or unknown. The query runs
# under timeout 120; when it times out (or fails) the pipeline status is non-zero, s is
# reset to empty and the result is unknown, which spark_verdict treats as not SUCCEEDED.
yarn_final_state() {
  local s
  s="$(timeout 120 yarn application -status "$1" 2>/dev/null | awk -F' : ' '/Final-State/ { gsub(/[[:space:]]/, "", $2); print $2; exit }')" || s=""
  printf '%s' "${s:-unknown}"
}

# spark_verdict LOG RC: judge one spark-submit run. Call it directly, never inside a
# command substitution: a subshell would lose the variables it sets. It prints nothing
# and always returns 0. It sets
#   SC_LAST_VERDICT  yes when spark-submit exited 0, a YARN application id appears in
#                    the log and YARN reports its final state SUCCEEDED; otherwise no
#   SC_LAST_APP      the last YARN application id in the log (empty when none)
#   SC_LAST_STATE    the YARN final state (empty when no query was made)
# The NativeCodeLoader openssl UnsatisfiedLinkError that the resource upload logs is
# non-fatal and plays no part here: the verdict reads only the exit status, the
# application id and the YARN final state.
SC_LAST_APP=""
SC_LAST_STATE=""
# shellcheck disable=SC2034  # SC_LAST_VERDICT is read by the sourcing scripts
SC_LAST_VERDICT="no"
# shellcheck disable=SC2034  # SC_LAST_VERDICT is read by the sourcing scripts
spark_verdict() {
  local log="$1" rc="$2"
  SC_LAST_APP="$(app_id_from_log "$log")"
  SC_LAST_STATE=""
  SC_LAST_VERDICT="no"
  if [[ "$rc" -eq 0 && -n "$SC_LAST_APP" ]]; then
    SC_LAST_STATE="$(yarn_final_state "$SC_LAST_APP")"
    if [[ "$SC_LAST_STATE" == "SUCCEEDED" ]]; then
      SC_LAST_VERDICT="yes"
    fi
  fi
  return 0
}

# last_error_line LOG: a short reason from the end of a log, for the report. The
# non-fatal NativeCodeLoader openssl UnsatisfiedLinkError (logged while Spark uploads
# its resources, both applications still finish) is skipped so it is never reported as
# the failure reason. Order: PipelineTool's own refusal line (ScholarChain pipeline not
# started: ..., which has none of the generic words), then the last generic error line,
# then the last three non-empty lines of the log joined with " / ". A line that matches
# the key-material patterns is replaced by a fixed text, never printed.
last_error_line() {
  local f="$1" l="" ex='UnsatisfiedLinkError|buildSupportsOpenssl'
  l="$(grep -E 'ScholarChain pipeline not started' "$f" 2>/dev/null | tail -n 1)" || l=""
  if [[ -z "$l" ]]; then
    l="$(grep -E 'Exception|Error|ERROR|FAILED' "$f" 2>/dev/null | grep -vE "$ex" | tail -n 1)" || l=""
  fi
  if [[ -z "$l" ]]; then
    l="$(grep -vE '^[[:space:]]*$' "$f" 2>/dev/null | grep -vE "$ex" | tail -n 3 | awk '{ printf "%s%s", (NR > 1 ? " / " : ""), $0 }')" || l=""
    if [[ -n "$l" ]]; then
      l="no error line; last non-empty lines: $l"
    fi
  fi
  if [[ -n "$l" ]] && grep -qE "$SC_SECRET_RE" <<< "$l"; then
    l="a log line matches a key-material pattern: not printed"
  fi
  clean_text "${l:-no error line found and the log is empty or unreadable}"
}

# first_job_id LOG: the first MapReduce job id (job_local... or job_<digits>_<digits>)
# in a log, or nothing.
first_job_id() {
  local j
  j="$(grep -oE 'job_(local)?[0-9_]+' "$1" 2>/dev/null | head -n 1)" || j=""
  printf '%s' "$j"
}

# classify_job_ids LOG: every distinct MapReduce job id in LOG, in order of first
# appearance, both forms: job_local<digits>_<digits> (the local runner) and
# job_<digits>_<digits> with 10 or more leading digits (YARN). Call it directly, not in
# a command substitution. Sets
#   SC_MR_IDS        the ids, space separated (empty when none)
#   SC_MR_IDS_NL     the same, one per line
#   SC_MR_N          how many distinct ids
#   SC_MR_YARN       how many are job_<digits>_<digits>
#   SC_MR_LOCAL      how many are job_local...
# shellcheck disable=SC2034  # the SC_MR_* results are read by the sourcing scripts
SC_MR_IDS=""
# shellcheck disable=SC2034  # read by the sourcing scripts
SC_MR_IDS_NL=""
# shellcheck disable=SC2034  # read by the sourcing scripts
SC_MR_N=0
# shellcheck disable=SC2034  # read by the sourcing scripts
SC_MR_YARN=0
# shellcheck disable=SC2034  # read by the sourcing scripts
SC_MR_LOCAL=0
# shellcheck disable=SC2034  # the SC_MR_* results are read by the sourcing scripts
classify_job_ids() {
  local j
  SC_MR_IDS=""
  SC_MR_IDS_NL=""
  SC_MR_N=0
  SC_MR_YARN=0
  SC_MR_LOCAL=0
  while IFS= read -r j; do
    [[ -n "$j" ]] || continue
    SC_MR_N=$((SC_MR_N + 1))
    SC_MR_IDS="${SC_MR_IDS}${SC_MR_IDS:+ }${j}"
    SC_MR_IDS_NL="${SC_MR_IDS_NL}${j}"$'\n'
    case "$j" in
      job_local*) SC_MR_LOCAL=$((SC_MR_LOCAL + 1)) ;;
      *) SC_MR_YARN=$((SC_MR_YARN + 1)) ;;
    esac
  done <<EOF
$( { grep -oE 'job_local[0-9]+_[0-9]+|job_[0-9]{10,}_[0-9]+' "$1" 2>/dev/null || true; } | awk '!seen[$0]++')
EOF
}

# yarn_mapreduce_apps DEST: read the ResourceManager's list of MapReduce applications
# (yarn application -list -appTypes MAPREDUCE -appStates ALL, a read only call), keep
# the whole output in the file DEST, and print the application ids it names, one line,
# space separated, nothing when there are none. A failure of the command is recorded in
# DEST and never stops the script. The list has no start-time column, so the caller
# relies on the cluster being fresh with one Hadoop stage.
yarn_mapreduce_apps() {
  local dest="$1" ids
  { timeout 120 yarn application -list -appTypes MAPREDUCE -appStates ALL 2>&1 || true; } > "$dest"
  ids="$(grep -oE 'application_[0-9]+_[0-9]+' "$dest" 2>/dev/null | LC_ALL=C sort -u | tr '\n' ' ')" || ids=""
  printf '%s' "${ids% }"
}

# job_ids_in_file FILE: every job_<digits>_<digits> in FILE, one line, space separated,
# ordered by timestamp then sequence, no duplicates. Never fails.
job_ids_in_file() {
  local out
  out="$( { grep -oE 'job_[0-9]{10,}_[0-9]+' "$1" 2>/dev/null || true; } | LC_ALL=C sort -t_ -k2,2n -k3,3n -u | tr '\n' ' ')"
  printf '%s' "${out% }"
}

# mapred_job_list_ids DEST: best effort, never fails. Runs "mapred job -list all" under a
# 60 s timeout and, when that gives no job id (not usable on this cluster), "mapred job
# -list". The whole output of both goes to DEST. Prints the ids found, one line, space
# separated. UNVERIFIED on EMR 7.10.0: whether the command lists finished jobs there.
mapred_job_list_ids() {
  local dest="$1" ids
  { timeout 60 mapred job -list all 2>&1 || true; } > "$dest"
  ids="$(job_ids_in_file "$dest")"
  if [[ -z "$ids" ]]; then
    { printf '\n# mapred job -list all gave no job id; trying mapred job -list\n'; timeout 60 mapred job -list 2>&1 || true; } >> "$dest"
    ids="$(job_ids_in_file "$dest")"
  fi
  printf '%s' "$ids"
}

# derive_job_ids APPS: job_<ts>_<n> for each application_<ts>_<n> in the space separated
# list APPS. A MapReduce job id is its application id with the prefix application_
# replaced by job_ (a naming property of Hadoop, not an observation in a log).
derive_job_ids() {
  local out
  out="$(printf '%s' "$1" | tr ' ' '\n' | sed -n 's/^application_\([0-9][0-9]*_[0-9][0-9]*\)$/job_\1/p' | tr '\n' ' ')"
  printf '%s' "${out% }"
}

# words_in TEXT: how many space separated words.
words_in() {
  printf '%s' "$1" | wc -w | tr -d ' '
}

# choose_mapreduce_job_ids EXPECTED APPS LISTLOG: the real job ids of the Hadoop stage,
# from the first source that yields at least EXPECTED of them, else from the source that
# yields the most (ties go to the earlier source). Call classify_job_ids on the Hadoop
# log first. Call it directly, not in a command substitution. Sources, in order:
#   hadoop-log                    the ids found in the Hadoop log (classify_job_ids)
#   mapred-job-list               mapred job -list all (or mapred job -list), parsed
#   derived-from-application-ids  job_<ts>_<n> derived from each application id APPS
# Sets SC_MRF_IDS (space separated), SC_MRF_N and SC_MRF_SOURCE (none when no id at all).
# shellcheck disable=SC2034  # the SC_MRF_* results are read by the sourcing scripts
SC_MRF_IDS=""
# shellcheck disable=SC2034  # read by the sourcing scripts
SC_MRF_N=0
# shellcheck disable=SC2034  # read by the sourcing scripts
SC_MRF_SOURCE="none"
# shellcheck disable=SC2034  # the SC_MRF_* results are read by the sourcing scripts
choose_mapreduce_job_ids() {
  local expected="$1" apps="$2" listlog="$3" cand n
  SC_MRF_IDS="$SC_MR_IDS"
  SC_MRF_N="$SC_MR_N"
  SC_MRF_SOURCE="hadoop-log"
  if [[ "$SC_MRF_N" -eq 0 ]]; then
    SC_MRF_SOURCE="none"
  fi
  if [[ "$SC_MRF_N" -lt "$expected" ]]; then
    cand="$(mapred_job_list_ids "$listlog")"
    n="$(words_in "$cand")"
    if [[ "$n" -gt "$SC_MRF_N" ]]; then
      SC_MRF_IDS="$cand"
      SC_MRF_N="$n"
      SC_MRF_SOURCE="mapred-job-list"
    fi
  fi
  if [[ "$SC_MRF_N" -lt "$expected" ]]; then
    cand="$(derive_job_ids "$apps")"
    n="$(words_in "$cand")"
    if [[ "$n" -gt "$SC_MRF_N" ]]; then
      SC_MRF_IDS="$cand"
      SC_MRF_N="$n"
      SC_MRF_SOURCE="derived-from-application-ids"
    fi
  fi
  return 0
}

### Files and publishing -----------------------------------------------------

# lib_jars_csv DIR EXCLUDE_ERE: comma list of the jars in DIR (not recursive) whose
# file name does not match EXCLUDE_ERE. Prints the excluded names to stdout as
# EXCLUDED_LIB_JARS= through kv in the caller.
lib_jars_csv() {
  local dir="$1" excl="$2" j n out=""
  for j in "$dir"/*.jar; do
    [[ -f "$j" ]] || continue
    n="${j##*/}"
    if [[ "$n" =~ $excl ]]; then
      continue
    fi
    out="${out}${out:+,}${j}"
  done
  printf '%s' "$out"
}

lib_jars_excluded() {
  local dir="$1" excl="$2" j n out=""
  for j in "$dir"/*.jar; do
    [[ -f "$j" ]] || continue
    n="${j##*/}"
    if [[ "$n" =~ $excl ]]; then
      out="${out}${out:+ }${n}"
    fi
  done
  printf '%s' "$out"
}

# main_jar DIR: the one jar directly in DIR (the thin job jar), or fail.
main_jar() {
  local dir="$1" j n=0 found=""
  for j in "$dir"/*.jar; do
    [[ -f "$j" ]] || continue
    n=$((n + 1))
    found="$j"
  done
  [[ "$n" -eq 1 ]] || die "expected exactly one jar in $dir, found $n"
  printf '%s' "$found"
}

### Single-node permissions and recorded Hadoop settings ----------------------

# mode_of PATH: owner:group and octal mode, or unknown.
mode_of() {
  stat -c '%U:%G %a' "$1" 2>/dev/null || echo unknown
}

# sc_chmod ARGS...: chmod, with the exact change printed first. A failure is reported
# and never stops the run: the next stage then shows what it cost.
sc_chmod() {
  local rc=0
  note "chmod $*"
  chmod "$@" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    note "chmod $* failed (exit $rc): the target keeps its mode"
  fi
  return 0
}

# sc_chmod_readable_tree DIR: chmod -R a+rX after a stage. Entries that another user
# (the YARN containers) created may refuse the change; that is reported, not fatal, and
# the files are readable anyway when they were created with the umask of
# SC_FS_UMASK.
sc_chmod_readable_tree() {
  local rc=0
  chmod -R a+rX "$1" || rc=$?
  if [[ "$rc" -ne 0 ]]; then
    note "chmod -R a+rX $1 reported errors (exit $rc): entries owned by another user keep their mode"
  fi
  return 0
}

# conf_xml_value KEY: the value of KEY in core-site.xml or mapred-site.xml under
# HADOOP_CONF_DIR (default /etc/hadoop/conf), the later file winning, or "unset". Reads
# the node's files only, best effort, never fails. A setting that a job or a -D option
# sets later is not seen here.
conf_xml_value() {
  local key="$1" dir="${HADOOP_CONF_DIR:-/etc/hadoop/conf}" k f r v=""
  # Each dot becomes the bracket expression [.], which matches a literal dot only, so
  # io.compression.codecs cannot match ioXcompressionYcodecs and no key matches as a
  # prefix of a longer one (the pattern ends at </name>).
  k="${key//./[.]}"
  for f in core-site.xml mapred-site.xml; do
    [[ -r "$dir/$f" ]] || continue
    # A missing key makes grep exit 1, which pipefail turns into a failing group; the
    # "|| true" keeps r empty, and the file is skipped above when it is absent.
    r="$( { tr '\n' ' ' < "$dir/$f" | grep -oE "<name>[[:space:]]*${k}[[:space:]]*</name>[[:space:]]*<value>[^<]*</value>" | tail -n 1 | sed -e 's/.*<value>//' -e 's/<\/value>.*//'; } 2>/dev/null || true)"
    if [[ -n "$r" ]]; then
      v="$r"
    fi
  done
  printf '%s' "${v:-unset}"
}

# record_hadoop_effective_conf: kv lines NODE_CONF_<KEY> for the settings that decide
# codec and compression behavior, as the node's site files set them.
record_hadoop_effective_conf() {
  local key
  for key in io.compression.codecs mapreduce.map.output.compress mapreduce.map.output.compress.codec \
    mapreduce.output.fileoutputformat.compress mapreduce.output.fileoutputformat.compress.codec; do
    kv "NODE_CONF_$(printf '%s' "$key" | tr 'a-z.' 'A-Z_')" "$(conf_xml_value "$key")"
  done
}

local_file_count() {
  find "$1" -type f -not -path '*/_work/*' | wc -l | tr -d ' '
}

# publish DIR S3_URI: copy DIR to S3_URI (a prefix ending in /) with the bucket's
# default encryption, then compare the object count. No sync, no delete. Skips the
# _work scratch directories.
publish() {
  local dir="$1" uri="$2" local_n remote_n
  [[ -d "$dir" ]] || die "nothing to publish: $dir"
  local_n="$(local_file_count "$dir")"
  aws s3 cp "$dir" "$uri" --recursive --exclude '_work/*' --only-show-errors
  # The reports prefix also holds the progress/ checkpoint and any failed/<stamp>/ copy
  # (sc_publish_progress, sc_publish_failed_logs): they are not part of this copy, so
  # they are left out of the count. awk exits 0 on an empty list, and pipefail still
  # reports a failing aws call.
  remote_n="$(aws s3 ls "$uri" --recursive | awk '$0 ~ " reports/runs/[^/]+/(progress|failed)/" { next } { n++ } END { print n + 0 }')"
  printf 'PUBLISHED uri=%s files_local=%s objects_listed=%s\n' "$uri" "$local_n" "$remote_n"
  [[ "$local_n" -eq "$remote_n" ]] || die "object count differs for $uri: local $local_n, listed $remote_n"
}
