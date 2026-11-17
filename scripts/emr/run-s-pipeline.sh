#!/usr/bin/env bash
# run-s-pipeline.sh: the processing half of the S run, on the EMR primary node as one
# command-runner.jar step. Copies inputs and jars from S3, then runs in order:
# The steps below are listed in order of execution. The letters are not the STAGE[n] numbers
# (the export is stage 8):
#   a. preflight (a sub-step of the spark-stage stage, before the Hadoop stage, about 40 s,
#                at most 90 s): class-valued configuration, the four job main classes and one
#                text and one Parquet write, in one spark-shell; see scripts/README.md
#   b. Hadoop    hadoop jar <thin jar> scholarchain.hadoop.jobs.PipelineTool (MapReduce)
#   c. ER        java -cp ... scholarchain.er.ErBatch --run-root --out-root
#   d. Spark     spark-submit --master yarn (Spark 4.0.5 distribution) CollaborationPipeline
#   e. Export    spark-submit --master yarn JsonlExport
#   f. publish   copy the results to run prefixes in the data bucket
# Written, not executed: every command below is UNVERIFIED until the first cluster, and
# the first S run waits for scripts/emr/capability-check.sh to pass.
#
# Usage (the state machine runs it):
#   bash run-s-pipeline.sh <data-bucket> <artifacts-bucket> <snapshot> <snapshots> <run-id> [profile]
#     profile    optional (Phase 15): 0 to 32 characters of a-z0-9-. It selects the
#                "<profile>.KEY=VALUE" lines of spark-yarn.conf over the plain KEY lines.
#                The state machine passes it only when the Terraform variable
#                pipeline_profile is set; without it every setting is the plain one.
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
#
# Phase 15 (Experiment B, core nodes): the paragraph above is the default path and is
# unchanged. When the cluster has a YARN node other than this one (setting DATA_PATH,
# auto by default, detects it with yarn node -list; file or hdfs force a choice) the
# Hadoop stage uses HDFS, the cluster's own default filesystem, for what its containers
# share: the inputs are put into hdfs:///user/hadoop/sc/run/<run-id>/raw, the MapReduce
# output is written under .../hadoop-out and is then copied to the same local $hout as
# before, and the HDFS run directory is removed. Everything after the Hadoop stage (ER,
# publish, S3 keys) reads the local copy and is unchanged. Why HDFS and not S3: the
# MapReduce FileOutputCommitter renames (a copy plus a delete on S3, and whether the EMR
# role may delete under curated/ was not read: UNVERIFIED); the Spark 4 client has no
# S3 filesystem (UNVERIFIED); HDFS renames are atomic and need no IAM change. The cost is one put and one get of a
# few megabytes, and HDFS blocks on core nodes, which is why the run directory is
# removed before the scale-in the experiment wants to observe.
# The Spark and export stages cannot follow: CollaborationPipeline and JsonlExport read
# and write java.nio paths and file: URIs (Inputs.fileUri, Algo.checkpoint, Files.isDirectory),
# so an executor on another node would not see the data. With a remote YARN node the
# two applications therefore run with --master local[N] on this node (setting
# SPARK_MASTER, auto by default; LOCAL_CORES, LOCAL_DRIVER_MEMORY). The MapReduce
# stage is what creates YARN demand for the core nodes. This is a recorded limit of
# Phase 15, not a claim about Spark scaling: SPARK_MASTER_USED and DATA_PATH_MODE are in
# the run record.
SC_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/emr/lib-emr.sh
source "$SC_SCRIPT_DIR/lib-emr.sh"

pipeline_main() {
  install_trap
  local data_bucket="${1:-}" artifacts_bucket="${2:-}" snapshot="${3:-}" snapshots="${4:-}" run_id="${5:-}"
  local profile="${6:-}"
  local work raw hout erout sout xout rep rc spark_events populate
  stage validate-arguments
  validate_bucket "data bucket" "$data_bucket"
  validate_bucket "artifacts bucket" "$artifacts_bucket"
  validate_id snapshot "$snapshot"
  validate_snapshots "$snapshots"
  validate_id run-id "$run_id"
  validate_run_id "$run_id"
  [[ "$profile" =~ ^[a-z0-9-]{0,32}$ ]] || die "profile must be 0 to 32 characters of a-z0-9-"
  SC_PROFILE="$profile"
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
  kv RUN_PROFILE "${profile:-none}"
  kv RUN_USER "$(id -un) uid=$(id -u) gid=$(id -g)"
  # RUN_SCOPE is recorded in the hadoop stage, once the data path and the Spark master
  # are resolved: it must describe the mode that actually ran (review 1, m2).
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
    kv "INPUT_LINES_${f//[.-]/_}" "$(wc -l < "$raw/$f" | tr -d ' ')"
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
  # Phase 15: the plain-text Spark event log (setting SPARK_EVENT_LOG, false unless a
  # profile sets it) is written here and copied into the reports before publish.
  SC_EVENT_LOG_DIR="$work/spark-events"
  spark_events="$(conf_value SPARK_EVENT_LOG false '^(true|false)$')" || exit 1
  kv SPARK_EVENT_LOG "$spark_events"
  populate="$(conf_value POPULATE_HADOOP_CLASSPATH false '^(true|false)$')" || exit 1
  write_spark_conf "$populate"
  kv POPULATE_HADOOP_CLASSPATH "$populate"
  # What the node's site files name for the old-API output committer (S run 5, export
  # stage): recorded as evidence only. spark-defaults.conf overrides it for both Spark
  # applications (write_spark_conf).
  kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS "$(conf_xml_value mapred.output.committer.class)"
  # Fail here, in about 40 s, when a class the Spark applications or the job jars need is
  # missing, instead of at the export stage an hour later (S run 5). A sub-step of this
  # stage on purpose: the stage numbers, and FAILED_STAGE=8 for the export, stay as they were.
  run_preflight "$work" "$rep"
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
  local hjar hlibs hcp hexcl="$SC_HADOOP_LIB_EXCLUDE"
  hjar="$(main_jar "$work/jars/hadoop")"
  hlibs="$(lib_jars_csv "$work/jars/hadoop/lib" "$hexcl")"
  hcp="$(printf '%s' "$hlibs" | tr ',' ':')"
  [[ -n "$hlibs" ]] || die "no library jars under jars/hadoop/lib after exclusions"
  kv HADOOP_EXCLUDED_LIB_JARS "$(lib_jars_excluded "$work/jars/hadoop/lib" "$hexcl")"
  # Dataset names come from the file names (openalex, crossref, orcid): PipelineTool
  # refuses a name it has no normalizer for.
  # The names are listed in sorted order: the driver runs one normalization job per
  # dataset in that order.
  # Phase 15: where the MapReduce containers read and write (see "Phase 15" in the header).
  # file (the default for a one-node cluster) keeps the file:/// URIs above exactly.
  local dmode dmode_conf hrun="" h_in h_out hextra=() hextra_text="" pc split mapmb redmb smaster local_cores run_scope nm_wait
  dmode_conf="$(conf_value DATA_PATH auto '^(auto|file|hdfs)$')" || exit 1
  dmode="$dmode_conf"
  if [[ "$dmode_conf" == "hdfs" ]]; then
    # Forced HDFS (the b-burst profile): the experiment needs a NodeManager on another
    # host. Wait a bounded time for it to register, then fail clearly. There is no
    # fallback to file:///, which would run the whole Hadoop stage on one node and
    # report it as a core-node run (review 1, M1). A count of unknown (yarn node -list
    # unreadable) is not a proof of absence: it is recorded and the run continues.
    # Resolved into a variable first: die inside a "$( )" argument ends only the subshell,
    # so an invalid NM_WAIT_SECONDS would reach the wait as an empty argument.
    nm_wait="$(conf_value NM_WAIT_SECONDS 300 '^(0|[1-9][0-9]{0,3})$')" || exit 1
    wait_for_remote_yarn_nodes "$nm_wait"
    if [[ "$SC_REMOTE_NODES" =~ ^[0-9]+$ && "$SC_REMOTE_NODES" -eq 0 ]]; then
      kv YARN_REMOTE_NODES "0 (RUNNING NodeManagers on a host other than this one)"
      die "DATA_PATH=hdfs is forced for profile '${SC_PROFILE:-none}' but yarn node -list shows no RUNNING NodeManager on a host other than this one, after waiting up to $nm_wait s: the core node did not register, so this run would not be a core-node run. Not falling back to file:///"
    fi
  else
    detect_remote_yarn_nodes
  fi
  if [[ "$dmode" == "auto" ]]; then
    if [[ "$SC_REMOTE_NODES" =~ ^[0-9]+$ && "$SC_REMOTE_NODES" -gt 0 ]]; then
      dmode="hdfs"
    else
      dmode="file"
    fi
  fi
  kv YARN_REMOTE_NODES "$SC_REMOTE_NODES (RUNNING NodeManagers on a host other than this one; unknown when yarn node -list gave nothing usable)"
  kv DATA_PATH_MODE "$dmode"
  # The Spark master is resolved here, with the data path, so RUN_SCOPE can say what runs.
  smaster="$(conf_value SPARK_MASTER auto '^(auto|yarn|local)$')" || exit 1
  if [[ "$smaster" == "auto" ]]; then
    if [[ "$SC_REMOTE_NODES" =~ ^[0-9]+$ && "$SC_REMOTE_NODES" -gt 0 ]]; then
      smaster="local"
    else
      smaster="yarn"
    fi
  fi
  local_cores="$(conf_value LOCAL_CORES 4 '^[1-9][0-9]?$')" || exit 1
  if [[ "$dmode" == "hdfs" && "$smaster" == "local" ]]; then
    run_scope="MapReduce on YARN with HDFS across the cluster's nodes ($SC_REMOTE_NODES remote NodeManager(s)); ER, Spark and export on the primary node only (Spark as local[$local_cores])"
  elif [[ "$dmode" == "file" && "$smaster" == "yarn" ]]; then
    run_scope="data-size scaling on a single-node EMR cluster (file:/// paths on the primary; MapReduce and Spark on YARN)"
  else
    run_scope="MapReduce data path $dmode ($SC_REMOTE_NODES remote NodeManager(s)); Spark master $smaster; ER on the primary node"
  fi
  kv RUN_SCOPE "$run_scope"
  h_in="file://$raw"
  h_out="file://$hout"
  if [[ "$dmode" == "hdfs" ]]; then
    hrun="/user/hadoop/sc/run/$run_id"
    timeout 60 hdfs dfs -mkdir -p "$hrun/raw" || die "hdfs dfs -mkdir -p $hrun/raw failed or took over 60 s"
    timeout 300 hdfs dfs -put "$raw/dblp-subset.xml" "$raw/crossref.jsonl" "$raw/openalex.jsonl" "$raw/orcid.jsonl" "$hrun/raw/" || die "hdfs dfs -put of the inputs to $hrun/raw failed"
    h_in="hdfs://$hrun/raw"
    h_out="hdfs://$hrun/hadoop-out"
    kv HDFS_RUN_DIR "$hrun"
  fi
  # Settings that default to nothing (Phase 15, P15-7): a value of 0 or an absent key
  # adds no option, so the default run is the one it always was. The partition count is
  # the reducer count of every MapReduce stage (modules/hadoop, PartitionCountSettingSuite).
  pc="$(conf_value PARTITION_COUNT 0 '^(0|[1-9][0-9]{0,3})$')" || exit 1
  split="$(conf_value SPLIT_MAX_BYTES 0 '^(0|[1-9][0-9]{0,8})$')" || exit 1
  mapmb="$(conf_value MAP_MEMORY_MB 0 '^(0|[1-9][0-9]{2,5})$')" || exit 1
  redmb="$(conf_value REDUCE_MEMORY_MB 0 '^(0|[1-9][0-9]{2,5})$')" || exit 1
  if [[ "$pc" -gt 0 ]]; then
    hextra+=("-Dscholarchain.run.partitionCount=$pc")
    hextra_text="$hextra_text -Dscholarchain.run.partitionCount=$pc"
  fi
  if [[ "$split" -gt 0 ]]; then
    hextra+=("-Dmapreduce.input.fileinputformat.split.maxsize=$split")
    hextra_text="$hextra_text -Dmapreduce.input.fileinputformat.split.maxsize=$split"
  fi
  if [[ "$mapmb" -gt 0 ]]; then
    hextra+=("-Dmapreduce.map.memory.mb=$mapmb")
    hextra_text="$hextra_text -Dmapreduce.map.memory.mb=$mapmb"
  fi
  if [[ "$redmb" -gt 0 ]]; then
    hextra+=("-Dmapreduce.reduce.memory.mb=$redmb")
    hextra_text="$hextra_text -Dmapreduce.reduce.memory.mb=$redmb"
  fi
  kv HADOOP_EXTRA_OPTIONS "${hextra_text:-none}"
  local dsets=(crossref openalex orcid) dargs=()
  for f in "${dsets[@]}"; do
    dargs+=("$f=$h_in/$f.jsonl")
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
  HADOOP_ROOT_LOGGER="INFO,console" HADOOP_CLASSPATH="$hcp" JAVA_HOME="$SC_JAVA_HOME" timeout -k 30 1500 \
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
    ${hextra[@]+"${hextra[@]}"} \
    "$h_out" "$h_in/dblp-subset.xml" "${dargs[@]}" > "$rep/hadoop.log" 2>&1 || rc=$?
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
  # Phase 15 (Experiment A observability, setting MR_COUNTERS, false unless a profile sets
  # it): mapred job -status per job, best effort, kept in the reports. The output format on
  # EMR 7.10.0 and whether the history server answers are UNVERIFIED; a job that gives
  # nothing leaves an empty file and never stops the run.
  local mr_counters mrc=0
  mr_counters="$(conf_value MR_COUNTERS false '^(true|false)$')" || exit 1
  if [[ "$mr_counters" == "true" ]]; then
    mkdir -p "$rep/mapreduce-counters"
    for mrid in ${mrfound[@]+"${mrfound[@]}"}; do
      [[ "$mrid" =~ ^job_[0-9]+_[0-9]+$ ]] || continue
      { timeout 60 mapred job -status "$mrid" 2>&1 || true; } > "$rep/mapreduce-counters/$mrid.txt"
      mrc=$((mrc + 1))
    done
    kv MAPREDUCE_COUNTERS_FILES "$mrc (mapreduce-counters/<job id>.txt, one job status query per job id)"
  fi
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
  # HDFS mode (Phase 15): bring the MapReduce output to the local $hout that ER, Spark and
  # publish read, then remove the HDFS run directory so no block of ours is left on a core
  # node that managed scaling may want to release. A failed copy is a failed stage; a failed
  # removal is only reported.
  if [[ "$dmode" == "hdfs" ]]; then
    timeout 600 hdfs dfs -get "$hrun/hadoop-out" "$hout" || die "hdfs dfs -get of $hrun/hadoop-out to $hout failed"
    kv HADOOP_OUT_FETCHED_FROM "hdfs://$hrun/hadoop-out ($(find "$hout" -type f | wc -l | tr -d ' ') files)"
    timeout 300 hdfs dfs -rm -r -skipTrash "$hrun" > "$rep/hdfs-cleanup.log" 2>&1 || note "hdfs dfs -rm -r $hrun failed: see hdfs-cleanup.log"
  fi
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
  # Ceiling, not an expectation: ER took about 103 s in S run 4. Override with SC_ER_TIMEOUT.
  timeout -k 30 "${SC_ER_TIMEOUT:-3000}" "$SC_JAVA_HOME/bin/java" -Xmx3g -cp "$ejar:$elibs/*" scholarchain.er.ErBatch \
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
  local sjar sjars sexcl="$SC_SPARK_LIB_EXCLUDE" mode verdict
  sjar="$(main_jar "$work/jars/spark")"
  sjars="$(lib_jars_csv "$work/jars/spark/lib" "$sexcl")"
  kv SPARK_EXCLUDED_LIB_JARS "$(lib_jars_excluded "$work/jars/spark/lib" "$sexcl")"
  mode="$(conf_value DEPLOY_MODE client '^(client|cluster)$')" || exit 1
  # Phase 15: with a YARN node other than this one the two applications run local[N] on
  # this node (header, "Phase 15"). yarn (the default on a one-node cluster) is the old path.
  # smaster was resolved in the hadoop stage (it is in RUN_SCOPE).
  local shuffle
  if [[ "$smaster" == "local" ]]; then
    SC_SPARK_MASTER="local[$local_cores]"
    SC_LOCAL_DRIVER_MEMORY="$(conf_value LOCAL_DRIVER_MEMORY 6g '^[0-9]+[gm]$')" || exit 1
    mode="local-master"
    kv SPARK_LOCAL_DRIVER_MEMORY "$SC_LOCAL_DRIVER_MEMORY"
  fi
  kv SPARK_MASTER_USED "$SC_SPARK_MASTER"
  shuffle="$(conf_value SHUFFLE_PARTITIONS 8 '^[1-9][0-9]{0,4}$')" || exit 1
  [[ "$shuffle" -le 10000 ]] || die "spark-yarn.conf: SHUFFLE_PARTITIONS must be 1 to 10000"
  kv SPARK_SHUFFLE_PARTITIONS "$shuffle"
  mkdir -p "$sout"
  sc_chmod 1777 "$sout"
  # No --master application argument: spark-submit supplies the master (work order 18 makes
  # the application honor it). Without that change the application would pick local[2].
  rc=0
  run_spark_submit "$rep/spark.log" "$mode" scholarchain.spark.CollaborationPipeline "$sjar" "$sjars" \
    --er-run "$erdir" --hadoop-root "$snapdir" --out-root "$sout" --partitions "$shuffle" || rc=$?
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
  # Phase 15 (review 1, M4): the run manifest (counts, outputs rows, partitioning) is
  # copied into the reports, so the experiment derive scripts can read output record
  # counts from reports/runs/<run-id>/ without the graph prefix.
  copy_report_file "$srun/run-manifest.json" "$rep/spark-run-manifest.json" SPARK_RUN_MANIFEST_COPY
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
  # Phase 15 (review 1, M4): the export manifest (rows per exported table) for the derive scripts.
  copy_report_file "$xout/export/export-manifest.json" "$rep/export-manifest.json" EXPORT_MANIFEST_COPY
  sc_chmod_readable_tree "$xout"
  stage_ok

  stage publish
  kv RUN_RESULT_PREFIXES "curated/runs/$run_id/ graph/runs/$run_id/ semantic-context/runs/$run_id/ reports/runs/$run_id/"
  # The published record cannot hold the publish stage's own seconds or the final total:
  # both are printed on stdout after the copy (STAGE_SECONDS_PUBLISH, STAGE_SECONDS_TOTAL).
  record_total_seconds STAGE_SECONDS_TOTAL_BEFORE_PUBLISH
  # Phase 15: the plain-text Spark event logs go to the reports prefix (spark-events/).
  # The event log is the one file whose size is not ours to bound (review 1, a1), so it
  # is scanned apart, before the run record is written: each file under a 120 s limit and
  # at most SC_EVENT_LOG_SCAN_MAX_BYTES (256 MiB). A file that is larger or whose scan did
  # not finish is NOT published (removed from the reports copy, named in a NOTE and in
  # SPARK_EVENT_LOG_PUBLISHED); the run does not fail on it. A scan that finds key
  # material still fails the run, as for every other file.
  local lf evbytes evdropped=0 evkept=0
  if [[ "$spark_events" == "true" && -d "$SC_EVENT_LOG_DIR" ]]; then
    mkdir -p "$rep/spark-events"
    cp -R "$SC_EVENT_LOG_DIR/." "$rep/spark-events/"
    while IFS= read -r lf; do
      evbytes="$(wc -c < "$lf" | tr -d ' ')"
      rc=0
      if [[ "$evbytes" -le "${SC_EVENT_LOG_SCAN_MAX_BYTES:-268435456}" ]]; then
        scan_no_secrets_bounded "$lf" 120 || rc=$?
      else
        rc=2
      fi
      if [[ "$rc" -ne 0 ]]; then
        rm -f "$lf"
        evdropped=$((evdropped + 1))
        note "Spark event log ${lf##*/} ($evbytes bytes) not published: too large or the key-material scan did not finish in 120 s"
      else
        evkept=$((evkept + 1))
      fi
    done < <(find "$rep/spark-events" -type f)
    kv SPARK_EVENT_LOG_PUBLISHED "$evkept file(s) published, $evdropped not published (over 256 MiB or scan unfinished), $(du -sb "$rep/spark-events" | cut -f1) bytes, uncompressed, reports/runs/$run_id/spark-events/"
  fi
  write_json "$rep/run-record.json"
  printf '%s' "$SC_KV" > "$rep/run-record.env"
  for lf in "$rep"/*; do
    scan_no_secrets "$lf"
  done
  # Files in the other reports subdirectories (mapreduce-counters/) get the same strict
  # key-material scan; the loop above reaches only the top level, and the event logs were
  # scanned above.
  while IFS= read -r lf; do
    scan_no_secrets "$lf"
  done < <(find "$rep" -mindepth 2 -type f -not -path "$rep/spark-events/*")
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
