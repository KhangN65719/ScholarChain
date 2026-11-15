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
#     run-id     the path-safe slug run-v1-<lower-case UUID> (43 characters), anything else is
#                refused. The slug names the work directory and the S3 prefixes; the Hadoop
#                command gets the stable form run:v1:<uuid> with -Dscholarchain.run.id (the
#                form core requires). Make one with
#                  printf 'run-v1-%s' "$(uuidgen | tr 'A-Z' 'a-z')"
#
# Inputs read:  s3://<data>/raw/<snapshot>/{dblp-subset.xml,openalex.jsonl,crossref.jsonl,orcid.jsonl}
#               s3://<artifacts>/jars/{hadoop,entity-resolution,spark}/ (thin jar + lib/)
#               s3://<artifacts>/jars/spark-4.0.5/ (tarball, jars archive, checksum file)
# Outputs:      s3://<data>/curated/runs/<run-id>/{hadoop,er}/
#               s3://<data>/graph/runs/<run-id>/            (Spark run=<id>/ directory)
#               s3://<data>/semantic-context/runs/<run-id>/ (JsonlExport output)
#               s3://<data>/reports/runs/<run-id>/          (logs and run-record.env)
# All five prefixes are inside the prefixes the emr_ec2 role may write.
# After every finished stage (and after the Hadoop classification) a progress checkpoint
#               s3://<data>/reports/runs/<run-id>/progress/ is overwritten with run-record.env,
#               progress.env and the small logs so far, best effort (at most 60 s, never
#               aborts): a kill from outside, which skips the exit trap, still leaves the
#               evidence up to the last finished stage. STAGE_SECONDS_<NAME>= lines give
#               each stage's elapsed seconds.
# On a failed stage the exit trap also publishes the reports directory to
#               s3://<data>/reports/runs/<run-id>/failed/<UTC stamp>/ (best effort, after
#               the key-material scan) and prints FAILED_LOGS_PUBLISHED=<s3 uri>.
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
  validate_run_id "$run_id"
  local stable_run_id="$SC_STABLE_RUN_ID"
  work="$SC_HOME/run/$run_id"
  raw="$work/raw"; hout="$work/hadoop-out"; erout="$work/er-out"; sout="$work/spark-out"
  xout="$work/export-out"; rep="$work/reports"
  [[ ! -e "$work" ]] || die "$work already exists: FileOutputFormat refuses an existing output directory, so a rerun needs a new run id"
  mkdir -p "$raw" "$rep"
  register_failure_logs "$rep" "$data_bucket" "$run_id"
  kv RUN_ID "$run_id"
  kv RUN_ID_STABLE "$stable_run_id"
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
  # Single node, one run directory: the YARN containers (and the MapReduce output
  # committer in the application master) may run as the yarn user, not as this one.
  # Everything is under $SC_HOME/run/<id> and read or written through file:/// URIs.
  # What is changed, and why:
  #   /home/hadoop and $SC_HOME   a+x: traverse only, so the path can be walked; nothing
  #                               in them becomes listable or readable.
  #   $raw                        a+rX recursive: the inputs the map tasks read.
  #   $work, $sout, $xout         1777 (world-writable with the sticky bit): another user
  #                               can create its output directories here (the Hadoop
  #                               output directory in $work, the Spark and export output
  #                               directories in $sout and $xout) but cannot delete or
  #                               rename entries this user owns (raw inputs, jars, reports).
  #                               Plain a+rwx would allow that. The owner of a sticky
  #                               directory can still remove everything in it.
  # Directories that a task creates inside those get their modes from the umask of
  # SC_FS_UMASK (-Dfs.permissions.umask-mode below, spark.hadoop.fs.permissions.umask-mode
  # in spark-defaults.conf): without it a directory the driver creates with mode 755
  # would stay unwritable for executors running as another user. No credential, no
  # ~/.aws and nothing outside $SC_HOME/run/<id> except the traverse bit is touched.
  local sc_home_parent="${SC_HOME%/*}"
  kv PERMS_USER "$(id -un) uid=$(id -u) gid=$(id -g) umask=$(umask)"
  kv PERMS_HOME_DIR_BEFORE "$sc_home_parent $(mode_of "$sc_home_parent")"
  kv PERMS_SC_HOME_BEFORE "$SC_HOME $(mode_of "$SC_HOME")"
  kv PERMS_RUN_DIR "$SC_HOME/run $(mode_of "$SC_HOME/run") (not changed: it must already be traversable)"
  kv PERMS_WORK_BEFORE "$work $(mode_of "$work")"
  sc_chmod a+x "$sc_home_parent" "$SC_HOME"
  sc_chmod -R a+rX "$raw"
  sc_chmod 1777 "$work"
  kv PERMS_HOME_DIR_AFTER "$sc_home_parent $(mode_of "$sc_home_parent")"
  kv PERMS_SC_HOME_AFTER "$SC_HOME $(mode_of "$SC_HOME")"
  kv PERMS_WORK_AFTER "$work $(mode_of "$work")"
  kv PERMS_FS_UMASK_MODE "$SC_FS_UMASK (-Dfs.permissions.umask-mode for Hadoop, spark.hadoop.fs.permissions.umask-mode for Spark)"
  record_hadoop_effective_conf
  local hjar hlibs hcp hexcl="^hadoop-"
  hjar="$(main_jar "$work/jars/hadoop")"
  hlibs="$(lib_jars_csv "$work/jars/hadoop/lib" "$hexcl")"
  hcp="$(printf '%s' "$hlibs" | tr ',' ':')"
  [[ -n "$hlibs" ]] || die "no library jars under jars/hadoop/lib after exclusions"
  kv HADOOP_EXCLUDED_LIB_JARS "$(lib_jars_excluded "$work/jars/hadoop/lib" "$hexcl")"
  # Dataset names come from the file names (openalex, crossref, orcid): PipelineTool
  # refuses a name it has no normalizer for.
  # The names are listed in sorted order: the driver runs one normalization job per
  # dataset in that order.
  local dsets=(crossref openalex orcid) dargs=()
  for f in "${dsets[@]}"; do
    dargs+=("$f=file://$raw/$f.jsonl")
  done
  rc=0
  # HADOOP_CLASSPATH reaches only the submitting JVM; -libjars ships the jars to the
  # tasks (it works only if PipelineTool passes its Configuration to the jobs, which
  # hadoop-engineer fixes in work order 23: UNVERIFIED until the capability check or
  # this run shows it). Explicit JAVA_HOME for the AM and the tasks, in case the node
  # manager's default JDK is older than 17.
  # HADOOP_ROOT_LOGGER=INFO,console is what the Hadoop 3 shell scripts already default
  # to for a client command (read from memory of hadoop-functions.sh, UNVERIFIED on this
  # node), so it changed nothing: S run 3 kept no job id line in hadoop.log although
  # PipelineDriver waits with waitForCompletion(true), whose "Running job: job_..." line
  # is an INFO message. The cause is therefore not this variable but the logging
  # configuration of the client JVM (a log4j file or a second slf4j binding on the
  # class path), UNVERIFIED. The job ids are recovered below from mapred job -list, then
  # derived from the ResourceManager's application ids.
  # Stdout and stderr both go to hadoop.log.
  # -Dio.compression.codecs replaces EMR's core-site list, which names the LZO codec
  # classes that this class path lacks (S run 1, D29: "Compression codec
  # com.hadoop.compression.lzo.LzoCodec not found", cause UNVERIFIED). It is a generic
  # option, so it stays before the positional arguments; PipelineTool hands its
  # Configuration to every job (ToolConfigurationPropagationSuite), so the tasks get it.
  HADOOP_ROOT_LOGGER="INFO,console" HADOOP_CLASSPATH="$hcp" JAVA_HOME="$SC_JAVA_HOME" timeout 1500 \
    hadoop jar "$hjar" scholarchain.hadoop.jobs.PipelineTool \
    -libjars "$hlibs" \
    "-Dio.compression.codecs=$SC_CODECS" \
    "-Dmapreduce.map.output.compress.codec=$SC_MAP_CODEC" \
    "-Dmapreduce.output.fileoutputformat.compress=false" \
    "-Dfs.permissions.umask-mode=$SC_FS_UMASK" \
    "-Dscholarchain.run.id=$stable_run_id" \
    "-Dscholarchain.run.snapshots=$snapshots" \
    "-Dmapreduce.map.env=JAVA_HOME=$SC_JAVA_HOME" \
    "-Dmapreduce.reduce.env=JAVA_HOME=$SC_JAVA_HOME" \
    "-Dyarn.app.mapreduce.am.env=JAVA_HOME=$SC_JAVA_HOME" \
    "file://$hout" "file://$raw/dblp-subset.xml" "${dargs[@]}" > "$rep/hadoop.log" 2>&1 || rc=$?
  tail -n 25 "$rep/hadoop.log" || true
  [[ "$rc" -eq 0 ]] || die "PipelineTool exited $rc: $(last_error_line "$rep/hadoop.log")"
  # The verdict (work order 48, human decision D30, replaces the work order 43 rule).
  # PipelineDriver runs one job for DBLP extraction, one source-normalization job per
  # dataset, then identifier join, blocking, candidate features, team expansion and
  # graph export: 1 + datasets + 5 jobs. The ResourceManager's MAPREDUCE application
  # list proves YARN execution (the local runner never registers with it):
  #   yarn     the list holds exactly the expected number of applications and no
  #            job_local id appears in the log. MAPREDUCE_PROOF is job-ids-in-log when the
  #            log also carried all expected job ids, else resourcemanager-application-ids.
  #   local    a job_local id appears in the log and neither the log nor the list shows a
  #            YARN job or application ("MapReduce in local mode on an EMR node").
  #   mixed    a job_local id appears together with YARN job ids or applications.
  #   unknown  anything else: the list is empty or its count differs from the expected one.
  # The real job ids are recorded separately (choose_mapreduce_job_ids), with their
  # source, and never change the verdict. No verdict stops the pipeline: the human reads
  # the report.
  local mr="unknown" mrdetail="" mrattn="yes" mrproof="none" mrapps="" mrapps_n=0 mrexpected mrorder="" mrn=0 mrid mrkind
  mrexpected=$((${#dargs[@]} + 6))
  mrorder="dblp-extraction"
  for f in "${dsets[@]}"; do
    mrorder="$mrorder source-normalization/$f"
  done
  mrorder="$mrorder identifier-join blocking candidate-features team-expansion graph-export"
  local mrstages=()
  read -r -a mrstages <<< "$mrorder"
  classify_job_ids "$rep/hadoop.log"
  mrapps="$(yarn_mapreduce_apps "$rep/mapreduce-yarn-list.log")"
  mrapps_n="$(words_in "$mrapps")"
  choose_mapreduce_job_ids "$mrexpected" "$mrapps" "$rep/mapreduce-job-list.log"
  local mrfound=()
  read -r -a mrfound <<< "$SC_MRF_IDS"
  {
    printf '# MapReduce job ids of the Hadoop stage. Source: %s\n' "$SC_MRF_SOURCE"
    case "$SC_MRF_SOURCE" in
      hadoop-log) printf '# The ids were found in hadoop.log, in order of first appearance.\n' ;;
      mapred-job-list) printf '# The ids come from mapred job -list (see mapreduce-job-list.log), sorted by timestamp and sequence.\n' ;;
      derived-from-application-ids) printf '# The ids are DERIVED, not observed: job_<ts>_<n> is the ResourceManager application id application_<ts>_<n> with the prefix replaced (a Hadoop naming property).\n' ;;
      *) printf '# No job id was found by any source.\n' ;;
    esac
    printf '# The stage column is positional and inferred: no source names the stage of a job id.\n'
    printf '# The driver runs the stages in this order: %s\n' "$mrorder"
    for mrid in ${mrfound[@]+"${mrfound[@]}"}; do
      printf '%s %s %s\n' "$((mrn + 1))" "$mrid" "${mrstages[$mrn]:-beyond-the-expected-stages}"
      mrn=$((mrn + 1))
    done
  } > "$rep/mapreduce-job-ids.txt"
  mrkind=$((SC_MR_YARN + mrapps_n))
  if [[ "$SC_MR_LOCAL" -gt 0 && "$mrkind" -gt 0 ]]; then
    mr="mixed"
    mrdetail="$SC_MR_LOCAL local job id(s) job_local... and YARN evidence together ($SC_MR_YARN YARN job id(s) in the log, $mrapps_n MAPREDUCE application(s) on the ResourceManager), $mrexpected expected"
  elif [[ "$SC_MR_LOCAL" -gt 0 ]]; then
    mr="local"
    mrdetail="$SC_MR_LOCAL local job id(s) job_local... found, no YARN job or application seen, $mrexpected expected"
  elif [[ "$mrapps_n" -eq "$mrexpected" ]]; then
    mr="yarn"
    mrattn="no"
    mrproof="resourcemanager-application-ids"
    if [[ "$SC_MR_YARN" -eq "$mrexpected" ]]; then
      mrproof="job-ids-in-log"
    fi
    mrdetail="$mrapps_n MAPREDUCE applications on the ResourceManager for $mrexpected expected stages, 0 job_local id in the log, $SC_MR_YARN job id(s) in the log"
  else
    mrdetail="the ResourceManager lists $mrapps_n MAPREDUCE application(s) against $mrexpected expected (empty or a different count), $SC_MR_YARN job id(s) and $SC_MR_LOCAL local id(s) in the log: not proven"
  fi
  kv MAPREDUCE "$mr"
  kv MAPREDUCE_PROOF "$mrproof"
  kv MAPREDUCE_DETAIL "$mrdetail"
  kv MAPREDUCE_STAGES_EXPECTED "$mrexpected, run in this order: $mrorder"
  kv MAPREDUCE_STAGE_MAPPING "positional and inferred, not read from any log; mapreduce-job-ids.txt lists each id with the stage at that position"
  kv MAPREDUCE_JOB_COUNT "$SC_MRF_N"
  kv MAPREDUCE_JOBS_YARN "$SC_MR_YARN"
  kv MAPREDUCE_JOBS_LOCAL "$SC_MR_LOCAL"
  kv MAPREDUCE_JOB_IDS_SOURCE "$SC_MRF_SOURCE"
  if [[ "$SC_MRF_SOURCE" == "derived-from-application-ids" ]]; then
    kv MAPREDUCE_JOB_IDS_DERIVATION "job_<ts>_<n> is application_<ts>_<n> with the prefix replaced: a Hadoop naming property, not an observation in the log"
  fi
  kv MAPREDUCE_JOB_IDS "${SC_MRF_IDS:-none}"
  kv MAPREDUCE_JOB_IDS_FILE "mapreduce-job-ids.txt (the full list; MAPREDUCE_JOB_IDS is cut at 400 characters)"
  kv MAPREDUCE_APPS_YARN_LIST "count=$mrapps_n (the proof for yarn when equal to the expected count; full output in mapreduce-yarn-list.log) ids=${mrapps:-none}"
  kv MAPREDUCE_ATTENTION "$mrattn"
  if [[ "$mr" == "local" || "$mr" == "mixed" ]]; then
    note "MapReduce in local mode on an EMR node ($SC_MR_LOCAL job_local... id(s) of $SC_MR_N found)"
  fi
  if [[ "$mrattn" == "yes" ]]; then
    note "ATTENTION: MAPREDUCE=$mr, not yarn for every stage: $mrdetail. The YARN list shows $mrapps_n MAPREDUCE application(s). The pipeline continues; read the report before calling this run done"
  fi
  # Checkpoint right after the classification: the verdict, the job ids and the
  # ResourceManager list are then in s3 even if a later stage is killed from outside.
  sc_publish_progress "after-hadoop-classification" || true
  # Container user and permissions for file: output are unknown (UNVERIFIED): record
  # them, then make the output readable by whoever runs the java.nio tools.
  kv HADOOP_OUT_OWNER_MODE "$(mode_of "$hout")"
  # Which user wrote the output files, as they stand before the chmod: "user:mode=count".
  kv HADOOP_OUT_FILE_OWNERS_MODES "$( { find "$hout" -type f -printf '%u:%m\n' | LC_ALL=C sort | uniq -c | awk '{ printf "%s%s=%s", (NR > 1 ? " " : ""), $2, $1 }'; } 2>/dev/null || true)"
  sc_chmod_readable_tree "$hout"
  kv HADOOP_OUT_OWNER_MODE_AFTER "$(mode_of "$hout")"
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
  sc_chmod_readable_tree "$erout"
  stage_ok

  stage spark
  local sjar sjars sexcl="^(spark-|hadoop-)" mode verdict
  sjar="$(main_jar "$work/jars/spark")"
  sjars="$(lib_jars_csv "$work/jars/spark/lib" "$sexcl")"
  kv SPARK_EXCLUDED_LIB_JARS "$(lib_jars_excluded "$work/jars/spark/lib" "$sexcl")"
  mode="$(conf_value DEPLOY_MODE client '^(client|cluster)$')"
  mkdir -p "$sout"
  sc_chmod 1777 "$sout"
  # No --master application argument: spark-submit supplies the master (work order 18 makes
  # the application honor it). Without that change the application would pick local[2].
  rc=0
  run_spark_submit "$rep/spark.log" "$mode" scholarchain.spark.CollaborationPipeline "$sjar" "$sjars" \
    --er-run "$erdir" --hadoop-root "$snapdir" --out-root "$sout" || rc=$?
  tail -n 20 "$rep/spark.log" || true
  spark_verdict "$rep/spark.log" "$rc"
  verdict="$SC_LAST_VERDICT"
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
  sc_chmod_readable_tree "$sout"
  stage_ok

  stage export
  mkdir -p "$xout"
  sc_chmod 1777 "$xout"
  rc=0
  run_spark_submit "$rep/export.log" "$mode" scholarchain.spark.JsonlExport "$sjar" "$sjars" "$srun" "$xout/export" || rc=$?
  tail -n 15 "$rep/export.log" || true
  spark_verdict "$rep/export.log" "$rc"
  verdict="$SC_LAST_VERDICT"
  kv EXPORT_ON_YARN "$mode:$verdict"
  kv EXPORT_APPLICATION "${SC_LAST_APP:-none} state=${SC_LAST_STATE:-none}"
  [[ "$verdict" == "yes" ]] || die "JsonlExport did not run as a successful YARN application (exit $rc): $(last_error_line "$rep/export.log")"
  sc_chmod_readable_tree "$xout"
  stage_ok

  stage publish
  kv RUN_RESULT_PREFIXES "curated/runs/$run_id/ graph/runs/$run_id/ semantic-context/runs/$run_id/ reports/runs/$run_id/"
  # The published record cannot hold the publish stage's own seconds or the final total:
  # both are printed on stdout after the copy (STAGE_SECONDS_PUBLISH, STAGE_SECONDS_TOTAL).
  record_total_seconds STAGE_SECONDS_TOTAL_BEFORE_PUBLISH
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
  record_total_seconds STAGE_SECONDS_TOTAL
  if [[ "$mrattn" == "yes" ]]; then
    note "ATTENTION: MAPREDUCE=$mr, not yarn for every stage (see MAPREDUCE_DETAIL in run-record.env)"
  fi
  printf 'PIPELINE_COMPLETE run_id=%s MAPREDUCE=%s mr_jobs_found=%s mr_jobs_yarn=%s mr_jobs_local=%s mr_jobs_expected=%s mr_yarn_list_apps=%s SPARK_ON_YARN=%s:yes mr_proof=%s mr_job_ids_source=%s total_seconds=%s\n' \
    "$run_id" "$mr" "$SC_MRF_N" "$SC_MR_YARN" "$SC_MR_LOCAL" "$mrexpected" "$mrapps_n" "$mode" "$mrproof" "$SC_MRF_SOURCE" "$((SECONDS - SC_T0))"
}

pipeline_main "$@"
