#!/usr/bin/env bash
# run-s-pipeline.sh: the processing half of the S run, on the EMR primary node as one
# command-runner.jar step. Copies inputs and jars from S3, then runs in order:
#   1. Hadoop    hadoop jar <thin jar> scholarchain.hadoop.jobs.PipelineTool (MapReduce)
#   2. ER        java -cp ... scholarchain.er.ErBatch --run-root --out-root
#   3. Spark     spark-submit --master yarn (Spark 4.0.5 distribution) CollaborationPipeline
#   4. Export    spark-submit --master yarn JsonlExport
#   5. publish   copy the results to run prefixes in the data bucket
# Written, not executed: every command below is UNVERIFIED until the first cluster, and
# the first S run waits for scripts/emr/capability-check.sh to pass.
#
# Usage (the state machine runs it):
#   bash run-s-pipeline.sh <data-bucket> <artifacts-bucket> <snapshot> <snapshots> <run-id>
#     snapshot   id of the raw/<snapshot>/ input prefix (A-Za-z0-9._-)
#     snapshots  the value of -Dscholarchain.run.snapshots, naming exactly dblp plus the
#                supplied datasets, e.g. dblp=2026-11-05,openalex=2026-11-07,...
#     run-id     pinned with -Dscholarchain.run.id (otherwise each launch gets a new id)
#
# Inputs read:  s3://<data>/raw/<snapshot>/{dblp-subset.xml,openalex.jsonl,crossref.jsonl,orcid.jsonl}
#               s3://<artifacts>/jars/{hadoop,entity-resolution,spark}/ (thin jar + lib/)
#               s3://<artifacts>/jars/spark-4.0.5/ (tarball, jars archive, checksum file)
# Outputs:      s3://<data>/curated/runs/<run-id>/{hadoop,er}/
#               s3://<data>/graph/runs/<run-id>/            (Spark run=<id>/ directory)
#               s3://<data>/semantic-context/runs/<run-id>/ (JsonlExport output)
#               s3://<data>/reports/runs/<run-id>/          (logs and run-record.env)
# All five prefixes are inside the prefixes the emr_ec2 role may write.
#
# Paths. Spark, ER and JsonlExport take java.nio paths, so every path handed to them is
# a plain absolute local path, and the Hadoop job gets explicit file:/// URIs (a plain
# path would resolve against the default filesystem, HDFS on EMR). The default
# filesystem stays HDFS for YARN staging: fs.defaultFS is not overridden. All data
# therefore lives on this one node's disk: this is data-size scaling on a single-node
# EMR cluster (Spark and MapReduce on YARN), not distributed execution, and it breaks
# on a second node.
SC_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/emr/lib-emr.sh
source "$SC_SCRIPT_DIR/lib-emr.sh"

pipeline_main() {
  install_trap
  local data_bucket="${1:-}" artifacts_bucket="${2:-}" snapshot="${3:-}" snapshots="${4:-}" run_id="${5:-}"
  local work raw hout erout sout xout rep rc
  stage validate-arguments
  validate_bucket "data bucket" "$data_bucket"
  validate_bucket "artifacts bucket" "$artifacts_bucket"
  validate_id snapshot "$snapshot"
  validate_snapshots "$snapshots"
  validate_id run-id "$run_id"
  work="$SC_HOME/run/$run_id"
  raw="$work/raw"; hout="$work/hadoop-out"; erout="$work/er-out"; sout="$work/spark-out"
  xout="$work/export-out"; rep="$work/reports"
  [[ ! -e "$work" ]] || die "$work already exists: FileOutputFormat refuses an existing output directory, so a rerun needs a new run id"
  mkdir -p "$raw" "$rep"
  kv RUN_ID "$run_id"
  kv RAW_SNAPSHOT "$snapshot"
  kv RUN_SNAPSHOTS "$snapshots"
  kv RUN_USER "$(id -un) uid=$(id -u) gid=$(id -g)"
  kv RUN_SCOPE "data-size scaling on a single-node EMR cluster (Spark and MapReduce on YARN)"
  stage_ok

  stage java
  find_java
  [[ -n "$SC_JAVA_HOME" ]] || die "no JDK 17 or later on this node: enable the java bootstrap (emr_install_java_bootstrap) and relaunch"
  kv JAVA_HOME_SELECTED "$SC_JAVA_HOME"
  kv JAVA_MAJOR "$SC_JAVA_MAJOR"
  stage_ok

  stage fetch-inputs
  aws s3 cp "s3://$data_bucket/raw/$snapshot/" "$raw/" --recursive --only-show-errors
  local f
  for f in dblp-subset.xml openalex.jsonl crossref.jsonl orcid.jsonl; do
    [[ -s "$raw/$f" ]] || die "missing or empty input: raw/$snapshot/$f"
    kv "INPUT_BYTES_${f//[.-]/_}" "$(wc -c < "$raw/$f" | tr -d ' ')"
  done
  local m
  for m in hadoop entity-resolution spark; do
    mkdir -p "$work/jars/$m"
    aws s3 cp "s3://$artifacts_bucket/jars/$m/" "$work/jars/$m/" --recursive --only-show-errors
    kv "JARS_$(printf '%s' "$m" | tr 'a-z-' 'A-Z_')" "$(find "$work/jars/$m" -name '*.jar' | wc -l | tr -d ' ') jar(s)"
  done
  stage_ok

  stage spark-stage
  stage_spark "$artifacts_bucket"
  write_spark_conf "$(conf_value POPULATE_HADOOP_CLASSPATH false '^(true|false)$')"
  kv POPULATE_HADOOP_CLASSPATH "$(conf_value POPULATE_HADOOP_CLASSPATH false '^(true|false)$')"
  stage_ok

  stage hadoop
  local hjar hlibs hcp hexcl="^hadoop-"
  hjar="$(main_jar "$work/jars/hadoop")"
  hlibs="$(lib_jars_csv "$work/jars/hadoop/lib" "$hexcl")"
  hcp="$(printf '%s' "$hlibs" | tr ',' ':')"
  [[ -n "$hlibs" ]] || die "no library jars under jars/hadoop/lib after exclusions"
  kv HADOOP_EXCLUDED_LIB_JARS "$(lib_jars_excluded "$work/jars/hadoop/lib" "$hexcl")"
  # Dataset names come from the file names (openalex, crossref, orcid): PipelineTool
  # refuses a name it has no normalizer for.
  local dargs=()
  for f in openalex crossref orcid; do
    dargs+=("$f=file://$raw/$f.jsonl")
  done
  rc=0
  # HADOOP_CLASSPATH reaches only the submitting JVM; -libjars ships the jars to the
  # tasks (it works only if PipelineTool passes its Configuration to the jobs, which
  # hadoop-engineer fixes in work order 23: UNVERIFIED until the capability check or
  # this run shows it). Explicit JAVA_HOME for the AM and the tasks, in case the node
  # manager's default JDK is older than 17.
  HADOOP_CLASSPATH="$hcp" JAVA_HOME="$SC_JAVA_HOME" timeout 1500 \
    hadoop jar "$hjar" scholarchain.hadoop.jobs.PipelineTool \
    -libjars "$hlibs" \
    "-Dscholarchain.run.id=$run_id" \
    "-Dscholarchain.run.snapshots=$snapshots" \
    "-Dmapreduce.map.env=JAVA_HOME=$SC_JAVA_HOME" \
    "-Dmapreduce.reduce.env=JAVA_HOME=$SC_JAVA_HOME" \
    "-Dyarn.app.mapreduce.am.env=JAVA_HOME=$SC_JAVA_HOME" \
    "file://$hout" "file://$raw/dblp-subset.xml" "${dargs[@]}" > "$rep/hadoop.log" 2>&1 || rc=$?
  tail -n 25 "$rep/hadoop.log" || true
  [[ "$rc" -eq 0 ]] || die "PipelineTool exited $rc: $(last_error_line "$rep/hadoop.log")"
  local mr="unknown"
  if grep -qE 'job_local[0-9_]*' "$rep/hadoop.log"; then
    mr="local"
    note "MapReduce in local mode on an EMR node (job id job_local...)"
  elif grep -qE 'job_[0-9]{10,}_[0-9]+' "$rep/hadoop.log"; then
    mr="yarn"
  fi
  kv MAPREDUCE "$mr"
  kv MAPREDUCE_DETAIL "$(grep -oE 'job_(local)?[0-9_]+' "$rep/hadoop.log" | head -n 1)"
  [[ "$mr" != "unknown" ]] || die "cannot tell the MapReduce framework from the Hadoop log"
  yarn application -list -appTypes MAPREDUCE -appStates ALL 2>/dev/null | head -n 20 || true
  # Container user and permissions for file: output are unknown (UNVERIFIED): record
  # them, then make the output readable by whoever runs the java.nio tools.
  kv HADOOP_OUT_OWNER_MODE "$(stat -c '%U:%G %a' "$hout" 2>/dev/null || echo unknown)"
  chmod -R a+rX "$hout"
  kv HADOOP_OUT_OWNER_MODE_AFTER "$(stat -c '%U:%G %a' "$hout" 2>/dev/null || echo unknown)"
  local snapdirs
  snapdirs="$(find "$hout" -type d -name 'snapshot=*' | LC_ALL=C sort)"
  [[ -n "$snapdirs" && "$(printf '%s\n' "$snapdirs" | wc -l | tr -d ' ')" -eq 1 ]] || die "expected exactly one snapshot=<id> directory under the Hadoop output, found: ${snapdirs:-none}"
  local snapdir="$snapdirs"
  kv HADOOP_SNAPSHOT_DIR "$snapdir"
  stage_ok

  stage entity-resolution
  local ejar elibs
  ejar="$(main_jar "$work/jars/entity-resolution")"
  elibs="$work/jars/entity-resolution/lib"
  mkdir -p "$erout"
  rc=0
  timeout 1500 "$SC_JAVA_HOME/bin/java" -Xmx3g -cp "$ejar:$elibs/*" scholarchain.er.ErBatch \
    --run-root "$snapdir" --out-root "$erout" > "$rep/er.log" 2>&1 || rc=$?
  tail -n 15 "$rep/er.log" || true
  [[ "$rc" -eq 0 ]] || die "ErBatch exited $rc: $(last_error_line "$rep/er.log")"
  grep -q 'ER-BATCH RESULT: SUCCESS' "$rep/er.log" || die "ErBatch did not print ER-BATCH RESULT: SUCCESS"
  local erdir
  erdir="$(sed -n 's/^ER-BATCH output: //p' "$rep/er.log" | tail -n 1)"
  [[ -n "$erdir" && -d "$erdir" ]] || die "cannot find the ER run directory from the ER-BATCH output line"
  kv ER_RUN_DIR "$erdir"
  chmod -R a+rX "$erout"
  stage_ok

  stage spark
  local sjar sjars sexcl="^(spark-|hadoop-)" mode verdict
  sjar="$(main_jar "$work/jars/spark")"
  sjars="$(lib_jars_csv "$work/jars/spark/lib" "$sexcl")"
  kv SPARK_EXCLUDED_LIB_JARS "$(lib_jars_excluded "$work/jars/spark/lib" "$sexcl")"
  mode="$(conf_value DEPLOY_MODE client '^(client|cluster)$')"
  mkdir -p "$sout"
  # No --master application argument: spark-submit supplies the master (work order 18 makes
  # the application honor it). Without that change the application would pick local[2].
  rc=0
  run_spark_submit "$rep/spark.log" "$mode" scholarchain.spark.CollaborationPipeline "$sjar" "$sjars" \
    --er-run "$erdir" --hadoop-root "$snapdir" --out-root "$sout" || rc=$?
  tail -n 20 "$rep/spark.log" || true
  verdict="$(spark_verdict "$rep/spark.log" "$rc")"
  kv SPARK_ON_YARN "$mode:$verdict"
  kv SPARK_APPLICATION "${SC_LAST_APP:-none} state=${SC_LAST_STATE:-none}"
  [[ "$verdict" == "yes" ]] || die "CollaborationPipeline did not run as a successful YARN application (exit $rc): $(last_error_line "$rep/spark.log")"
  # The success wording after "SPARK-PIPELINE RESULT: " was not read, so a missing
  # SUCCESS line is reported, not fatal: the YARN final state above decided.
  grep -q 'SPARK-PIPELINE RESULT: SUCCESS' "$rep/spark.log" || note "no 'SPARK-PIPELINE RESULT: SUCCESS' line in the Spark log: read it"
  local srun
  srun="$(find "$sout" -maxdepth 1 -type d -name 'run=*' | LC_ALL=C sort)"
  [[ -n "$srun" && "$(printf '%s\n' "$srun" | wc -l | tr -d ' ')" -eq 1 ]] || die "expected exactly one run=<id> directory under the Spark output, found: ${srun:-none}"
  kv SPARK_RUN_DIR "$srun"
  chmod -R a+rX "$sout"
  stage_ok

  stage export
  mkdir -p "$xout"
  rc=0
  run_spark_submit "$rep/export.log" "$mode" scholarchain.spark.JsonlExport "$sjar" "$sjars" "$srun" "$xout/export" || rc=$?
  tail -n 15 "$rep/export.log" || true
  verdict="$(spark_verdict "$rep/export.log" "$rc")"
  kv EXPORT_ON_YARN "$mode:$verdict"
  [[ "$verdict" == "yes" ]] || die "JsonlExport did not run as a successful YARN application (exit $rc): $(last_error_line "$rep/export.log")"
  chmod -R a+rX "$xout"
  stage_ok

  stage publish
  kv RUN_RESULT_PREFIXES "curated/runs/$run_id/ graph/runs/$run_id/ semantic-context/runs/$run_id/ reports/runs/$run_id/"
  write_json "$rep/run-record.json"
  printf '%s' "$SC_KV" > "$rep/run-record.env"
  local lf
  for lf in "$rep"/*; do
    scan_no_secrets "$lf"
  done
  publish "$hout" "s3://$data_bucket/curated/runs/$run_id/hadoop/"
  publish "$erout" "s3://$data_bucket/curated/runs/$run_id/er/"
  publish "$sout" "s3://$data_bucket/graph/runs/$run_id/"
  publish "$xout" "s3://$data_bucket/semantic-context/runs/$run_id/"
  publish "$rep" "s3://$data_bucket/reports/runs/$run_id/"
  stage_ok
  printf 'PIPELINE_COMPLETE run_id=%s MAPREDUCE=%s SPARK_ON_YARN=%s:yes\n' "$run_id" "$mr" "$mode"
}

pipeline_main "$@"
