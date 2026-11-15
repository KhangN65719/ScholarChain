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
# Jar name filters that decide the job class paths. run-s-pipeline.sh builds the Hadoop and
# Spark stage commands from them and the on-node preflight builds the class paths it loads
# from, so both read one value.
SC_HADOOP_LIB_EXCLUDE="^hadoop-"
SC_SPARK_LIB_EXCLUDE="^(spark-|hadoop-)"

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

### On-node preflight --------------------------------------------------------

# S run 5 lost 55 minutes because the export stage, the last one before publish, was the
# first to need a class that only EMR's site files name. run_preflight runs before the
# Hadoop stage and is expected to fail in about 30 to 40 s (the hard ceiling is
# SC_PREFLIGHT_LIMIT_SECONDS plus the 30 s KILL grace, at most 90 s) when a class that the Spark applications or the job jars need is
# missing. Written, not run on a node: every time and every class name here is UNVERIFIED.
#
# One spark-shell (Scala REPL, no new jar; the REPL compiles the script on the node, which no
# run has yet shown to work) on local[2] with what the Spark
# driver stages use: the Spark 4.0.5 distribution, the generated spark-defaults.conf,
# HADOOP_CONF_DIR=/etc/hadoop/conf, and none of EMR's own class path variables. The job jars
# are not put on the shell's own class path (the Scala 3 runtime jar among them could confuse
# the Scala 2.13 shell); checks 1 and 2 load from class loaders built over the exact jar lists
# instead. The script it runs (preflight_write_script) writes one result line per check to
# preflight-result.txt:
#   1. Class-valued configuration. The class names in the node's four site files plus the
#      spark.hadoop.* and spark.* keys of our spark-defaults.conf, loaded with Class.forName
#      (no initialization) from a class loader over exactly the Spark driver's jars. A key that
#      spark-defaults.conf sets is checked with OUR value; the node's value is recorded as
#      OVERRIDDEN. A few key families are reported as EXEMPT instead of failing (daemon-side
#      keys, file systems and committers of schemes the jobs never open, codec classes only
#      read through io.compression.codecs); they are listed in the record. A class that only
#      a node site key names (a key spark-defaults.conf does not set) and that does not load is
#      reported as WARN and does not fail the run (human decision, report only): the smoke
#      writes, the main classes and our own keys stay hard failures.
#   2. The main class of each job jar, loaded from its own class path (Hadoop: the output of
#      hadoop classpath --glob plus HADOOP_CLASSPATH, as the hadoop jar command builds it; ER:
#      the jar and its lib directory; Spark and export: the distribution's jars, the lib jars
#      and the job jar), with a static main(String[]).
#   3. Behaviour: an old-API saveAsTextFile and a Parquet Dataset write to a directory under the
#      run's work directory (file: only), each read back and counted. The old-API write builds
#      its output committer from mapred.output.committer.class, the call that failed in S run 5.
# Not vacuous: preflight_evaluate refuses a result without both controls (a class that must
# load, a class that cannot exist), without core-site.xml, without every spark.hadoop.* line of
# spark-defaults.conf reflected in the driver's Hadoop configuration, without the four job
# targets and the two smoke lines, and without the DONE line the script writes last. The
# count of node keys examined is recorded but not required (a node may name none).
# What it cannot catch: README, "On-node preflight".
SC_PREFLIGHT_LIMIT_SECONDS=60
SC_PREFLIGHT_FLOOR_SECONDS=15
SC_PREFLIGHT_ROWS=25
SC_PREFLIGHT_MAIN_HADOOP="scholarchain.hadoop.jobs.PipelineTool"
SC_PREFLIGHT_MAIN_ER="scholarchain.er.ErBatch"
SC_PREFLIGHT_MAIN_SPARK="scholarchain.spark.CollaborationPipeline"
SC_PREFLIGHT_MAIN_EXPORT="scholarchain.spark.JsonlExport"

# preflight_write_script DEST: the Scala script that spark-shell loads through ':load DEST' on
# its stdin (a here-string). `-i FILE` and `-I FILE` with stdin from /dev/null are NOT
# interpreted on Spark 4.0.5 / Scala 2.13.16 / Java 21: the REPL binds `spark`, reads EOF and
# exits 0 with no output (observed in phase14-rehearse-probe-1.log, found by
# phase14-rehearse-preflight-1.log). `:load` handles the multi-line object. A compile error in
# the script prints to the REPL output, so it lands in preflight.log. Under ':load' such an error
# means 'object PreflightRun' never exists: the result file, opened inside the object, is never
# created, PreflightRun.run() fails and 'sys.exit(0)' still runs, so the shell exits 0 with NO
# result file. Observed locally (rehearsal 4, scenario nocompile: stand-ins, Spark 4.0.5, Scala
# 2.13.16, Java 21, not on a node): exit 0, no result file, PREFLIGHT_RESULT=failed, and the
# first diagnostic line reported as the evidence. It reads its
# inputs from SC_PREFLIGHT_DIR (targets.tsv), SC_PREFLIGHT_DATA, SC_PREFLIGHT_ROWS and
# SC_PREFLIGHT_RESULT, and writes nothing but the result file and the smoke data.
preflight_write_script() {
  cat > "$1" <<'SC_PREFLIGHT_SCALA'
import java.io.PrintWriter
import java.net.URLClassLoader
import java.nio.file.{Files, Paths}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters._

object PreflightRun {
  private val dir = sys.env("SC_PREFLIGHT_DIR")
  private val data = sys.env("SC_PREFLIGHT_DATA")
  private val rows = sys.env("SC_PREFLIGHT_ROWS").toInt
  private val confDir = sys.env.getOrElse("HADOOP_CONF_DIR", "/etc/hadoop/conf")
  private val out = new PrintWriter(Files.newBufferedWriter(Paths.get(sys.env("SC_PREFLIGHT_RESULT"))))
  private val seen = scala.collection.mutable.LinkedHashMap[String, Option[String]]()
  private var checkedKeys = 0
  private var exemptCount = 0
  private var nodeChecked = 0
  private val warnKeys = scala.collection.mutable.LinkedHashSet[String]()
  private def emit(line: String): Unit = {
    out.println(line); out.flush()
    if (line.startsWith("WARN ")) println(line)
  }
  private def one(s: String): String = s.replaceAll("[\\r\\n\\t]+", " ").trim
  private def why(e: Throwable): String = one(e.getClass.getName + ": " + String.valueOf(e.getMessage)).take(200)
  private def loaderFor(cp: String): URLClassLoader = {
    val urls = cp.split(":").filter(p => p.nonEmpty).map(p => Paths.get(p).toUri.toURL)
    new URLClassLoader(urls, ClassLoader.getPlatformClassLoader)
  }
  private def loads(name: String, loader: ClassLoader): Option[String] =
    try { Class.forName(name, false, loader); None }
    catch { case e: Throwable => Some(why(e)) }
  private val suffixes = Seq(".class", ".classes", ".classname", ".impl", ".codec", ".codecs", ".provider", ".providers", ".serializations", ".serializer", ".mapping")
  private def classLike(key: String): Boolean = {
    val k = key.toLowerCase
    suffixes.exists(s => k.endsWith(s)) || k.contains(".factory") || k.contains("committer")
  }
  private val classToken = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
  private def tokens(value: String): Seq[String] =
    value.split("[,\\s]+").toSeq.map(t => t.trim).filter(t => t.nonEmpty && classToken.matcher(t).matches)
  private val daemonPrefixes = Seq("yarn.resourcemanager.", "yarn.nodemanager.", "yarn.timeline-service.", "yarn.router.", "yarn.webapp.", "dfs.namenode.", "dfs.datanode.", "dfs.journalnode.", "mapreduce.jobhistory.", "hadoop.registry.")
  private val usedSchemes = Set("file", "hdfs", "viewfs")
  private val schemeKey = Pattern.compile("^(?:fs\\.AbstractFileSystem\\.([A-Za-z0-9]+)\\.|fs\\.([A-Za-z0-9]+)\\.|mapreduce\\.outputcommitter\\.factory\\.scheme\\.([A-Za-z0-9]+)$)")
  private val codecKey = Pattern.compile("^io\\.compression\\.codec\\.[A-Za-z0-9]+\\.class$")
  private def exemptReason(key: String): Option[String] = {
    val m = schemeKey.matcher(key)
    if (daemonPrefixes.exists(p => key.startsWith(p))) Some("daemon-side key, not read by the client")
    else if (key.endsWith(".system.classes")) Some("package prefix list, not class names")
    else if (codecKey.matcher(key).matches) Some("read only through io.compression.codecs, which spark-defaults overrides")
    else if (m.find) {
      val scheme = Seq(m.group(1), m.group(2), m.group(3)).filter(g => g != null).head
      if (usedSchemes.contains(scheme)) None else Some("scheme " + scheme + " is not used by the jobs")
    } else None
  }
  private def checkValue(loader: ClassLoader, key: String, value: String, reason: Option[String], hard: Boolean): Unit = {
    val toks = tokens(value)
    if (toks.nonEmpty) {
      checkedKeys += 1
      if (!hard) nodeChecked += 1
      for (c <- toks) {
        seen.getOrElseUpdate(c, loads(c, loader)) match {
          case None => emit("CLASS OK " + key + " " + c)
          case Some(w) =>
            if (reason.isDefined) { exemptCount += 1; emit("EXEMPT " + key + " " + c + " " + reason.get) }
            else if (hard) emit("FAIL config " + key + " " + c + " " + w)
            else { warnKeys += key; emit("WARN config " + key + " " + c + " " + w) }
        }
      }
    }
  }
  private def checkConfig(loader: ClassLoader): Unit = {
    val node = new org.apache.hadoop.conf.Configuration(false)
    val read = Seq("core-site.xml", "mapred-site.xml", "yarn-site.xml", "hdfs-site.xml").filter { n =>
      val p = Paths.get(confDir, n)
      if (Files.isReadable(p)) { node.addResource(p.toUri.toURL); true } else false
    }
    emit("SITE_FILES " + (if (read.isEmpty) "none" else read.mkString(" ")))
    val driverConf = spark.sparkContext.hadoopConfiguration
    val sparkConf = spark.sparkContext.getConf.getAll
    val ours = sparkConf.filter(kv => kv._1.startsWith("spark.hadoop.")).map(kv => (kv._1.stripPrefix("spark.hadoop."), kv._2)).toMap
    val nodeKeys = node.iterator().asScala.map(e => e.getKey).toList
    for (key <- (nodeKeys ++ ours.keys).distinct) {
      val nodeValue = Option(node.get(key))
      if (ours.contains(key)) {
        emit("OVERLAY " + key + " " + one(Option(driverConf.get(key)).getOrElse("")))
        if (nodeValue.exists(v => v != ours(key))) {
          emit("OVERRIDDEN " + key + " node=" + one(nodeValue.get).take(300) + " ours=" + one(ours(key)).take(300))
        }
      }
      val value = ours.getOrElse(key, nodeValue.getOrElse(""))
      if (classLike(key) && value.nonEmpty) {
        val own = ours.contains(key)
        checkValue(loader, key, value, if (own) None else exemptReason(key), own)
      }
    }
    for ((key, value) <- sparkConf if !key.startsWith("spark.hadoop.") && classLike(key)) {
      checkValue(loader, key, value, None, true)
    }
  }
  private def checkTargets(): Unit = {
    for (line <- Files.readAllLines(Paths.get(dir, "targets.tsv")).asScala if line.trim.nonEmpty) {
      val f = line.split("\t", -1)
      val loader = loaderFor(f(2))
      try {
        val c = Class.forName(f(1), false, loader)
        c.getDeclaredFields
        c.getDeclaredConstructors
        c.getDeclaredMethods
        val m = c.getMethod("main", classOf[Array[String]])
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers)) emit("TARGET " + f(0) + " OK")
        else emit("FAIL target " + f(0) + " " + f(1) + " main is not static")
      } catch { case e: Throwable => emit("FAIL target " + f(0) + " " + f(1) + " " + why(e)) }
      finally loader.close()
    }
  }
  private def smoke(): Unit = {
    val base = data.stripSuffix("/")
    val oldUri = "file://" + base + "/old"
    val pqUri = "file://" + base + "/pq"
    try {
      spark.sparkContext.parallelize((1 to rows).map(i => i.toString), 2).saveAsTextFile(oldUri)
      val n = spark.sparkContext.textFile(oldUri).count()
      emit(if (n == rows.toLong) "SMOKE old-api OK rows=" + n else "FAIL smoke old-api rows=" + n + " expected=" + rows)
    } catch { case e: Throwable => emit("FAIL smoke old-api " + why(e)) }
    try {
      spark.range(rows.toLong).selectExpr("cast(id as string) as k", "id as v").write.parquet(pqUri)
      val n = spark.read.parquet(pqUri).count()
      emit(if (n == rows.toLong) "SMOKE parquet OK rows=" + n else "FAIL smoke parquet rows=" + n + " expected=" + rows)
    } catch { case e: Throwable => emit("FAIL smoke parquet " + why(e)) }
  }
  def run(): Unit = {
    var sparkLoader: URLClassLoader = null
    try {
      val targetLines = Files.readAllLines(Paths.get(dir, "targets.tsv")).asScala.toSeq.filter(l => l.trim.nonEmpty).map(l => l.split("\t", -1))
      val sparkTarget = targetLines.find(f => f(0) == "spark").get
      sparkLoader = loaderFor(sparkTarget(2))
      emit("CONTROL positive " + (if (loads("org.apache.hadoop.fs.FileSystem", sparkLoader).isEmpty) "OK" else "FAILED"))
      emit("CONTROL negative " + (if (loads("scholarchain.preflight.NoSuchClassForTheControl", sparkLoader).isDefined) "OK" else "FAILED"))
      try { checkConfig(sparkLoader) } catch { case e: Throwable => emit("ERROR config " + why(e)) }
      try { checkTargets() } catch { case e: Throwable => emit("ERROR targets " + why(e)) }
      smoke()
      emit("SUMMARY keys=" + checkedKeys + " classes=" + seen.size + " exempt=" + exemptCount + " nodechecked=" + nodeChecked + " nodewarn=" + warnKeys.size)
    } catch { case e: Throwable => emit("ERROR run " + why(e)) }
    finally {
      emit("DONE")
      out.close()
      if (sparkLoader != null) sparkLoader.close()
    }
  }
}

PreflightRun.run()
sys.exit(0)
SC_PREFLIGHT_SCALA
}

# preflight_write_targets FILE WORK: FILE gets one line per job target, label, main class and
# the colon separated class path that the real command uses, tab separated. It dies when a
# jar directory is wrong, which the Hadoop stage would otherwise find minutes later.
preflight_write_targets() {
  local file="$1" work="$2" hjar hlibs hcp hadoop_cp ejar elibs ecp sjar sjars sparkjars sparkcp
  hjar="$(main_jar "$work/jars/hadoop")"
  hlibs="$(lib_jars_csv "$work/jars/hadoop/lib" "$SC_HADOOP_LIB_EXCLUDE")"
  [[ -n "$hlibs" ]] || die "preflight: no library jars under jars/hadoop/lib after exclusions"
  hcp="$(printf '%s' "$hlibs" | tr ',' ':')"
  hadoop_cp="$(HADOOP_CLASSPATH="$hcp" JAVA_HOME="$SC_JAVA_HOME" timeout -k 5 20 hadoop classpath --glob)" || die "preflight: hadoop classpath --glob failed or took over 20 s"
  [[ -n "$hadoop_cp" ]] || die "preflight: hadoop classpath --glob printed nothing"
  ejar="$(main_jar "$work/jars/entity-resolution")"
  elibs="$(lib_jars_csv "$work/jars/entity-resolution/lib" '^$')"
  ecp="$ejar"
  if [[ -n "$elibs" ]]; then
    ecp="$ejar:$(printf '%s' "$elibs" | tr ',' ':')"
  fi
  sjar="$(main_jar "$work/jars/spark")"
  sjars="$(lib_jars_csv "$work/jars/spark/lib" "$SC_SPARK_LIB_EXCLUDE")"
  sparkjars="$(lib_jars_csv "$SPARK_HOME/jars" '^$')"
  [[ -n "$sparkjars" ]] || die "preflight: no jars under $SPARK_HOME/jars"
  sparkcp="$(printf '%s' "$sparkjars" | tr ',' ':')"
  if [[ -n "$sjars" ]]; then
    sparkcp="$sparkcp:$(printf '%s' "$sjars" | tr ',' ':')"
  fi
  sparkcp="$sparkcp:$sjar"
  {
    printf 'hadoop\t%s\t%s\n' "$SC_PREFLIGHT_MAIN_HADOOP" "$hadoop_cp:$hjar"
    printf 'er\t%s\t%s\n' "$SC_PREFLIGHT_MAIN_ER" "$ecp"
    printf 'spark\t%s\t%s\n' "$SC_PREFLIGHT_MAIN_SPARK" "$sparkcp"
    printf 'export\t%s\t%s\n' "$SC_PREFLIGHT_MAIN_EXPORT" "$sparkcp"
  } > "$file"
}

# preflight_compile_evidence LOG: the evidence line for a run that left no result file. A script
# that does not compile under ':load' leaves no result file (see preflight_write_script), and
# the REPL prints the cause as a diagnostic line "<file>:<line>: error: <text>" (lower case;
# observed locally in rehearsal 4, scenario nocompile, Spark 4.0.5, Scala 2.13.16, Java 21,
# stand-ins, not on a node). The REPL prints the source and caret lines of a diagnostic BEFORE
# its error line, and the diagnostics after the first are consequences ("not found: value
# PreflightRun"), so only the FIRST diagnostic line is returned, alone. The <line> number in it
# is the REPL's own count of the loaded file, not a line of preflight.scala (observed: 142 for a
# marker on file line 148); evaluate.log and preflight.log hold the whole text.
# Order: the first line of the diagnostic shape, else the first line holding "error:" in any
# case, else last_error_line. The non-fatal openssl UnsatisfiedLinkError is skipped like in
# last_error_line. The line is cut to 300 characters; a line that matches the key-material
# patterns is replaced by a fixed text. The grep results are read through sed, not head, so a
# closed pipe cannot fail the pipeline.
preflight_compile_evidence() {
  local f="$1" ex='UnsatisfiedLinkError|buildSupportsOpenssl' pat n="" out=""
  for pat in ':[0-9]+: error:' 'error:'; do
    n="$(grep -a -n -i -E "$pat" "$f" 2>/dev/null | grep -a -v -E "$ex" | sed -n '1p' | cut -d: -f1)" || n=""
    [[ "$n" =~ ^[0-9]+$ ]] && break
    n=""
  done
  if [[ -n "$n" ]]; then
    out="$(sed -n "${n}p" "$f" | cut -c1-300)"
  fi
  if [[ -n "$out" ]] && grep -qE "$SC_SECRET_RE" <<< "$out"; then
    out="a log line matches a key-material pattern: not printed"
  fi
  if [[ -z "$out" ]]; then
    last_error_line "$f"
  else
    clean_text "$out"
  fi
}

# preflight_evaluate RESULT LOG RC: read the result file and die on the first thing that is
# not proven. RC is the exit status of spark-shell, which proves nothing by itself: it is
# observed locally (rehearsal 4, scenario nocompile: stand-ins, Spark 4.0.5, Scala 2.13.16, Java
# 21, not on a node) that the REPL keeps going after a compile error in the script and exits 0
# with no result file; the design does not depend on it, because only the result file is read.
# No result file has two causes: a REPL that never interpreted the script, and a script that
# failed to compile under ':load' (the object never exists, so nothing opens the file and the
# shell still exits 0). Both record PREFLIGHT_RESULT=failed, and the evidence is the first
# compiler diagnostic of preflight.log (preflight_compile_evidence). A result file without DONE
# means the script compiled and started.
# A WARN line (a class that only a node site file names and that does not load) is recorded
# and reported, never a failure.
preflight_evaluate() {
  local result="$1" plog="$2" rc="$3" conf="$SC_SPARK_CONF_DIR/spark-defaults.conf"
  local rows="$SC_PREFLIGHT_ROWS" fails nfail first line k v label summary keys classes exempt nodechecked overridden warn_keys warn_n w overlay_n=0
  [[ -s "$result" ]] || kv PREFLIGHT_RESULT failed
  [[ -s "$result" ]] || die "preflight wrote no result (spark-shell exit $rc; the script is loaded with ':load' from stdin, and a REPL that never interpreted it exits 0 with no output): a script that failed to compile under ':load' does the same, because the object never exists and no result file is created, so read preflight.log. Evidence from preflight.log (the first compiler diagnostic, else the last error line): $(preflight_compile_evidence "$plog")"
  grep -qx 'DONE' "$result" || kv PREFLIGHT_RESULT failed
  grep -qx 'DONE' "$result" || die "preflight did not reach DONE (spark-shell exit $rc; the script compiled and started, since the result file exists, but it stopped before its last line; a compile error would have left no result file at all): $(last_error_line "$plog")"
  fails="$(grep -E '^(FAIL|ERROR) ' "$result" || true)"
  if [[ -n "$fails" ]]; then
    nfail="$(printf '%s\n' "$fails" | wc -l | tr -d ' ')"
    first="${fails%%$'\n'*}"
    kv PREFLIGHT_RESULT failed
    kv PREFLIGHT_FAILED_COUNT "$nfail"
    kv PREFLIGHT_FIRST_FAILURE "$first"
    die "preflight: $nfail check(s) failed, first: $first (every result line is in preflight-result.txt)"
  fi
  grep -qx 'CONTROL positive OK' "$result" || die "preflight control failed: a class that must load did not, so no result can be trusted"
  grep -qx 'CONTROL negative OK' "$result" || die "preflight control failed: a class that cannot exist loaded, so the check would pass anything"
  line="$(grep '^SITE_FILES ' "$result" | head -n 1 || true)"
  [[ " $line " == *" core-site.xml "* ]] || die "preflight read no core-site.xml under /etc/hadoop/conf ($line): the class-valued check would be empty"
  while IFS=' ' read -r k v; do
    [[ "$k" == spark.hadoop.* ]] || continue
    grep -qxF -- "OVERLAY ${k#spark.hadoop.} $v" "$result" || die "preflight: $k of spark-defaults.conf is not what the Spark driver's Hadoop configuration holds, so our overrides do not apply"
    overlay_n=$((overlay_n + 1))
  done < "$conf"
  [[ "$overlay_n" -ge 1 ]] || die "preflight: spark-defaults.conf has no spark.hadoop.* line to prove the overrides with"
  for label in hadoop er spark export; do
    grep -qx "TARGET $label OK" "$result" || die "preflight: no 'TARGET $label OK' line, so the $label main class was not proven"
  done
  for label in old-api parquet; do
    grep -qx "SMOKE $label OK rows=$rows" "$result" || die "preflight: no 'SMOKE $label OK rows=$rows' line, so the $label write was not proven"
  done
  summary="$(sed -n 's/^SUMMARY //p' "$result" | head -n 1)"
  keys="$(printf '%s' "$summary" | sed -n 's/.*keys=\([0-9][0-9]*\).*/\1/p')"
  classes="$(printf '%s' "$summary" | sed -n 's/.*classes=\([0-9][0-9]*\).*/\1/p')"
  exempt="$(printf '%s' "$summary" | sed -n 's/.*exempt=\([0-9][0-9]*\).*/\1/p')"
  nodechecked="$(printf '%s' "$summary" | sed -n 's/.*nodechecked=\([0-9][0-9]*\).*/\1/p')"
  [[ "${keys:-0}" -ge 1 && "${classes:-0}" -ge 1 ]] || die "preflight checked no class-valued key ($summary)"
  warn_keys="$(awk '$1 == "WARN" && !seen[$3]++ { printf "%s%s", (n++ ? " " : ""), $3 }' "$result")"
  warn_n="$(awk '$1 == "WARN" && !seen[$3]++ { n++ } END { print n + 0 }' "$result")"
  kv PREFLIGHT_RESULT ok
  kv PREFLIGHT_SITE_FILES "${line#SITE_FILES }"
  kv PREFLIGHT_CONFIG_KEYS_CHECKED "$keys"
  kv PREFLIGHT_CLASS_NAMES_CHECKED "$classes"
  overridden="$(awk '$1 == "OVERRIDDEN" { printf "%s%s", (n++ ? " " : ""), $2 }' "$result")"
  kv PREFLIGHT_KEYS_OVERRIDDEN "${overridden:-none}"
  kv PREFLIGHT_UNLOADABLE_EXEMPT "$exempt: $(awk '$1 == "EXEMPT" { printf "%s%s", (n++ ? " " : ""), $2 }' "$result")"
  kv PREFLIGHT_NODE_KEYS_CHECKED "${nodechecked:-unknown}"
  kv PREFLIGHT_NODE_KEYS_WARN "$warn_n: $warn_keys"
  kv PREFLIGHT_OVERLAY_KEYS_PROVEN "$overlay_n"
  kv PREFLIGHT_TARGETS "hadoop:ok er:ok spark:ok export:ok"
  kv PREFLIGHT_SMOKE "old-api:ok parquet:ok rows=$rows"
  while IFS= read -r w; do
    note "preflight ${w:0:300} (report only, node site file key not set by spark-defaults.conf; see preflight-result.txt)"
  done < <(grep '^WARN ' "$result" | head -n 20 || true)
}

# run_preflight WORK REPORTS: see the block above. Call it after write_spark_conf (it needs
# SPARK_HOME, SC_SPARK_CONF_DIR and the staged jars). The script, the JVM output and the result
# file go to REPORTS, so a failure publishes them with the other logs. The smoke data lives
# under WORK/preflight/data and is removed before the result is read.
run_preflight() {
  local work="$1" rep="$2" pf="$1/preflight" t0=$SECONDS limit remaining rc=0
  local scala="$rep/preflight.scala" result="$rep/preflight-result.txt" plog="$rep/preflight.log"
  limit="${SC_PREFLIGHT_TIMEOUT:-$SC_PREFLIGHT_LIMIT_SECONDS}"
  [[ "$limit" =~ ^[1-9][0-9]?$ && "$limit" -ge "$SC_PREFLIGHT_FLOOR_SECONDS" && "$limit" -le "$SC_PREFLIGHT_LIMIT_SECONDS" ]] || die "SC_PREFLIGHT_TIMEOUT must be a whole number from $SC_PREFLIGHT_FLOOR_SECONDS to $SC_PREFLIGHT_LIMIT_SECONDS with no leading zero (the floor of 15 is chosen because the JVM compile and run is estimated at 15 to 25 s, and below it a pass is not plausible)"
  [[ -x "$SPARK_HOME/bin/spark-shell" ]] || die "no spark-shell in the extracted distribution: $SPARK_HOME"
  [[ "$scala" != *[[:space:]]* ]] || die "preflight: the script path '$scala' holds whitespace and ':load' splits its argument on whitespace (inferred, not observed), so the reports directory path must hold none"
  mkdir -p "$pf/data"
  preflight_write_script "$scala"
  preflight_write_targets "$pf/targets.tsv" "$work"
  kv PREFLIGHT_CLASSPATH_ENTRIES "$(awk -F'\t' '{ n = split($3, a, ":"); printf "%s%s=%s", (NR > 1 ? " " : ""), $1, n }' "$pf/targets.tsv")"
  remaining=$((limit - (SECONDS - t0)))
  [[ "$remaining" -ge 10 ]] || die "preflight: $((SECONDS - t0)) s of the $limit s limit went into building the class paths, too little is left to start the JVM"
  note "preflight: class names, job main classes and two small file: writes in one spark-shell (limit $limit s, $remaining s left for the JVM)"
  (
    cd "$pf" && env -u HADOOP_CLASSPATH -u SPARK_DIST_CLASSPATH -u SPARK_EXTRA_CLASSPATH \
      SPARK_HOME="$SPARK_HOME" SPARK_CONF_DIR="$SC_SPARK_CONF_DIR" \
      HADOOP_CONF_DIR=/etc/hadoop/conf YARN_CONF_DIR=/etc/hadoop/conf \
      JAVA_HOME="$SC_JAVA_HOME" \
      SC_PREFLIGHT_DIR="$pf" SC_PREFLIGHT_DATA="$pf/data" SC_PREFLIGHT_RESULT="$result" SC_PREFLIGHT_ROWS="$SC_PREFLIGHT_ROWS" \
      timeout -k 30 "$remaining" "$SPARK_HOME/bin/spark-shell" --master "local[2]" \
      --conf spark.sql.catalogImplementation=in-memory <<< ":load $scala"
  ) > "$plog" 2>&1 || rc=$?
  rm -rf "${pf:?}/data"
  if [[ "$rc" -eq 124 || "$rc" -eq 137 ]]; then
    kv PREFLIGHT_RESULT "timed out (exit $rc, JVM limit $remaining s)"
    die "preflight timed out (exit $rc) after $((SECONDS - t0)) s: $(last_error_line "$plog")"
  fi
  preflight_evaluate "$result" "$plog" "$rc"
  kv PREFLIGHT_LIMIT_SECONDS "$limit"
  kv PREFLIGHT_SECONDS "$((SECONDS - t0))"
}
