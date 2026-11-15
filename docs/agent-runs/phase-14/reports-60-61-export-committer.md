# Phase 14 work orders 60 and 61 and their reports (2026-11-15 UTC)

Archived by the orchestrator. Authors identified; the reports are condensed to their findings, decisions and validation, with the files, case names and figures as received. No account ID in this file. Evidence: `decisions-d34.md`.

## Work order 60 (infra-engineer): export output committer
Task as dispatched: confirm or refute the inferred cause of the S run 5 export failure (`ClassNotFoundException: org.apache.hadoop.mapred.DirectFileOutputCommitter`), make the minimal change in `scripts/**` only, list the harness impact, say whether `EXPORT_ON_YARN=client:no` / `state=none` is a recording bug, and suggest cheap offline checks.

### Report (infra-engineer) STATUS: COMPLETE (written, not run)
Files modified:
- `scripts/emr/lib-emr.sh`: `write_spark_conf` writes `spark.hadoop.mapred.output.committer.class org.apache.hadoop.mapred.FileOutputCommitter`, with a comment, after the umask line.
- `scripts/emr/run-s-pipeline.sh`: one `kv NODE_CONF_MAPRED_OUTPUT_COMMITTER_CLASS "$(conf_xml_value mapred.output.committer.class)"` in the `spark-stage` stage (evidence only; prints the node's value or `unset`).
- `scripts/README.md`: one paragraph before "### S3 key layout", headed "Export output committer (work order 60, S run 5 ...".
- No change to `run_spark_submit`, `spark_verdict`, `spark-yarn.conf`, Terraform or Rule A numbers.

Confirmed by reading: stage 8 is `export`; `JsonlExport.scala:549` calls `saveAsTextFile` (old mapred API; committer from `mapred.output.committer.class`); jobs 0 to 3 finished at `:516`, `:528`, `:541`, the failing call is the write at `:549`, the `SparkContext stopping ... from :756` line is the `finally`; the Spark stage uses only Dataset/Parquet writers (new API) and never reads the key; `run_spark_submit` (`lib-emr.sh:541-545`) sets `SPARK_CONF_DIR=$SC_HOME/spark-conf` and `HADOOP_CONF_DIR=/etc/hadoop/conf`, so EMR's `core-site.xml` and `mapred-site.xml` are read, EMR's spark-defaults are not, and `POPULATE_HADOOP_CLASSPATH=false` keeps EMR's jars off the classpath; the export already handles the old committer's `part-NNNNN`, `.part-NNNNN.crc` and `_SUCCESS` output (`finishParts`).

Inferred, not observed: that the node's `mapred-site.xml` names the EMR class (`record_hadoop_effective_conf` does not read the key); that `org.apache.hadoop.mapred.FileOutputCommitter` is in Spark 4.0.5's `hadoop-client-api` jar (Hadoop knowledge; the guard blocks unzipping); that `spark.hadoop.*` overrides the site files and reaches the executors.

Recording: `EXPORT_ON_YARN=client:no` and `state=none` are not a bug. `spark_verdict` (`lib-emr.sh:581`) queries the YARN state only when `rc == 0`; spark-submit exited 1, so the id `_0011` came from the log and no state was queried. Not changed (the `14.emr.yarn-final-state.*` cases cover that function).

Other EMR-specific classes the node config could inject: expected from memory, not read on the node: `fs.s3.impl`/`fs.s3n.impl` (EmrFileSystem), `fs.AbstractFileSystem.s3.impl`, S3-optimized committer and output-direct keys; only matter for `s3://` paths, which Spark and the export do not use (`file:` and `hdfs:` only). The one confirmed source is the LZO codecs in `NODE_CONF_IO_COMPRESSION_CODECS`, already overridden by `spark.hadoop.io.compression.codecs`. `mapreduce.output.fileoutputformat.compress` is `unset` in the record. Later stages are safe from this class: `publish` uses `aws s3 cp`, the loader runs on Fargate.

Decisions: one hard-coded line in `write_spark_conf` (one source of truth for both submits, not a `spark-yarn.conf` key); the main Spark stage and the capability check also get it (value is Hadoop's own default); ER untouched (plain java); the evidence `kv` is a separate line in `run-s-pipeline.sh`, not in `record_hadoop_effective_conf` (that function also feeds `capability-check.sh`). `lib-emr.sh` and `run-s-pipeline.sh` are uploaded inputs, so `upload-inputs.sh` must run again with a new approval record; `run-bounded-pipeline.sh` and `lib.sh` digests are unchanged.

Validation: unrun (`bash -n`, `shellcheck -x`, the harness, any real Spark run). Risks: the EMR committer inference may be wrong (the new `NODE_CONF_...` line will show it); the old committer on `file:` under YARN containers is the same machinery as the Parquet writers; publish and loader fetch have never run live (publish compares file counts with `aws s3 ls`, untested on a real run).

Offered offline checks (not decided): a local Spark 4.0.5 `local[2]` reproduction with a copy of EMR's `mapred-site.xml`; a static test that no `scripts/emr/*.sh` code line names EMR-only classes (done in work order 61); a 30 to 40 s on-node preflight before Hadoop (one old-API `saveAsTextFile` and one Parquet write on YARN); publishing `curated/`, `er/` and `graph/` before the export (larger change); adding the key to `record_hadoop_effective_conf`.

## Work order 61 (test-engineer): harness impact of work order 60
Task as dispatched: cases for the committer line (defaults and real conf), a static check for EMR-only class names, the ordering of the new `kv` line, README needles; audit `tests/scripts/run.sh` lines 1 to 3180; update `tests/scripts/README.md`; no weakening, no script edits.

### Report (test-engineer) STATUS: COMPLETE (written, nothing executed)
New total 1,069 + 15 = 1,084 (arithmetic), all in `tests/scripts/run.sh` before "Case 12":
- 1a, 4 dynamic cases `14.emr.spark-conf.export-committer-line-*` (defaults exactly once after the umask line; real `spark-yarn.conf` output; populate-true as the capability check writes it; with a java home set), each asserting the exact line appears once, no other line sets the key, `DirectFileOutputCommitter` absent, and the order; the last two use a new child program `sparkconf-variant-child.sh`.
- 1b, 5 static cases: `14.emr.<script>.no-emr-only-class-on-any-code-line` per `scripts/emr/*.sh` (4 today; `DirectFileOutputCommitter`, `EmrFileSystem`, `com.amazon`, `com.hadoop.compression` on code lines; the allowed set is empty: `com.hadoop.compression` appears only in comments at `lib-emr.sh:37` and `run-s-pipeline.sh:175`; the codec override lines name only `org.apache.hadoop.io.compress` classes) and `14.emr.lib-emr.the-codec-and-committer-lines-name-only-hadoop-core-classes`.
- 1c, 1 static case `14.emr.run-s-pipeline.node-committer-class-is-recorded-in-the-spark-stage-after-stage-spark-before-its-stage-ok` (order: `stage spark-stage`, `stage_spark`, the `kv`, `stage hadoop`; the first `stage_ok` in the file belongs to validate-arguments).
- Extra, 3 dynamic cases `14.emr.conf-xml-value.*` (the real `conf_xml_value` against fixture XML: names the class; lookalike keys return `unset`; no site files returns `unset`).
- 1d, 2 static cases on the README paragraph (stage 8 `export` after 35 s with nothing published, the `ClassNotFoundException` text, old mapred API and key, `write_spark_conf` fix, value is Hadoop's default; and it says `mapred-site.xml` "was not read", "inferred, not observed", UNVERIFIED, no claim of observation).

Audit of lines 1 to 3180: no case pins the text or line count of `write_spark_conf` or the spark-stage stage, nor the digest or size of the two edited scripts; upload cases use stand-in `scripts/emr` files; only `14.upload.real-emr-file.*.passes-the-upload-secret-scan` reads the real files (no key-material pattern in the new lines). No existing case edited; stub tripwire untouched. `tests/scripts/README.md`: work order 61 paragraph and count 1,084.

Validation: unrun by the agent. Risks: the per-script case count is a glob over `scripts/emr/*.sh` (1,084 assumes today's four); the ordering case depends on the literal `stage spark-stage` and `stage hadoop` lines; not coverable here: that Spark applies the key to `saveAsTextFile`, that EMR's `mapred-site.xml` sets it, that ER is unaffected.

## Human-run results after both work orders
- `phase14-static-checks-20.log`: `bash -n` on `lib-emr.sh`, `run-s-pipeline.sh`, `tests/scripts/run.sh` and `shellcheck -x` on all three, all exit 0.
- `phase14-harness-10.log`: `RESULT pass=1084 fail=0`, `SKIPPED_CASES 0`.
