# M-3 report, experiment-engineer (received 2026-11-17; summary as received; author: experiment-engineer subagent)

Status: COMPLETE, not executed. `experiment-c-embedding-backlog.manifest` line 38 and its JSON copy line 16: `SPEND_CEILING_USD` 0.50 -> 0.60 (P15-24); header: 0.45 burn x 60/60 = 0.45 plus Titan tokens (0.02-0.06) and S3 Vectors PUTs (0.05-0.067), HIGH about 0.577, within 0.60. Nothing else changed. C needs a fresh `--print-digest` and record (sha256 changed).

Old b-burst values still quoted (read-only scan): `experiments/b-burst/README.md` line 6 (`PARTITION_COUNT=16`, `SPLIT_MAX_BYTES=131072`, `MAP_MEMORY_MB=3072`, `REDUCE_MEMORY_MB=3072`) and lines 32-33 (derived "16 reducers"); `run.sh`, `collect.sh`, `derive-capacity.py`, `rehearse.sh`, `rehearse-collect.sh` none; fixtures not opened. Orchestrator routed the README update to the same agent (M-3b).

M-3b (received 2026-11-17): `experiments/b-burst/README.md` line 6 and lines 32-33 now quote the P15-23 values (8 / 262144 / 2048 / 3072; 8 reducers) citing P15-23 and the diagnosis; new Limitations line on the failed session 1 run (old values as history, scale-out recorded, rerun targets scale-in). All 15 fixture files read: none quotes the profile values. Not executed.

Human check: `bash experiments/check-manifest-json.sh` (expected OK manifests=6).
