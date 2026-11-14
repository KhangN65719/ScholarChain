# Work order 13: experiment-engineer, tier S real-data run input (draft, read-only plus draft files)

Owner: experiment-engineer (experiments/**, load-tests/**). No AWS, no execution, no commit. Edit only inside your paths.

Context: D8 fixes Phase 14 to smoke on fixtures plus the S real-data run (5,000 works, cap 60 min, burn 0.60 USD/h, spend ceiling 0.75). D2: real data, report dataset size, execution time, actual AWS cost, vector ingestion counts with working retrieval. Real data lives outside the repository under /Users/winnguyen/scholarchain-data (read the directory listing and any manifest with Read; datasets/slice/ is untracked and hidden from git). Evidence: docs/agent-runs/phase-14/checks-5.md, decisions-d13-d14.md, scripts/README.md "run-bounded-pipeline.sh run record" and "Pipeline sizes".

Task:
1. Report what real dataset exists locally (path, format, size in bytes, record counts if a manifest says so), whether it can be reduced to a 5,000-work S slice with the existing dataset scripts (datasets/scripts, owned by dataset-engineer; name the script and its bounds, do not run it), and the DATASET_SNAPSHOT id and checksums that must be recorded.
2. Draft the pipeline input file for `run-bounded-pipeline.sh` (`{hadoop_args, spark_args, loader_command}`, see modules/workflow input contract) with S3 URIs as placeholders to be fixed by infra-engineer's upload proposal and storage-engineer's loader report. Do not invent main classes: leave `TODO-from-WO12` where unknown. Put the draft under experiments/ (not tracked changes to production code).
3. List the S run's required measurements and where each comes from (dataset size, wall-clock, EMR/Neptune/Bedrock cost, vector counts, retrieval check) and which are fixtures-only versus real-data, per D2.

Report contract: STATUS, OWNER, WORK ORDER, FILES CREATED, FINDINGS, RISKS, BLOCKED-ON OWNER, NEXT GATE. You cannot run bash (guard D11); state that nothing was executed.
