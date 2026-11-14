# Work order 12: storage-engineer, loader entry point (read-only analysis)

Owner: storage-engineer (modules/storage/**). Read-only: no edits, no sbt, no AWS, no commit.

Question: the loader image is the same bytes as the services image (`dockerLocal` stages one context; scripts/README.md "What the build gives deploy.sh", gap 2). Does the services runtime classpath (storage is a dependency of services) contain a loader main class that the pipeline's `loader_command` can run? Evidence: docs/agent-runs/phase-14/checks-5.md; infra/terraform/modules/workflow and modules/loader (read to find the loader task definition's `command`/`entryPoint` and what environment, buckets, Neptune endpoint and vector bucket it expects).

Report:
1. The exact main class or entry point the loader task needs, whether it exists in modules/storage, and the arguments and environment it reads. If it does not exist or is not on the services classpath, say what is missing and who owns the fix (build-engineer for packaging, storage-engineer for code).
2. How the loader gets its inputs (S3 keys, graph format for the Neptune bulk loader, embedding input), from the code, with the real Neptune bulk-loader S3 path assumption L1 and the vector dimension 256 guard.
3. The S3 Vectors bucket and index names and the ingestion count the loader would report, so the S run can report "vector ingestion counts with working retrieval" (instructor requirement D2). Say what retrieval check proves it works.
Mark each statement as read from code or UNVERIFIED. Do not guess a class name.

Report contract: STATUS, OWNER, WORK ORDER, FINDINGS, RISKS, BLOCKED-ON OWNER, NEXT GATE.
