#!/usr/bin/env python3
"""Experiment A: derive the course field table from RAW run files only.

Python 3 standard library only, no network, no AWS call. Run it with `python3 -I`.

Course fields for Experiment A: input records, output records, task counts, duration,
shuffle, failures and estimated cost. For each field the table states a value, the raw file
it came from, and one of three statuses:
  RECORDED      the value is a line or key in a raw file
  DERIVED       the value is counted or computed from raw files by this script
  NOT RECORDED  no raw file holds it; the cell says where it could still be fetched

Usage:
  python3 -I experiments/a-baseline/derive-table.py --reports-dir DIR --pipeline-record FILE \
      --out-dir NEW_DIR [--counters-dir DIR]

  --reports-dir      the run's on-node reports (run-record.json, er.log, spark.log, export.log,
                     progress.env, mapreduce-job-ids.txt). For S run 6 this is
                     scholarchain-data/runs/s-6/reports/. Two more files, copied there by
                     the on-node script (infra-engineer, Phase 15 R4 item 5), carry the
                     output records:
                       spark-run-manifest.json  copy of <spark out>/run=<id>/run-manifest.json
                       export-manifest.json     copy of <export out>/export/export-manifest.json
                     A missing file leaves its field NOT RECORDED. The export manifest has
                     tables.<name>.rows (read from the JsonlExport source). The Spark run
                     manifest holds outputs[].table and outputs[].rows (the writer is
                     CollaborationPipeline.scala); any other shape, including a `tables` key,
                     is reported as NOT RECORDED, never guessed.
  --pipeline-record  the pipeline run record (pipeline-<stamp>-<size>.record.env).
  --counters-dir     optional. Files holding `mapred job -status <job id>` output, one per
                     MapReduce job, named <job id>.txt (the on-node script writes them to
                     reports/mapreduce-counters/ when MR_COUNTERS is true). When absent,
                     MapReduce task counts, shuffle and input records are NOT RECORDED. (The
                     on-node script of S run 6 did not write them.)
                     Input records come from the "Map input records" counter, NOT from
                     INPUT_LINES_* (those are `wc -l` line counts, shown as line counts
                     only: an XML line is not a record). Only the jobs that read the raw
                     inputs (stage dblp-extraction and source-normalization/*, from the
                     positional stage column of mapreduce-job-ids.txt) are summed; later
                     jobs re-read intermediate data, so a sum over every job is not an input
                     record count.
  --spark-eventlog-dir
                     optional. A directory of plain (uncompressed) Spark event logs. When
                     absent, Spark shuffle bytes are NOT RECORDED. (S run 6 wrote no event log.)
  --out-dir          must not exist; fields.json and fields.md are written into it.

Raw inputs are only read. The script does not print or copy an account id; the pipeline
record holds one, and only the keys named below are taken from it.
"""

import argparse
import hashlib
import json
import os
import re
import sys

SPARK_PATTERNS = {
    "tasks_started": re.compile(r"TaskSetManager: Starting task "),
    "tasks_finished": re.compile(r"TaskSetManager: Finished task "),
    "tasks_lost_lines": re.compile(r"TaskSetManager: Lost task "),
    "task_errors": re.compile(r"ERROR TaskSetManager"),
    "executors_lost": re.compile(r"(ExecutorLostFailure|Lost executor)"),
    "stages_finished": re.compile(r"DAGScheduler: (ShuffleMapStage|ResultStage) \d+ \(.*\) finished in "),
    "stages_failed": re.compile(r"DAGScheduler: (ShuffleMapStage|ResultStage) \d+ \(.*\) failed in "),
    "jobs_finished": re.compile(r"DAGScheduler: Job \d+ finished: "),
    "jobs_failed": re.compile(r"DAGScheduler: Job \d+ failed: "),
    "shuffles_registered": re.compile(r"DAGScheduler: Registering RDD \d+ .* as input to shuffle \d+"),
    "executors_registered": re.compile(r"Registered executor "),
}

ER_LINES = {
    "candidates": re.compile(r"ER-BATCH candidates read: (\d+); distinct pairs: (\d+); duplicates dropped: (\d+)"),
    "indexed": re.compile(r"ER-BATCH records indexed: (\d+) \(works (\d+); researcher mentions (\d+); duplicate ids (\d+)\)"),
    "decisions": re.compile(r"ER-BATCH decisions: ACCEPTED (\d+) REJECTED (\d+) UNRESOLVED (\d+) CONFLICT (\d+)"),
    "elapsed": re.compile(r"ER-BATCH elapsed ms: (\d+)"),
    "result": re.compile(r"ER-BATCH RESULT: (\S+)"),
}

# Names printed by `mapred job -status` (Hadoop counters). The match is by exact name so a
# renamed counter shows up as NOT RECORDED instead of a wrong number.
COUNTER_NAMES = [
    "Launched map tasks",
    "Launched reduce tasks",
    "Map input records",
    "Map output records",
    "Reduce input records",
    "Reduce output records",
    "Reduce shuffle bytes",
    "Shuffled Maps",
    "Failed Shuffles",
    "Failed map tasks",
    "Failed reduce tasks",
    "Killed map tasks",
    "Killed reduce tasks",
]


def read_json(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def read_kv(path):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if "=" in line and not line.startswith("#"):
                key, _, value = line.partition("=")
                values.setdefault(key, value)
    return values


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def count_log(path, patterns):
    counts = dict((name, 0) for name in patterns)
    lines = 0
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            lines += 1
            for name, rx in patterns.items():
                if rx.search(line):
                    counts[name] += 1
    return counts, lines


def parse_counters(counters_dir):
    totals = dict((name, 0) for name in COUNTER_NAMES)
    seen = dict((name, 0) for name in COUNTER_NAMES)
    per_job = {}
    files = []
    for name in sorted(os.listdir(counters_dir)):
        path = os.path.join(counters_dir, name)
        if not os.path.isfile(path):
            continue
        files.append(name)
        job = name[:-4] if name.endswith(".txt") else name
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                m = re.match(r"^\s*([A-Za-z][A-Za-z ]*?)\s*=\s*(\d+)\s*$", line)
                if m and m.group(1) in totals:
                    totals[m.group(1)] += int(m.group(2))
                    seen[m.group(1)] += 1
                    per_job.setdefault(job, {})[m.group(1)] = int(m.group(2))
    return totals, seen, files, per_job


RAW_INPUT_STAGES = ("dblp-extraction", "source-normalization/")


def job_stages(ids_path):
    """job id -> positional stage name, from the `<n> <job id> <stage>` lines."""
    stages = {}
    if not os.path.isfile(ids_path):
        return stages
    with open(ids_path, encoding="utf-8") as handle:
        for line in handle:
            parts = line.split()
            if len(parts) == 3 and not line.startswith("#") and parts[1].startswith("job_"):
                stages[parts[1]] = parts[2]
    return stages


def row_count(value):
    """A whole, non-negative row count from a JSON number (ujson may write 120 or 120.0); else None."""
    if isinstance(value, bool):
        return None
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    return value if isinstance(value, int) and value >= 0 else None


def manifest_rows(manifest):
    """Export manifest: table name -> rows from the `tables.<name>.rows` object; None when the shape is not recognized."""
    tables = manifest.get("tables") if isinstance(manifest, dict) else None
    if not isinstance(tables, dict):
        return None
    rows = {}
    for name, entry in tables.items():
        count = row_count(entry.get("rows")) if isinstance(entry, dict) else None
        if count is None:
            return None
        rows[name] = count
    return rows or None


def spark_output_rows(manifest):
    """Spark run manifest: table name -> rows from `outputs[].table` / `outputs[].rows`
    (CollaborationPipeline.scala, the `outputs` array of the run manifest). None when the
    shape is not recognized; any other key, including a `tables` key, is never read."""
    outputs = manifest.get("outputs") if isinstance(manifest, dict) else None
    if not isinstance(outputs, list):
        return None
    rows = {}
    for entry in outputs:
        name = entry.get("table") if isinstance(entry, dict) else None
        count = row_count(entry.get("rows")) if isinstance(entry, dict) else None
        if not isinstance(name, str) or not name or count is None:
            return None
        rows[name] = count
    return rows or None


COMPRESSED_SUFFIXES = (".zstd", ".lz4", ".snappy", ".lzf", ".gz", ".zst")


def parse_eventlogs(root):
    """Sum task end events of Spark event logs (plain JSON lines, single file or rolling v2)."""
    totals = {"tasks": 0, "tasks_failed": 0, "shuffle_write_bytes": 0, "shuffle_read_bytes": 0}
    files = []
    skipped = []
    for dirpath, dirs, names in os.walk(root):
        dirs.sort()
        for name in sorted(names):
            if not name.startswith(("events_", "application_", "local-")):
                continue
            if name.endswith(COMPRESSED_SUFFIXES):
                skipped.append(name)
                continue
            files.append(name)
            with open(os.path.join(dirpath, name), encoding="utf-8", errors="replace") as handle:
                for line in handle:
                    if "SparkListenerTaskEnd" not in line:
                        continue
                    try:
                        ev = json.loads(line)
                    except ValueError:
                        continue
                    if ev.get("Event") != "SparkListenerTaskEnd":
                        continue
                    totals["tasks"] += 1
                    if (ev.get("Task End Reason") or {}).get("Reason") != "Success":
                        totals["tasks_failed"] += 1
                    metrics = ev.get("Task Metrics") or {}
                    read = metrics.get("Shuffle Read Metrics") or {}
                    write = metrics.get("Shuffle Write Metrics") or {}
                    totals["shuffle_read_bytes"] += int(read.get("Remote Bytes Read", 0)) + int(read.get("Local Bytes Read", 0))
                    totals["shuffle_write_bytes"] += int(write.get("Shuffle Bytes Written", 0))
    return totals, files, skipped


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reports-dir", required=True)
    parser.add_argument("--pipeline-record", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--counters-dir", default="")
    parser.add_argument("--spark-eventlog-dir", default="")
    args = parser.parse_args(argv)

    if os.path.exists(args.out_dir):
        raise SystemExit("output directory exists: %s" % args.out_dir)
    reports = args.reports_dir
    run_record = read_json(os.path.join(reports, "run-record.json"))
    record = read_kv(args.pipeline_record)
    fields = []
    inputs = {}

    def add(field, value, source, status, note=""):
        fields.append({"field": field, "value": value, "source": source, "status": status, "note": note})

    def use(path):
        inputs[os.path.relpath(path, os.path.dirname(os.path.abspath(reports)))] = sha256_of(path)

    rr_path = os.path.join(reports, "run-record.json")
    use(rr_path)
    use(args.pipeline_record)
    # ---- input records / bytes
    in_bytes = dict((k, run_record.get(k)) for k in sorted(run_record) if k.startswith("INPUT_BYTES_"))
    add("input bytes (4 inputs)", in_bytes, "reports/run-record.json INPUT_BYTES_*", "RECORDED")
    in_lines = dict((k, run_record.get(k)) for k in sorted(run_record) if k.startswith("INPUT_LINES_"))
    if in_lines:
        add(
            "input lines (4 inputs, `wc -l`: line counts only, NOT record counts)",
            in_lines,
            "reports/run-record.json INPUT_LINES_*",
            "RECORDED",
            "an XML line is not a record; the record count is the MapReduce 'Map input records' counter",
        )
    input_records_added = False

    # ---- ER stage counters (the only record-level counters in the run)
    er_path = os.path.join(reports, "er.log")
    if os.path.isfile(er_path):
        use(er_path)
        er = {}
        with open(er_path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                for name, rx in ER_LINES.items():
                    m = rx.search(line)
                    if m:
                        er[name] = m.groups()
        if "candidates" in er:
            add("ER candidate pairs read / distinct / duplicates dropped", list(er["candidates"]), "reports/er.log", "RECORDED")
        if "indexed" in er:
            add("ER records indexed / works / researcher mentions / duplicate ids", list(er["indexed"]), "reports/er.log", "RECORDED")
        if "decisions" in er:
            add("ER decisions ACCEPTED/REJECTED/UNRESOLVED/CONFLICT", list(er["decisions"]), "reports/er.log", "RECORDED")
    else:
        add("ER stage counters", None, "reports/er.log", "NOT RECORDED", "file missing")

    # ---- output records (copies of the Spark run manifest and the export manifest)
    export_path = os.path.join(reports, "export-manifest.json")
    export_rows = None
    if os.path.isfile(export_path):
        use(export_path)
        export_rows = manifest_rows(read_json(export_path))
    if export_rows:
        add("output records (export tables, rows per table)", export_rows, "reports/export-manifest.json tables.<name>.rows", "RECORDED")
        add("output records (export tables, total rows)", sum(export_rows.values()), "reports/export-manifest.json", "DERIVED",
            "sum of the rows of %d tables" % len(export_rows))
    else:
        add(
            "output records (export tables, graph rows)",
            None,
            "-",
            "NOT RECORDED",
            "reports/export-manifest.json is missing or has no tables.<name>.rows; the export manifest in S3 (semantic-context/runs/<run id>/export/) holds the counts",
        )
    spark_manifest_path = os.path.join(reports, "spark-run-manifest.json")
    spark_rows = None
    if os.path.isfile(spark_manifest_path):
        use(spark_manifest_path)
        spark_rows = spark_output_rows(read_json(spark_manifest_path))
    if spark_rows:
        add("graph rows (Spark run-manifest tables, rows per table)", spark_rows, "reports/spark-run-manifest.json outputs[].table / outputs[].rows", "RECORDED",
            "shape verified against the run manifest writer in CollaborationPipeline.scala")
        add("graph rows (Spark run-manifest tables, total rows)", sum(spark_rows.values()), "reports/spark-run-manifest.json", "DERIVED",
            "sum of the rows of %d tables" % len(spark_rows))
    else:
        add("graph rows (Spark run-manifest tables)", None, "-", "NOT RECORDED",
            "reports/spark-run-manifest.json is missing or has no outputs[].table / outputs[].rows")

    # ---- task counts
    ids_path = os.path.join(reports, "mapreduce-job-ids.txt")
    if os.path.isfile(ids_path):
        use(ids_path)
        with open(ids_path, encoding="utf-8") as handle:
            job_lines = [ln for ln in handle if ln.strip() and not ln.startswith("#")]
        add("MapReduce jobs (applications)", len(job_lines), "reports/mapreduce-job-ids.txt", "DERIVED")
    add("MapReduce jobs expected / yarn verdict", [run_record.get("MAPREDUCE_JOB_COUNT"), run_record.get("MAPREDUCE")], "reports/run-record.json", "RECORDED")

    spark_counts = {}
    for label, name in (("spark", "spark.log"), ("export", "export.log")):
        path = os.path.join(reports, name)
        if os.path.isfile(path):
            use(path)
            counts, lines = count_log(path, SPARK_PATTERNS)
            spark_counts[label] = counts
            add("Spark tasks finished (%s application)" % label, counts["tasks_finished"], "reports/%s (%d lines)" % (name, lines), "DERIVED",
                "count of 'TaskSetManager: Finished task' lines in the driver log")
            add("Spark tasks started (%s application)" % label, counts["tasks_started"], "reports/%s" % name, "DERIVED")
            add("Spark stages finished (%s application)" % label, counts["stages_finished"], "reports/%s" % name, "DERIVED")
            add("Spark jobs finished (%s application)" % label, counts["jobs_finished"], "reports/%s" % name, "DERIVED")
            add("Spark shuffle dependencies registered (%s application)" % label, counts["shuffles_registered"], "reports/%s" % name, "DERIVED",
                "a count of shuffles, not bytes")
            add("Spark executors registered (%s application)" % label, counts["executors_registered"], "reports/%s" % name, "DERIVED")
            failed = counts["tasks_lost_lines"] + counts["task_errors"] + counts["executors_lost"] + counts["stages_failed"] + counts["jobs_failed"]
            add("Spark failure lines (%s application)" % label, failed, "reports/%s" % name, "DERIVED",
                "lost tasks + task errors + lost executors + failed stages + failed jobs: %d, %d, %d, %d, %d" % (
                    counts["tasks_lost_lines"], counts["task_errors"], counts["executors_lost"], counts["stages_failed"], counts["jobs_failed"]))
        else:
            add("Spark task counts (%s application)" % label, None, "reports/%s" % name, "NOT RECORDED", "file missing")

    if args.counters_dir:
        totals, seen, files, per_job = parse_counters(args.counters_dir)
        for name in COUNTER_NAMES:
            if seen[name]:
                note = ""
                if name == "Map input records":
                    note = "sum over every job, later jobs re-read intermediate data: NOT the input record count (see the input records field)"
                add("MapReduce counter: %s (sum over %d job file(s))" % (name, seen[name]), totals[name], "counters-dir (%d files)" % len(files), "DERIVED", note)
            else:
                add("MapReduce counter: %s" % name, None, "counters-dir", "NOT RECORDED", "name not found in the counter files")
        if seen["Map input records"]:
            stages = job_stages(ids_path)
            raw_jobs = sorted(j for j, s in stages.items() if s.startswith(RAW_INPUT_STAGES))
            have = [j for j in raw_jobs if "Map input records" in per_job.get(j, {})]
            if raw_jobs and len(have) == len(raw_jobs):
                add("input records (MapReduce 'Map input records' of the raw-input jobs)", sum(per_job[j]["Map input records"] for j in raw_jobs),
                    "counters-dir + reports/mapreduce-job-ids.txt stage column (positional, inferred)", "DERIVED",
                    "jobs summed: %s" % ", ".join("%s=%s" % (j, stages[j]) for j in raw_jobs))
                input_records_added = True
            else:
                missing = [j for j in raw_jobs if j not in have]
                add("input records (MapReduce 'Map input records' of the raw-input jobs)", None, "counters-dir + reports/mapreduce-job-ids.txt", "NOT RECORDED",
                    "no raw-input job found in mapreduce-job-ids.txt" if not raw_jobs else "no 'Map input records' counter for: %s" % ", ".join(missing))
                input_records_added = True
            add("Map input records per job", dict((j, per_job[j]["Map input records"]) for j in sorted(per_job) if "Map input records" in per_job[j]),
                "counters-dir", "DERIVED", "jobs 1 to 4 (dblp-extraction, source-normalization/*) read the raw inputs; later jobs read intermediate data")
    else:
        add("MapReduce map and reduce task counts", None, "-", "NOT RECORDED",
            "job history and counters lived on the terminated cluster; S3 EMR logs may hold container logs (fetch command in experiments/a-baseline/README.md)")
        add("MapReduce shuffle bytes (Reduce shuffle bytes)", None, "-", "NOT RECORDED", "same as above")
    if not input_records_added:
        add(
            "input records (per input file)",
            None,
            "-",
            "NOT RECORDED",
            "no record count without the MapReduce 'Map input records' counter: run with MR_COUNTERS=true and pass --counters-dir. INPUT_LINES_* (if present) are line counts only",
        )
    if args.spark_eventlog_dir:
        ev_totals, ev_files, ev_skipped = parse_eventlogs(args.spark_eventlog_dir)
        source = "spark-eventlog-dir (%d files read, %d compressed files skipped)" % (len(ev_files), len(ev_skipped))
        add("Spark tasks (event log task-end events)", ev_totals["tasks"], source, "DERIVED")
        add("Spark tasks not successful (event log)", ev_totals["tasks_failed"], source, "DERIVED")
        add("Spark shuffle write bytes (event log)", ev_totals["shuffle_write_bytes"], source, "DERIVED")
        add("Spark shuffle read bytes, remote + local (event log)", ev_totals["shuffle_read_bytes"], source, "DERIVED")
    else:
        add("Spark shuffle read and write bytes", None, "-", "NOT RECORDED",
            "the driver log prints no shuffle bytes and no Spark event log was written on the node")

    # ---- duration
    stage_keys = sorted(k for k in run_record if k.startswith("STAGE_SECONDS_"))
    add("stage seconds", dict((k, run_record[k]) for k in stage_keys), "reports/run-record.json", "RECORDED")
    add("duration, pipeline execution", {"start_utc": record.get("START_UTC"), "end_utc": record.get("END_UTC"), "elapsed_minutes": record.get("ELAPSED_MINUTES")},
        "pipeline record", "RECORDED")
    progress = os.path.join(reports, "progress.env")
    if os.path.isfile(progress):
        use(progress)
        p = read_kv(progress)
        add("last on-node checkpoint", {"checkpoint": p.get("CHECKPOINT"), "updated_utc": p.get("UPDATED_UTC"), "elapsed_seconds": p.get("ELAPSED_SECONDS")},
            "reports/progress.env", "RECORDED")

    # ---- failures and cost
    add("pipeline final status", record.get("FINAL_STATUS"), "pipeline record", "RECORDED",
        "the final state covers the loader step as well, so a loader failure shows here even when every EMR stage succeeded")
    add("EMR application states", {"spark": run_record.get("SPARK_APPLICATION"), "export": run_record.get("EXPORT_APPLICATION"), "preflight": run_record.get("PREFLIGHT_RESULT")},
        "reports/run-record.json", "RECORDED")
    add("estimated cost (burn x elapsed, whole execution, NOT a bill)",
        {"estimated_spend_usd": record.get("ESTIMATED_SPEND_USD"), "hourly_burn_usd": record.get("HOURLY_BURN_USD")},
        "pipeline record", "RECORDED",
        "includes the loader step and the whole execution; an EMR-only figure is not recorded")

    os.makedirs(args.out_dir)
    document = {"fields": fields, "inputs_sha256": inputs}
    with open(os.path.join(args.out_dir, "fields.json"), "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2, sort_keys=True)
        handle.write("\n")
    lines = ["| Field | Value | Source | Status | Note |", "|---|---|---|---|---|"]
    for f in fields:
        value = "-" if f["value"] is None else json.dumps(f["value"], sort_keys=True)
        lines.append("| %s | %s | %s | %s | %s |" % (f["field"], value.replace("|", "/"), f["source"], f["status"], f["note"].replace("|", "/")))
    lines.append("")
    lines.append("Input files (sha256):")
    lines.append("")
    for name, digest in sorted(inputs.items()):
        lines.append("- `%s` %s" % (name, digest))
    lines.append("")
    with open(os.path.join(args.out_dir, "fields.md"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    print("FIELDS_DIR=%s" % args.out_dir)
    for status in ("RECORDED", "DERIVED", "NOT RECORDED"):
        print("%s=%d" % (status.replace(" ", "_"), sum(1 for f in fields if f["status"] == status)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
