#!/usr/bin/env bash
# Local rehearsal of the on-node preflight (lib-emr.sh, "On-node preflight"). Run it by hand
# on the operator's machine, never on a node:
#   /opt/homebrew/bin/bash scripts/emr/rehearse-preflight.sh
# It proves one thing the harness cannot: that the Scala script of preflight_write_script
# compiles and runs in the real spark-shell of Spark 4.0.5 (Scala 2.13), that `spark` is
# bound when the script is loaded with ':load FILE' fed on stdin (a here-string; `-i`/`-I` with
# stdin from /dev/null are not interpreted, phase14-rehearse-probe-1.log), and that DONE and
# both controls come out. It does NOT prove
# that a node passes: the node's real site files, EMR's classes, YARN and our job jars are
# not here (stand-ins are listed in the output).
#
# No AWS: it reads no ~/.aws, calls no aws, terraform or network command, and removes the
# AWS credential and profile variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY,
# AWS_SESSION_TOKEN, AWS_PROFILE, AWS_CONFIG_FILE, AWS_SHARED_CREDENTIALS_FILE,
# AWS_WEB_IDENTITY_TOKEN_FILE, AWS_ROLE_ARN) from the spark-shell environment. Every file it
# writes on purpose is under the rehearsal root (default
# /Users/winnguyen/scholarchain-data/rehearsal), never the repo. The JVM and the REPL write a
# few files of their own outside it (the JVM temp directory, and probably the REPL history in
# the home directory; inferred), none in the repository.
# Observed (human-run, relayed, 2026-11-15, Spark 4.0.5, Scala 2.13.16, Java 21, stand-ins, not
# a node): rehearsal 3 (phase14-rehearse-preflight-3.log) ran clean, hard and warn; rehearsal 4
# (phase14-rehearse-preflight-4.log) ran all five, each PASS, hard with PREFLIGHT_FAILED_COUNT=2.
# In nocompile the spark-shell exit was 0, no result file was written, PREFLIGHT_RESULT=failed,
# and the REPL's first diagnostic line ("<file>:<line>: error: not found: value ...", its own
# line count, not the file's) was the evidence; in nooverride the old-API smoke failed with
# ClassNotFoundException for DirectFileOutputCommitter.
#
# Five runs of the real spark-shell, each judged by the real preflight_evaluate:
#   clean       no class missing under any key we set: expect PREFLIGHT_RESULT=ok, 0 warnings
#   hard        a missing class under a key OUR spark-defaults.conf sets: expect a hard FAIL
#   warn        a missing class under a key only the NODE site file names: expect ok plus 1 WARN
#   nocompile   the script with a deliberate compile error inside the object: expect shell exit
#               0, no result file, PREFLIGHT_RESULT=failed, and the first compiler diagnostic
#               line (lower case) as the evidence, printed as a DIAGNOSTIC: line
#   nooverride  clean, but our committer line is removed and the fake mapred-site names
#               DirectFileOutputCommitter (not in the Spark jars): expect a failure naming it,
#               which shows that the clean pass is due to our override
#
# Environment (all optional):
#   SC_REHEARSE_DATA_ROOT  default /Users/winnguyen/scholarchain-data
#   SC_REHEARSE_SPARK_DIR  default $SC_REHEARSE_DATA_ROOT/spark-4.0.5 (an unpacked
#                          distribution, or a directory holding spark-4.0.5-bin-hadoop3.tgz
#                          and spark-4.0.5.sha256, which is verified and unpacked once)
#   SC_REHEARSE_ROOT       default $SC_REHEARSE_DATA_ROOT/rehearsal (refused inside the repo)
#   SC_REHEARSE_LIMIT      spark-shell limit in seconds, 15 to 300, default 120. The node limit
#                          is 60 s; this one is looser so a slow laptop still shows a result,
#                          and the elapsed seconds are printed against 60.
#   JAVA_HOME              a JDK 17 or later; else /usr/libexec/java_home -v 17+ is tried

if [ -z "${BASH_VERSINFO:-}" ] || [ "${BASH_VERSINFO[0]}" -lt 4 ]; then
  {
    printf 'REFUSED: this is bash %s. lib-emr.sh is written for bash 4 or later, and this script\n' "${BASH_VERSION:-unknown}"
    printf 'does not re-exec itself into a shell it has not been given.\n'
    printf 'Install one: brew install bash coreutils   (coreutils supplies the GNU timeout the lib uses)\n'
    printf 'Then run it with the explicit path, for example:\n'
    printf '  /opt/homebrew/bin/bash %s\n' "$0"
    printf '  /usr/local/bin/bash %s      (Intel Homebrew)\n' "$0"
    printf '  <any bash 4+ path> %s\n' "$0"
  } >&2
  for c in /opt/homebrew/bin/bash /usr/local/bin/bash; do
    if [ -x "$c" ]; then
      # shellcheck disable=SC2016  # the inner bash must expand BASH_VERSINFO, not this shell
      v="$("$c" -c 'printf %s "${BASH_VERSINFO[0]}"' 2>/dev/null)" || v=0
      if [ "${v:-0}" -ge 4 ]; then
        printf 'Found bash %s at %s: run  %s %s\n' "$v" "$c" "$c" "$0" >&2
      fi
    fi
  done
  exit 2
fi

set -euo pipefail

refuse() {
  printf 'REFUSED: %s\n' "$*" >&2
  exit 2
}

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/../.." && pwd -P)"
LIB="$HERE/lib-emr.sh"
[[ -r "$LIB" ]] || refuse "cannot read $LIB"

DATA_ROOT="${SC_REHEARSE_DATA_ROOT:-/Users/winnguyen/scholarchain-data}"
SPARK_SRC="${SC_REHEARSE_SPARK_DIR:-$DATA_ROOT/spark-4.0.5}"
ROOT_IN="${SC_REHEARSE_ROOT:-$DATA_ROOT/rehearsal}"
LIMIT="${SC_REHEARSE_LIMIT:-120}"

[[ "$ROOT_IN" == /* && "$SPARK_SRC" == /* ]] || refuse "SC_REHEARSE_ROOT and SC_REHEARSE_SPARK_DIR must be absolute paths"
[[ "$LIMIT" =~ ^[1-9][0-9]{1,2}$ && "$LIMIT" -ge 15 && "$LIMIT" -le 300 ]] || refuse "SC_REHEARSE_LIMIT must be a whole number from 15 to 300"
case "/$ROOT_IN/" in
  */./* | */../*) refuse "SC_REHEARSE_ROOT must not contain . or .. segments: $ROOT_IN" ;;
esac

# Physical path of a directory that may not exist yet: the nearest existing ancestor resolved
# with pwd -P, then the missing names appended. Nothing is created, so the refusal below runs
# before any directory is made.
resolve_new_dir() {
  local p="$1" rest="" base
  [[ -n "$p" ]] || p="/"
  while [[ ! -d "$p" ]]; do
    base="${p##*/}"
    rest="/$base$rest"
    p="${p%/*}"
    [[ -n "$p" ]] || p="/"
  done
  printf '%s%s' "$(cd "$p" && pwd -P)" "$rest"
}
ROOT="$(resolve_new_dir "${ROOT_IN%/}")"
case "$ROOT/" in
  "$REPO"/*) refuse "the rehearsal root $ROOT is inside the repository $REPO; choose a directory outside it" ;;
esac

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN="$ROOT/preflight-$STAMP"
case "$RUN" in
  *[[:space:]]*) refuse "the run directory $RUN holds whitespace, and ':load' splits its argument on whitespace (inferred); choose SC_REHEARSE_ROOT without any" ;;
esac
mkdir -p "$ROOT"
[[ ! -e "$RUN" ]] || refuse "the run directory $RUN already exists (the stamp has 1-second resolution); wait a second and run again"
SHIM="$RUN/shim"
SUMMARY="$RUN/summary.txt"
mkdir -p "$RUN" "$SHIM"
ln -sfn "$RUN" "$ROOT/latest"

say() {
  printf '%s\n' "$*"
  printf '%s\n' "$*" >> "$SUMMARY"
}

# The lib calls the GNU `timeout` through env, so a function cannot stand in: a symlink in a
# directory that comes first on PATH does.
TIMEOUT_BIN=""
for c in "$(command -v timeout 2>/dev/null || true)" "$(command -v gtimeout 2>/dev/null || true)" /opt/homebrew/opt/coreutils/libexec/gnubin/timeout /usr/local/opt/coreutils/libexec/gnubin/timeout; do
  if [[ -n "$c" && -x "$c" ]]; then
    TIMEOUT_BIN="$c"
    break
  fi
done
[[ -n "$TIMEOUT_BIN" ]] || refuse "no GNU timeout (or gtimeout) found; the lib's spark-shell wrapper needs it. Install: brew install coreutils"
TIMEOUT_VERSION="$("$TIMEOUT_BIN" --version 2>&1 || true)"
[[ "$TIMEOUT_VERSION" == *"GNU coreutils"* ]] || refuse "$TIMEOUT_BIN is not GNU coreutils timeout (the lib uses timeout -k); install: brew install coreutils"
ln -sf "$TIMEOUT_BIN" "$SHIM/timeout"

for t in tar shasum awk sed tr grep; do
  command -v "$t" > /dev/null 2>&1 || refuse "required tool not found: $t"
done

# No AWS reach of any kind from here on.
export AWS_EC2_METADATA_DISABLED=true
export SC_HOME="$RUN/node-home"
export PATH="$SHIM:$PATH"

# shellcheck source=scripts/emr/lib-emr.sh
source "$LIB"

### Java ---------------------------------------------------------------------

find_java
if [[ -z "$SC_JAVA_HOME" && -x /usr/libexec/java_home ]]; then
  jh="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
  if [[ -n "$jh" ]]; then
    export JAVA_HOME="$jh"
    find_java
  fi
fi
[[ -n "$SC_JAVA_HOME" ]] || refuse "no JDK 17 or later found. Set JAVA_HOME to one (Spark 4.0.5 needs 17 or 21)"

### Spark 4.0.5 --------------------------------------------------------------

resolve_spark_home() {
  local src="$1" cache="$ROOT/spark-unpacked" sums line
  if [[ -x "$src/bin/spark-shell" ]]; then
    SPARK_HOME="$src"
    return 0
  fi
  if [[ -x "$src/$SPARK_DIST/bin/spark-shell" ]]; then
    SPARK_HOME="$src/$SPARK_DIST"
    return 0
  fi
  if [[ -x "$cache/$SPARK_DIST/bin/spark-shell" ]]; then
    SPARK_HOME="$cache/$SPARK_DIST"
    return 0
  fi
  [[ -r "$src/$SPARK_TARBALL" ]] || refuse "no unpacked Spark and no $SPARK_TARBALL under $src (set SC_REHEARSE_SPARK_DIR)"
  sums="$src/$SPARK_SUMS_NAME"
  if [[ -r "$sums" ]]; then
    line="$(grep -F "  $SPARK_TARBALL" "$sums" || true)"
    [[ -n "$line" ]] || refuse "$sums has no line for $SPARK_TARBALL"
    printf '%s\n' "$line" > "$RUN/spark-tarball.sha256"
    (cd "$src" && shasum -a 256 -c "$RUN/spark-tarball.sha256") > "$RUN/spark-sha256.log" 2>&1 || refuse "sha256 of $SPARK_TARBALL does not match $sums (see $RUN/spark-sha256.log)"
    say "SPARK_SHA256_VERIFIED=yes"
  else
    say "NOTE: no $sums, the tarball is not verified"
  fi
  say "NOTE: unpacking $src/$SPARK_TARBALL into $cache (local, once)"
  rm -rf "$cache"
  mkdir -p "$cache"
  tar -xzf "$src/$SPARK_TARBALL" -C "$cache"
  [[ -x "$cache/$SPARK_DIST/bin/spark-shell" ]] || refuse "no spark-shell in the unpacked tarball under $cache"
  SPARK_HOME="$cache/$SPARK_DIST"
}
resolve_spark_home "$SPARK_SRC"
export SPARK_HOME

### Stand-ins for the node -----------------------------------------------------

# `hadoop classpath --glob` is the one external command preflight_write_targets runs. The
# stand-in prints HADOOP_CLASSPATH followed by the Spark distribution's own jars (which carry
# the Hadoop client classes).
SC_REHEARSE_HADOOP_CP="$(lib_jars_csv "$SPARK_HOME/jars" '^$' | tr ',' ':')"
[[ -n "$SC_REHEARSE_HADOOP_CP" ]] || refuse "no jars under $SPARK_HOME/jars"
export SC_REHEARSE_HADOOP_CP
cat > "$SHIM/hadoop" <<'SHIM_HADOOP'
#!/bin/sh
if [ "$1" = classpath ] && [ "$2" = "--glob" ]; then
  printf '%s:%s\n' "${HADOOP_CLASSPATH:-}" "$SC_REHEARSE_HADOOP_CP"
  exit 0
fi
echo "rehearsal hadoop stand-in: unsupported arguments: $*" >&2
exit 2
SHIM_HADOOP
chmod 755 "$SHIM/hadoop"

# The four job main classes are not built here. Each target gets a class with a public static
# main from the Spark distribution, so the TARGET check runs its real code on real jars. This
# says nothing about our own jars: the real names are exercised only on a node.
STANDIN_MAIN="org.apache.spark.deploy.SparkSubmit"
# shellcheck disable=SC2034  # read by the sourced lib-emr.sh preflight, not in this file
SC_PREFLIGHT_MAIN_HADOOP="$STANDIN_MAIN"
# shellcheck disable=SC2034  # read by the sourced lib-emr.sh preflight, not in this file
SC_PREFLIGHT_MAIN_ER="$STANDIN_MAIN"
# shellcheck disable=SC2034  # read by the sourced lib-emr.sh preflight, not in this file
SC_PREFLIGHT_MAIN_SPARK="$STANDIN_MAIN"
# shellcheck disable=SC2034  # read by the sourced lib-emr.sh preflight, not in this file
SC_PREFLIGHT_MAIN_EXPORT="$STANDIN_MAIN"

CORE_JAR=""
for j in "$SPARK_HOME"/jars/spark-core_*.jar; do
  if [[ -f "$j" ]]; then
    CORE_JAR="$j"
    break
  fi
done
[[ -n "$CORE_JAR" ]] || refuse "no spark-core jar under $SPARK_HOME/jars"
LIB_JAR=""
for j in "$SPARK_HOME"/jars/slf4j-api-*.jar "$SPARK_HOME"/jars/*.jar; do
  n="${j##*/}"
  if [[ -f "$j" && ! "$n" =~ ^(spark-|hadoop-) ]]; then
    LIB_JAR="$j"
    break
  fi
done
[[ -n "$LIB_JAR" ]] || refuse "no library jar outside spark-* and hadoop-* under $SPARK_HOME/jars"

make_jars() {
  local work="$1"
  local j
  mkdir -p "$work/jars/hadoop/lib" "$work/jars/entity-resolution/lib" "$work/jars/spark"
  ln -sf "$CORE_JAR" "$work/jars/hadoop/job-hadoop.jar"
  ln -sf "$LIB_JAR" "$work/jars/hadoop/lib/${LIB_JAR##*/}"
  ln -sf "$CORE_JAR" "$work/jars/entity-resolution/job-er.jar"
  # The real ER class path is job-er.jar plus every jar of entity-resolution/lib (the pipeline
  # runs java -cp "$ejar:$elibs/*"), so a real ErBatch finds its dependencies there. The
  # stand-in main class (SparkSubmit, in spark-core) needs the Spark jars that define the
  # classes its signatures name (org.apache.spark.internal.Logging is in spark-common-utils),
  # so they stand in for the ER dependency jars. preflight_write_targets is unchanged.
  for j in "$SPARK_HOME"/jars/*.jar; do
    if [[ -f "$j" ]]; then
      ln -sf "$j" "$work/jars/entity-resolution/lib/${j##*/}"
    fi
  done
  ln -sf "$CORE_JAR" "$work/jars/spark/job-spark.jar"
}

xml_prop() {
  printf '  <property><name>%s</name><value>%s</value></property>\n' "$1" "$2"
}

# A minimal node: core-site.xml and mapred-site.xml shaped like the EMR findings (LZO codecs,
# an EMR file system, the EMR committer), each of which our spark-defaults.conf overrides or
# the exemptions cover. WITH_NODE_ONLY=yes adds one missing class under a key nothing of ours sets.
write_node_conf() {
  local dir="$1" with_node_only="$2"
  {
    printf '<?xml version="1.0"?>\n<configuration>\n'
    xml_prop fs.defaultFS "file:///"
    xml_prop io.compression.codecs "org.apache.hadoop.io.compress.DefaultCodec,com.hadoop.compression.lzo.LzoCodec,com.hadoop.compression.lzo.LzopCodec"
    xml_prop io.compression.codec.lzo.class "com.hadoop.compression.lzo.LzoCodec"
    xml_prop fs.s3.impl "com.amazon.ws.emr.hadoop.fs.EmrFileSystem"
    if [[ "$with_node_only" == yes ]]; then
      xml_prop rehearsal.node.only.class "scholarchain.rehearsal.NodeOnlyMissing"
    fi
    printf '</configuration>\n'
  } > "$dir/core-site.xml"
  {
    printf '<?xml version="1.0"?>\n<configuration>\n'
    xml_prop mapred.output.committer.class "org.apache.hadoop.mapred.DirectFileOutputCommitter"
    xml_prop mapreduce.map.output.compress.codec "org.apache.hadoop.io.compress.SnappyCodec"
    printf '</configuration>\n'
  } > "$dir/mapred-site.xml"
}

set_committer() {
  local f="$1" v="$2"
  awk -v v="$v" '$1 == "spark.hadoop.mapred.output.committer.class" { print $1 " " v; next } { print }' "$f" > "$f.new"
  mv "$f.new" "$f"
  grep -qxF "spark.hadoop.mapred.output.committer.class $v" "$f" || refuse "could not set the committer line in $f"
}

drop_committer() {
  local f="$1"
  awk '$1 != "spark.hadoop.mapred.output.committer.class" { print }' "$f" > "$f.new"
  mv "$f.new" "$f"
  if grep -qF 'spark.hadoop.mapred.output.committer.class' "$f"; then
    refuse "could not remove the committer line from $f"
  fi
}

# The marker is an undefined name, so the Scala compiler reports it first and the follow-on
# message ('not found: value PreflightRun') comes after. The statement goes just before the line
# that closes the object, so the error is inside the object.
NOCOMPILE_MARKER="scholarchain_rehearsal_undefined"
make_nocompile_script() {
  local f="$1"
  awk -v m="$NOCOMPILE_MARKER" '
    { line[NR] = $0 }
    END {
      call = 0
      for (i = 1; i <= NR; i++) if (line[i] == "PreflightRun.run()") { call = i; break }
      close_at = 0
      for (i = call - 1; i >= 1; i--) if (line[i] == "}") { close_at = i; break }
      if (call == 0 || close_at == 0) exit 3
      for (i = 1; i <= NR; i++) {
        if (i == close_at) print "  private val rehearsalNoCompile: Int = " m
        print line[i]
      }
    }' "$f" > "$f.nocompile" || refuse "could not place the compile error in a copy of $f"
  mv "$f.nocompile" "$f"
  grep -qF "= $NOCOMPILE_MARKER" "$f" || refuse "the compile error is not in $f"
}

# show_errors LOG RESULT: up to 40 error-looking lines of the shell output, then the FAIL and
# ERROR lines of the result file. A line that matches a key-material pattern is dropped.
show_errors() {
  local log="$1" result="$2"
  say "---- first 40 error lines of $log"
  { grep -anE 'error|Error|ERROR|Exception|not found|cannot|warning:' "$log" 2>/dev/null | grep -avE "$SC_SECRET_RE" | head -n 40 | cut -c1-300 || true; } | tee -a "$SUMMARY"
  say "---- end of error lines (the whole log: $log)"
  if [[ -s "$result" ]]; then
    say "---- FAIL and ERROR lines of $result"
    { grep -aE '^(FAIL|ERROR) ' "$result" 2>/dev/null | grep -avE "$SC_SECRET_RE" | head -n 10 | cut -c1-300 || true; } | tee -a "$SUMMARY"
  fi
}

# say_targets RESULT: one line with the verdict of each of the four job targets, read from the
# result file, so a scenario shows all four at a glance (OK, FAIL, or MISSING when the script
# wrote no line for it), then each FAIL target line in full.
say_targets() {
  local result="$1" t line="" st
  for t in hadoop er spark export; do
    st="MISSING"
    if grep -qxF "TARGET $t OK" "$result" 2>/dev/null; then
      st="OK"
    elif grep -qE "^FAIL target $t " "$result" 2>/dev/null; then
      st="FAIL"
    fi
    line="${line}${line:+ }${t}=${st}"
  done
  say "TARGETS: $line"
  { grep -aE '^FAIL target ' "$result" 2>/dev/null | grep -avE "$SC_SECRET_RE" | cut -c1-300 || true; } | tee -a "$SUMMARY"
}

# say_diagnostic EVLOG: the text after 'Evidence from preflight.log (...): ' of the EVIDENCE
# line, on its own line. The EVIDENCE line is long (the die text comes first), so a plain cut of
# that line loses the diagnostic. A text that matches a key-material pattern is not printed.
say_diagnostic() {
  local evlog="$1" diag=""
  diag="$(grep -a '^EVIDENCE: ' "$evlog" 2>/dev/null | sed -n 's/^EVIDENCE: .*Evidence from preflight\.log ([^)]*): //p' | sed -n '1p')" || diag=""
  [[ -n "$diag" ]] || return 0
  if grep -qE "$SC_SECRET_RE" <<< "$diag"; then
    diag="a log line matches a key-material pattern: not printed"
  fi
  say "DIAGNOSTIC: ${diag:0:600}"
}

SCEN_PASS=0
SCEN_FAIL=0
RESULT_LINES=""

# run_scenario NAME WITH_NODE_ONLY COMMITTER_VALUE EXPECT(ok|hard-fail|warn|no-compile|no-override)
# COMMITTER_VALUE: a value replaces our committer line; the word DROP removes the line.
run_scenario() {
  local name="$1" node_only="$2" committer="$3" expect="$4"
  local d="$RUN/$name" work pf scala result plog evlog hconf rc=0 erc=0 trc=0 t0 secs verdict why="" observed evidence=""
  work="$d/work"
  pf="$work/preflight"
  hconf="$d/hadoop-conf"
  scala="$d/preflight.scala"
  result="$d/preflight-result.txt"
  plog="$d/preflight.log"
  evlog="$d/evaluate.log"
  mkdir -p "$hconf" "$d/spark-conf" "$pf/data"
  say ""
  say "=== scenario $name (expect: $expect) ==="

  write_node_conf "$hconf" "$node_only"
  SC_SPARK_CONF_DIR="$d/spark-conf"
  # shellcheck disable=SC2034  # read by the sourced lib-emr.sh spark-conf writer, not in this file
  SC_ARCHIVE_URI="file://$RUN/not-used/spark-$SPARK_VERSION-jars.zip"
  write_spark_conf false
  if [[ "$committer" == DROP ]]; then
    drop_committer "$SC_SPARK_CONF_DIR/spark-defaults.conf"
  elif [[ -n "$committer" ]]; then
    set_committer "$SC_SPARK_CONF_DIR/spark-defaults.conf" "$committer"
  fi
  make_jars "$work"

  preflight_write_script "$scala"
  if [[ "$expect" == no-compile ]]; then
    cp "$scala" "$scala.clean"
    make_nocompile_script "$scala"
  fi
  rm -f "$result"
  set +e
  (set -e; preflight_write_targets "$pf/targets.tsv" "$work") > "$d/targets.log" 2>&1
  trc=$?
  set -e
  if [[ "$trc" -ne 0 ]]; then
    say "SCENARIO_$name=FAIL (preflight_write_targets exit $trc, see $d/targets.log)"
    { grep -a 'EVIDENCE' "$d/targets.log" | grep -avE "$SC_SECRET_RE" | cut -c1-600 || true; } | tee -a "$SUMMARY"
    SCEN_FAIL=$((SCEN_FAIL + 1))
    RESULT_LINES="${RESULT_LINES}${name}=FAIL "
    return 0
  fi

  say "run: spark-shell --master local[2] with ':load $scala' on stdin  (limit ${LIMIT}s, same timeout -k 30 wrapper as run_preflight)"
  t0=$SECONDS
  set +e
  (
    cd "$pf" && env -u HADOOP_CLASSPATH -u SPARK_DIST_CLASSPATH -u SPARK_EXTRA_CLASSPATH \
      -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN -u AWS_PROFILE \
      -u AWS_CONFIG_FILE -u AWS_SHARED_CREDENTIALS_FILE -u AWS_WEB_IDENTITY_TOKEN_FILE -u AWS_ROLE_ARN \
      SPARK_HOME="$SPARK_HOME" SPARK_CONF_DIR="$SC_SPARK_CONF_DIR" \
      HADOOP_CONF_DIR="$hconf" YARN_CONF_DIR="$hconf" \
      JAVA_HOME="$SC_JAVA_HOME" SPARK_LOCAL_IP=127.0.0.1 \
      SC_PREFLIGHT_DIR="$pf" SC_PREFLIGHT_DATA="$pf/data" SC_PREFLIGHT_RESULT="$result" SC_PREFLIGHT_ROWS="$SC_PREFLIGHT_ROWS" \
      timeout -k 30 "$LIMIT" "$SPARK_HOME/bin/spark-shell" --master "local[2]" \
      --conf spark.sql.catalogImplementation=in-memory <<< ":load $scala"
  ) > "$plog" 2>&1
  rc=$?
  set -e
  secs=$((SECONDS - t0))
  rm -rf "${pf:?}/data"
  say "spark-shell exit $rc after ${secs}s (the node limit is ${SC_PREFLIGHT_LIMIT_SECONDS}s, this machine is not the node)"

  if [[ "$rc" -eq 124 || "$rc" -eq 137 ]]; then
    say "SCENARIO_$name=FAIL (spark-shell hit the ${LIMIT}s limit, exit $rc; raise SC_REHEARSE_LIMIT to see whether it would finish)"
    show_errors "$plog" "$result"
    SCEN_FAIL=$((SCEN_FAIL + 1))
    RESULT_LINES="${RESULT_LINES}${name}=FAIL "
    return 0
  fi

  set +e
  (set -e; preflight_evaluate "$result" "$plog" "$rc") > "$evlog" 2>&1
  erc=$?
  set -e
  say "preflight_evaluate exit $erc"
  { grep -aE '^(PREFLIGHT_|NOTE: preflight|EVIDENCE:)' "$evlog" | grep -avE "$SC_SECRET_RE" | cut -c1-400 || true; } | tee -a "$SUMMARY"
  say_diagnostic "$evlog"
  say_targets "$result"

  observed="evaluate-failed"
  if [[ "$erc" -eq 0 ]]; then
    observed="ok"
  fi
  verdict="FAIL"
  case "$expect" in
    ok)
      if [[ "$erc" -eq 0 ]] && grep -qxF 'PREFLIGHT_RESULT=ok' "$evlog" \
        && grep -qE '^PREFLIGHT_NODE_KEYS_WARN=0:' "$evlog" \
        && grep -qxF 'DONE' "$result" && grep -qxF 'CONTROL positive OK' "$result" && grep -qxF 'CONTROL negative OK' "$result"; then
        verdict="PASS"
      else
        why="expected ok with 0 warnings, DONE and both controls"
      fi
      ;;
    hard-fail)
      if [[ "$erc" -ne 0 ]] && grep -qxF 'PREFLIGHT_RESULT=failed' "$evlog" \
        && grep -qF "FAIL config mapred.output.committer.class $committer " "$result" \
        && grep -qxF 'DONE' "$result" && grep -qxF 'CONTROL positive OK' "$result" && grep -qxF 'CONTROL negative OK' "$result"; then
        verdict="PASS"
        observed="hard-fail"
      else
        why="expected a hard 'FAIL config mapred.output.committer.class $committer' with both controls OK and DONE"
      fi
      ;;
    warn)
      if [[ "$erc" -eq 0 ]] && grep -qxF 'PREFLIGHT_RESULT=ok' "$evlog" \
        && grep -qxF 'PREFLIGHT_NODE_KEYS_WARN=1: rehearsal.node.only.class' "$evlog" \
        && grep -qE '^WARN config rehearsal[.]node[.]only[.]class scholarchain[.]rehearsal[.]NodeOnlyMissing ' "$result" \
        && ! grep -qaE '^(FAIL|ERROR) ' "$result"; then
        verdict="PASS"
        observed="ok-with-warn"
      else
        why="expected ok, exactly one WARN for rehearsal.node.only.class and no FAIL"
      fi
      ;;
    no-compile)
      # The static text of the die contains no lower case 'error:', so a match is the REPL's own
      # diagnostic. The marker name proves it is the first diagnostic (about our statement), not
      # the follow-on one about PreflightRun.
      evidence="$(grep -aE '^EVIDENCE: ' "$evlog" || true)"
      if [[ "$rc" -eq 0 && "$erc" -ne 0 && ! -s "$result" ]] \
        && grep -qxF 'PREFLIGHT_RESULT=failed' "$evlog" \
        && grep -qF 'preflight wrote no result' <<< "$evidence" \
        && grep -qF 'error:' <<< "$evidence" \
        && grep -qF "$NOCOMPILE_MARKER" <<< "$evidence"; then
        verdict="PASS"
        observed="no-result-with-compile-error"
      else
        why="expected spark-shell exit 0 (got $rc), no result file, PREFLIGHT_RESULT=failed, and an EVIDENCE line with the REPL's lower case 'error:' text naming $NOCOMPILE_MARKER"
      fi
      ;;
    no-override)
      if [[ "$erc" -ne 0 ]] && grep -qxF 'PREFLIGHT_RESULT=failed' "$evlog" \
        && grep -qE '^FAIL (smoke old-api|config [^ ]+) .*DirectFileOutputCommitter' "$result" \
        && grep -qxF 'DONE' "$result" && grep -qxF 'CONTROL positive OK' "$result" && grep -qxF 'CONTROL negative OK' "$result"; then
        verdict="PASS"
        observed="failed-without-override"
      else
        why="expected a failed preflight with 'FAIL smoke old-api' (or a FAIL config line) naming org.apache.hadoop.mapred.DirectFileOutputCommitter, both controls OK and DONE; if the preflight passed, the clean pass is not shown to be due to our override"
      fi
      ;;
  esac
  say "SCENARIO_$name=$verdict observed=$observed expected=$expect seconds=$secs"
  if [[ "$verdict" == PASS ]]; then
    SCEN_PASS=$((SCEN_PASS + 1))
  else
    say "REASON: $why"
    show_errors "$plog" "$result"
    SCEN_FAIL=$((SCEN_FAIL + 1))
  fi
  RESULT_LINES="${RESULT_LINES}${name}=${verdict} "
  say "files: $d/preflight-result.txt $d/preflight.log $d/preflight.scala $d/evaluate.log $d/spark-conf/spark-defaults.conf"
}

say "REHEARSAL_DIR=$RUN"
say "SPARK_HOME=$SPARK_HOME"
say "JAVA_HOME=$SC_JAVA_HOME (major $SC_JAVA_MAJOR)"
say "TIMEOUT=$TIMEOUT_BIN"
say "STAND-INS: hadoop classpath --glob is a shim over the Spark jars; the four job main classes are $STANDIN_MAIN on symlinked spark-core jars; the node site files are minimal fakes. Our job jars, EMR's classes and YARN are not here."

run_scenario clean no "" ok
run_scenario hard no "scholarchain.rehearsal.MissingCommitter" hard-fail
run_scenario warn yes "" warn
run_scenario nocompile no "" no-compile
run_scenario nooverride no DROP no-override

say ""
if [[ "$SCEN_FAIL" -eq 0 ]]; then
  say "REHEARSAL_RESULT=PASS ($RESULT_LINES)"
else
  say "REHEARSAL_RESULT=FAIL ($RESULT_LINES)"
fi
say "REHEARSAL_SUMMARY=$SUMMARY"
say "REHEARSAL_SCOPE: proves the Scala compiles and runs in the real spark-shell (Spark $SPARK_VERSION), \`spark\` is bound under ':load' on stdin, DONE and both controls appear, the clean, hard and warn verdict modes behave, a script that does not compile leaves no result file and is reported with the compiler's own first diagnostic (nocompile), and the clean pass depends on our committer override (nooverride). Does not prove a node passes."
[[ "$SCEN_FAIL" -eq 0 ]]
