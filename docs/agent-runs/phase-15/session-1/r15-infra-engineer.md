# R15 report, infra-engineer (received 2026-11-17; summary as received; author: infra-engineer subagent)

Status: COMPLETE for the edit; not executed. `scripts/emr/spark-yarn.conf`: `b-burst.PARTITION_COUNT` 16 -> 8, `SPLIT_MAX_BYTES` 131072 -> 262144, `MAP_MEMORY_MB` 3072 -> 2048, `REDUCE_MEMORY_MB` 3072 unchanged; the PROPOSED comment replaced by a P15-23 note (session 1 run reason, about 1,590 -> about 800 containers; Hadoop stage limit unchanged; NodeManager memory UNVERIFIED). a-baseline lines untouched.

Harness pins to route to test-engineer (read lines 1-5,500 and 6,900-12,020 of `tests/scripts/run.sh`):
1. Line 10903, `15.emr.static.the-conf-defaults-equal-the-old-behaviour-and-each-profile-line-overrides-a-known-plain-key`: needles `b-burst.PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072` -> 8, 262144, 2048 (comment 10901-10902 to cite P15-23).
2. Lines 10430-10431, `15.emr.hadoop-options.profile-b-burst-adds-the-four-options-in-order-and-the-record-lists-them`: `-D` options and `HADOOP_EXTRA_OPTIONS=` line -> partitionCount=8, split.maxsize=262144, map.memory.mb=2048, reduce.memory.mb=3072.
Not read: lines 5,500-6,900 and after 12,020.

`experiments/b-burst/README.md` already updated (M-3b); line 20 keeps the old values as session 1 history. `scripts/README.md` not checked for the values.

Next: re-upload (new upload record), new B digests and records in tomorrow's runbook.
