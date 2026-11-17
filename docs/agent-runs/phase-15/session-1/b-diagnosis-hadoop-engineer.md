# B-run diagnosis, hadoop-engineer (received 2026-11-17; summary of the report as received; read-only analysis; author: hadoop-engineer subagent)

## Job chain (PipelineTool.scala:183-246, sequential; reducers = run.partitionCount, JobSupport.scala:45; default 4, PipelineOptions.scala:79)
At split.maxsize 131,072 and partitionCount 16 (intermediate sizes estimated from the local 4-reducer run `scholarchain-data/phase4-out/subset-v6/`, same input UNVERIFIED):

| # | Job | Maps | Reduces | Containers incl. AM |
|---|---|---|---|---|
| 1 | dblp-extraction | 17 | 16 | 34 |
| 2-4 | normalize crossref / openalex / orcid | 2 / 8 / 1 | 16 each | 19 / 25 / 18 |
| 5 | identifier-join | 144 | 16 | 161 |
| 6 | blocking | 144 | 16 | 161 |
| 7 | candidate-features | 720 (16 files of about 5.9 MB) | 16 | 737 |
| 8 | team-expansion | 144 | 16 | 161 |
| 9 | graph-export | about 256 | 16 | about 273 |

About 1,590 container launches in total.

## At the kill
Most likely job 7 (candidate-features), moderate confidence: the ContainerPending curve (15; 129; 129; 716 peak against 737 expected; 522; 300; 102) matches the chain; drain about 0.72 containers/s (about 15 s per container on 11 slots); jobs 8-9 still to run; whole chain would need about 2,150-2,250 s against the 1,500 s limit. No direct job observation (hadoop.log holds only the banner).

## Limits
- Hadoop stage: literal `timeout -k 30 1500 hadoop jar ...` at `scripts/emr/run-s-pipeline.sh:308` (no profile key); 1,508 s = 1,500 + HDFS setup.
- 88 min = 5,280 s from PIPELINE_START. Before the step: 371 s; after: 900 s Wait + about 300-400 s terminate; about 3,600 s for the pipeline step.
- After Hadoop (from S run 6 on YARN): ER 104 s, Spark 1,735 s, export 65 s, plus about 165 s overhead: about 2,070 s. b-burst Spark `local[4]` with 8 shuffle partitions is unmeasured (largest unknown). Raising the 1,500 s Hadoop limit is not safe within 88 min.

## Containers (m5.xlarge NodeManager 12,288 MB, EMR default, UNVERIFIED)
3,072 MB: 3 slots on 1 core node, 11 on 3 (matches ContainerAllocated 3 then 11). 1,536 MB: 7 / 23. Keep reduce at 3,072 (heap risk).

## Proposed fix (profile values only; no production change)
`b-burst.SPLIT_MAX_BYTES` 131072 -> 262144; `b-burst.PARTITION_COUNT` 16 -> 8; `b-burst.MAP_MEMORY_MB` 3072 -> 2048 (5 / 17 slots); `REDUCE_MEMORY_MB` 3072 unchanged; Hadoop limit 1,500 unchanged. About 800 containers (jobs 1-4: 50; 5, 6, 8: 81 each; 7: 369; 9: about 137). Estimated Hadoop stage about 1,000 s (range 800-1,400). Scale-out pressure: job 1 peak pending about 11 (observed 15); jobs 5-6 request 72 maps on 5 slots; job 7 requests 360. Stronger job-1 trigger option: SPLIT_MAX_BYTES 196608 (about 930 containers, about +100 s). Risks: 15 s/container rate from one run; intermediate sizes estimated; Spark `local[4]` time unmeasured (over about 1,700 s breaks the 88-min cap); whether a lower pending ratio still triggers scale-out (policy internals unpublished).

Owners: infra-engineer edits the `b-burst.*` lines in `scripts/emr/spark-yarn.conf` (and the PROPOSED comment); re-upload and new B digests and records (deploy-engineer runbook, experiment-engineer manifests if bound). No `modules/hadoop/**` change.
