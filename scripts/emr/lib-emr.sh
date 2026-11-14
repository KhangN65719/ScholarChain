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

SC_STAGE_N=0
SC_STAGE_NAME="init"
SC_STAGE_OPEN=0
SC_KV=""

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
  printf 'STAGE[%s] %s: start\n' "$SC_STAGE_N" "$SC_STAGE_NAME"
}

stage_ok() {
  printf 'STAGE[%s] %s: ok\n' "$SC_STAGE_N" "$SC_STAGE_NAME"
  SC_STAGE_OPEN=0
}

sc_on_exit() {
  local rc=$?
  if [[ $rc -ne 0 && $SC_STAGE_OPEN -eq 1 ]]; then
    printf 'STAGE[%s] %s: FAIL (exit %s)\n' "$SC_STAGE_N" "$SC_STAGE_NAME" "$rc"
  fi
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
  em="$(conf_value EXECUTOR_MEMORY 4g '^[0-9]+[gm]$')"
  ec="$(conf_value EXECUTOR_CORES 2 '^[0-9]+$')"
  ei="$(conf_value EXECUTOR_INSTANCES 1 '^[0-9]+$')"
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
    # is known for Apache Spark on EMR Hadoop configuration, UNVERIFIED here).
    printf 'spark.hadoop.io.compression.codecs org.apache.hadoop.io.compress.DefaultCodec,org.apache.hadoop.io.compress.GzipCodec,org.apache.hadoop.io.compress.BZip2Codec\n'
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
    timeout "${SC_SPARK_TIMEOUT:-1500}" "${cmd[@]}" > "$log" 2>&1
}

app_id_from_log() {
  grep -oE 'application_[0-9]+_[0-9]+' "$1" | tail -n 1 || true
}

# yarn_final_state APP_ID: SUCCEEDED, FAILED, KILLED, UNDEFINED or unknown.
yarn_final_state() {
  local s
  s="$(yarn application -status "$1" 2>/dev/null | awk -F' : ' '/Final-State/ { gsub(/[[:space:]]/, "", $2); print $2; exit }')" || s=""
  printf '%s' "${s:-unknown}"
}

# spark_verdict LOG RC: print yes when spark-submit exited 0, a YARN application id
# appears in the log and YARN reports it SUCCEEDED; otherwise no. Sets SC_LAST_APP.
SC_LAST_APP=""
SC_LAST_STATE=""
spark_verdict() {
  local log="$1" rc="$2"
  SC_LAST_APP="$(app_id_from_log "$log")"
  SC_LAST_STATE=""
  if [[ "$rc" -eq 0 && -n "$SC_LAST_APP" ]]; then
    SC_LAST_STATE="$(yarn_final_state "$SC_LAST_APP")"
    if [[ "$SC_LAST_STATE" == "SUCCEEDED" ]]; then
      printf 'yes'
      return 0
    fi
  fi
  printf 'no'
}

# last_error_line LOG: a short reason from the end of a log, for the report.
last_error_line() {
  local l
  l="$(grep -E 'Exception|Error|ERROR|FAILED' "$1" 2>/dev/null | tail -n 1)" || l=""
  clean_text "${l:-no error line found}"
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
  remote_n="$(aws s3 ls "$uri" --recursive | wc -l | tr -d ' ')"
  printf 'PUBLISHED uri=%s files_local=%s objects_listed=%s\n' "$uri" "$local_n" "$remote_n"
  [[ "$local_n" -eq "$remote_n" ]] || die "object count differs for $uri: local $local_n, listed $remote_n"
}
