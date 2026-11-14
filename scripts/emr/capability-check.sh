#!/usr/bin/env bash
# capability-check.sh: what can this EMR cluster (emr-7.10.0, Hadoop application only)
# do for ScholarChain? Runs on the primary node as one command-runner.jar step, then
# the state machine terminates the cluster. Written, not executed: every probe below
# is UNVERIFIED until the first cluster.
#
# Usage (the state machine runs it): bash capability-check.sh <data-bucket> <artifacts-bucket>
#
# Result lines (also written to capability.json, with the full log, under
# s3://<data-bucket>/reports/capability/<UTC stamp>/):
#   MAPREDUCE=yarn|local|mixed|unknown|absent   SPARK_ON_YARN=client:yes|no cluster:yes|no
#   JAVA_*, HADOOP_*, AWS_CLI, DISK_*, SPARK_ARCHIVE_MODE, POPULATE_* ...
# The script exits 0 when it finished and wrote the report, even when a capability is
# "no": the answers are the product. It exits nonzero only when it could not stage or
# report (for example no Spark tarball in the bucket, or the upload failed).
#
# How MAPREDUCE is decided (the evidence is recorded next to it): the hadoop-mapreduce
# examples job "pi 1 1" is run with no framework option. If its output names a job id
# job_local... the framework is the local runner ("MapReduce in local mode on an EMR
# node", never distributed MapReduce). If it names exactly one job_<timestamp>_<n>, the
# framework is YARN. Ids of both kinds are "mixed"; no id or a count other than one is
# "unknown" (the script goes on). YARN's MAPREDUCE application list is recorded beside
# the verdict and never changes it. No examples jar, or a job that fails, is "absent"
# with a detail. mapreduce.framework.name from /etc/hadoop/conf is also
# recorded, but the job output decides.
SC_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/emr/lib-emr.sh
source "$SC_SCRIPT_DIR/lib-emr.sh"

# detail_or_error VERDICT LOGFILE: "ok" when VERDICT is yes, else the log's last error line.
detail_or_error() {
  if [[ "$1" == yes ]]; then
    printf 'ok'
  else
    last_error_line "$2"
  fi
}

capability_main() {
  install_trap
  local data_bucket="${1:-}" artifacts_bucket="${2:-}" stamp out log
  validate_bucket "data bucket" "$data_bucket"
  validate_bucket "artifacts bucket" "$artifacts_bucket"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  out="$SC_HOME/capability/$stamp"
  mkdir -p "$out"
  kv CAPABILITY_STAMP "$stamp"
  kv CAPABILITY_RELEASE_LABEL "emr-7.10.0 (design B: Hadoop application only)"

  stage environment
  kv RUN_USER "$(id -un) uid=$(id -u)"
  kv HADOOP_VERSION "$( { hadoop version 2>&1 || true; } | head -n 1)"
  yarn node -list 2>&1 | head -n 20 || true
  kv YARN_NODES_RUNNING "$( { yarn node -list 2>/dev/null || true; } | awk '/Total Nodes:/ { print $3; exit }')"
  kv JAVA_DEFAULT "$( { java -version 2>&1 || true; } | head -n 1)"
  kv JAVA_HOME_ENV "${JAVA_HOME:-unset}"
  find_java
  if [[ -n "$SC_JAVA_HOME" ]]; then
    kv JAVA17_PLUS_FOUND "yes major=$SC_JAVA_MAJOR source=$SC_JAVA_SOURCE"
    kv JAVA_HOME_SELECTED "$SC_JAVA_HOME"
  else
    kv JAVA17_PLUS_FOUND "no (the optional java bootstrap is then needed; Spark 4 stays untested below)"
    kv JAVA_HOME_SELECTED "none"
  fi
  if command -v aws >/dev/null 2>&1; then
    kv AWS_CLI "present $( { aws --version 2>&1 || true; } | head -n 1)"
  else
    kv AWS_CLI absent
  fi
  kv DISK_ROOT "$( { df -h / 2>/dev/null || true; } | awk 'NR == 2 { print "size=" $2 " avail=" $4 " use=" $5 }')"
  kv DISK_HOME "$( { df -h "$SC_HOME" 2>/dev/null || true; } | awk 'NR == 2 { print "size=" $2 " avail=" $4 " use=" $5 }')"
  kv MEMORY_MB "$(awk '/MemTotal/ { printf "%d", $2 / 1024 }' /proc/meminfo)"
  if hdfs dfs -ls / >/dev/null 2>&1; then kv HDFS_REACHABLE yes; else kv HDFS_REACHABLE no; fi
  kv MAPRED_SITE_FRAMEWORK "$( { awk '/mapreduce.framework.name/ { getline; print; exit }' /etc/hadoop/conf/mapred-site.xml 2>/dev/null || true; } | sed -e 's/<[^>]*>//g' -e 's/[[:space:]]//g')"
  stage_ok

  stage mapreduce-probe
  local jar="" f mrlog="$out/mapreduce-pi.log" rc=0 mr="absent" detail="" mrids=""
  for f in /usr/lib/hadoop-mapreduce/hadoop-mapreduce-examples*.jar /usr/lib/hadoop/hadoop-mapreduce-examples*.jar; do
    if [[ -f "$f" ]]; then jar="$f"; break; fi
  done
  if [[ -z "$jar" ]] || ! command -v hadoop >/dev/null 2>&1; then
    detail="no hadoop-mapreduce-examples jar or no hadoop launcher"
  else
    # Stdout and stderr both go to the log. HADOOP_ROOT_LOGGER=INFO,console asks the
    # client for its job-client INFO lines (UNVERIFIED: launch 3 kept no job id line and
    # no missing 2>&1 or filter was found, so this variable is the suspected cause).
    HADOOP_ROOT_LOGGER="INFO,console" timeout 900 hadoop jar "$jar" pi 1 1 > "$mrlog" 2>&1 || rc=$?
    tail -n 15 "$mrlog" || true
    classify_job_ids "$mrlog"
    if [[ "$rc" -ne 0 ]]; then
      detail="examples job exit $rc: $(last_error_line "$mrlog")"
    else
      # The pi job is one MapReduce job, so one job id is expected. The verdict rests on
      # the job id; the ResourceManager's list is recorded beside it, never instead.
      mrids="$(yarn_mapreduce_apps "$out/mapreduce-yarn-list.log")"
      if [[ "$SC_MR_LOCAL" -gt 0 && "$SC_MR_YARN" -gt 0 ]]; then
        mr="mixed"
        detail="job ids $SC_MR_IDS: YARN and local ids together"
      elif [[ "$SC_MR_LOCAL" -gt 0 ]]; then
        mr="local"
        detail="job id $SC_MR_IDS: MapReduce in local mode on an EMR node"
      elif [[ "$SC_MR_YARN" -eq 1 ]]; then
        mr="yarn"
        detail="job id $SC_MR_IDS in the job output"
      else
        mr="unknown"
        detail="job exit 0 but $SC_MR_N job id(s) found in the output against 1 expected: not proven"
      fi
      detail="$detail; yarn application -list MAPREDUCE applications: ${mrids:-none} (supporting evidence only)"
    fi
  fi
  kv MAPREDUCE "$mr"
  kv MAPREDUCE_DETAIL "$detail"
  kv MAPREDUCE_JOB_IDS "${SC_MR_IDS:-none} (count $SC_MR_N, YARN $SC_MR_YARN, local $SC_MR_LOCAL)"
  kv MAPREDUCE_SCOPE "single node: MapReduce on YARN on one node is not distributed execution"
  # Does an explicit JAVA_HOME for the task containers work? The pipeline passes it.
  if [[ -n "$jar" && -n "$SC_JAVA_HOME" && "$mr" != "absent" ]]; then
    rc=0
    timeout 900 hadoop jar "$jar" pi \
      "-Dmapreduce.map.env=JAVA_HOME=$SC_JAVA_HOME" "-Dmapreduce.reduce.env=JAVA_HOME=$SC_JAVA_HOME" \
      "-Dyarn.app.mapreduce.am.env=JAVA_HOME=$SC_JAVA_HOME" 1 1 > "$out/mapreduce-pi-java-env.log" 2>&1 || rc=$?
    if [[ "$rc" -eq 0 ]]; then kv MAPREDUCE_JAVA_ENV_OVERRIDE yes; else kv MAPREDUCE_JAVA_ENV_OVERRIDE "no (exit $rc)"; fi
  else
    kv MAPREDUCE_JAVA_ENV_OVERRIDE skipped
  fi
  stage_ok

  local client="no" cluster="no" pop_true="skipped"
  if [[ -z "$SC_JAVA_HOME" ]]; then
    stage spark-stage
    kv SPARK_STAGE skipped-no-jdk
    kv SPARK_ARCHIVE_MODE none
    stage_ok
  else
    stage spark-stage
    stage_spark "$artifacts_bucket"
    local examples
    examples="$(find "$SPARK_HOME/examples/jars" -maxdepth 1 -name 'spark-examples_*.jar' | head -n 1)"
    [[ -n "$examples" ]] || die "no spark-examples jar in the Spark distribution"
    stage_ok

    stage spark-client-populate-false
    write_spark_conf false
    rc=0
    run_spark_submit "$out/spark-client.log" client org.apache.spark.examples.SparkPi "$examples" "" 10 || rc=$?
    tail -n 8 "$out/spark-client.log" || true
    spark_verdict "$out/spark-client.log" "$rc"
    client="$SC_LAST_VERDICT"
    if [[ "$client" == "yes" ]] && ! grep -q 'Pi is roughly' "$out/spark-client.log"; then
      client="no"
      kv SPARK_CLIENT_NOTE "exit 0 and application SUCCEEDED but no 'Pi is roughly' line"
    fi
    kv SPARK_CLIENT_DETAIL "exit=$rc app=${SC_LAST_APP:-none} yarn_state=${SC_LAST_STATE:-none} $(detail_or_error "$client" "$out/spark-client.log")"
    stage_ok

    stage spark-cluster-populate-false
    rc=0
    run_spark_submit "$out/spark-cluster.log" cluster org.apache.spark.examples.SparkPi "$examples" "" 10 || rc=$?
    tail -n 8 "$out/spark-cluster.log" || true
    spark_verdict "$out/spark-cluster.log" "$rc"
    cluster="$SC_LAST_VERDICT"
    kv SPARK_CLUSTER_DETAIL "exit=$rc app=${SC_LAST_APP:-none} yarn_state=${SC_LAST_STATE:-none} $(detail_or_error "$cluster" "$out/spark-cluster.log")"
    stage_ok

    stage spark-client-populate-true
    write_spark_conf true
    rc=0
    run_spark_submit "$out/spark-client-populate-true.log" client org.apache.spark.examples.SparkPi "$examples" "" 10 || rc=$?
    spark_verdict "$out/spark-client-populate-true.log" "$rc"
    pop_true="$SC_LAST_VERDICT"
    kv SPARK_POPULATE_TRUE_DETAIL "exit=$rc app=${SC_LAST_APP:-none} yarn_state=${SC_LAST_STATE:-none} $(detail_or_error "$pop_true" "$out/spark-client-populate-true.log")"
    write_spark_conf false
    stage_ok
  fi
  kv SPARK_ON_YARN "client:$client cluster:$cluster"
  kv POPULATE_HADOOP_CLASSPATH_FALSE "client:$client cluster:$cluster"
  kv POPULATE_HADOOP_CLASSPATH_TRUE "client:$pop_true"
  kv SPARK_DYNAMIC_ALLOCATION off
  kv SPARK_SCOPE "single node: Spark on YARN on one node is not distributed execution"

  stage report
  write_json "$out/capability.json"
  scan_no_secrets "$out/capability.json"
  local f2
  for f2 in "$out"/*.log; do
    [[ -f "$f2" ]] || continue
    scan_no_secrets "$f2"
  done
  aws s3 cp "$out/capability.json" "s3://$data_bucket/reports/capability/$stamp/capability.json" --only-show-errors
  for f2 in "$out"/*.log; do
    [[ -f "$f2" ]] || continue
    aws s3 cp "$f2" "s3://$data_bucket/reports/capability/$stamp/${f2##*/}" --only-show-errors
  done
  printf 'CAPABILITY_REPORT=s3://%s/reports/capability/%s/capability.json\n' "$data_bucket" "$stamp"
  stage_ok
  printf 'CAPABILITY_COMPLETE MAPREDUCE=%s SPARK_ON_YARN=client:%s cluster:%s\n' "$mr" "$client" "$cluster"
}

capability_main "$@"
