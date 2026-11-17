# Wave 2 human-only commands (orchestrator; updated as owners report)

Run from `/Users/winnguyen/ScholarChain` with `!`. No AWS in this file yet. Logs in `/Users/winnguyen/scholarchain-data/logs/phase15-w2-*.log`.

## 1. Cleanup (WO-7)
```
rm modules/storage/src/main/scala/scholarchain/storage/AwsSqsWorkQueue.scala.pending
```
Expected: no output. The compiled adapter is `AwsSqsWorkQueue.scala` in the same directory.

## 2. Experiment rehearsals (WO-11; local only)
```
set -o pipefail; python3 -I load-tests/query-load/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rehearse-load-client.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -I experiments/c-embedding-backlog/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rehearse-c.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -I experiments/f-failure-recovery/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rehearse-f.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -I load-tests/lib/rehearse_shell.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rehearse-shell.log | tail -n 60; echo "exit: $?"
```
Expected: `REHEARSAL pass=42 fail=0`, `pass=35 fail=0`, `pass=8 fail=0`, `pass=24 fail=0`; exit 0 each. (Counts derived by reading, not yet run.)

### 2b. Load-client rehearsal rerun (after the WO-11 fix)
```
set -o pipefail; python3 -I load-tests/query-load/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rehearse-load-client-2.log | tail -n 60; echo "exit: $?"
```
Expected: `REHEARSAL pass=45 fail=0`, exit 0. (C 35, F 8 and shell 24 already passed.)

## 3. Service image and mains (WO-9, WO-7, WO-8; local Docker, no push)
```
set -o pipefail; sbt dockerLocal 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-dockerlocal.log | tail -n 60; echo "exit: $?"
set -o pipefail; docker run --rm scholarchain-local:phase-2 -version 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-image-version.log | tail -n 60; echo "exit: $?"
set -o pipefail; docker run --rm --memory=256m scholarchain-local:phase-2 scholarchain.services.runtime.AwsRuntime --help 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-image-awsruntime-help.log | tail -n 60; echo "exit: $?"
set -o pipefail; docker run --rm --memory=256m scholarchain-local:phase-2 scholarchain.storage.EmbeddingWorkerMain --help 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-image-worker-help.log | tail -n 60; echo "exit: $?"
```
Expected: `dockerLocal` ends `[success]` and tags `scholarchain-local:phase-2` (it fails with `Staged lib/ lacks main classes ... or SDK jars ...` if a main or the `sqs-`/`bedrockruntime-`/`url-connection-` jars are missing); `-version` prints OpenJDK 21; each `--help` prints the settings list and exits 0, no `ClassNotFoundException` or `NoClassDefFoundError`. Do not run sbt while an agent holds the sbt slot; the orchestrator says when the slot is free.

## 4. Load-client image (WO-9; needs the Python base-image digest)
```
docker buildx imagetools inspect python:3.12-slim-bookworm
```
Copy the top-level `Digest: sha256:...` value, then (replace `<DIGEST>`):
```
set -o pipefail; docker build -f packaging/load-client/Dockerfile --build-arg PYTHON_BASE=python:3.12-slim-bookworm@sha256:<DIGEST> -t scholarchain-load-client:local load-tests/query-load 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-load-client-build.log | tail -n 60; echo "exit: $?"
set -o pipefail; docker run --rm scholarchain-load-client:local --help 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-load-client-help.log | tail -n 60; echo "exit: $?"
set -o pipefail; docker run --rm --entrypoint id scholarchain-load-client:local 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-load-client-id.log | tail -n 60; echo "exit: $?"
```
Expected: build succeeds; `--help` prints `load_client.py` usage (URL, levels, caps, output directory), exit 0; `id` prints `uid=10001 gid=10001`. If the build cannot find `load_client.py` (the Dockerfile's COPY path assumes a staging directory), report it; build-engineer fixes the context.

## 5. Manifest JSON check after M-1 (C-F edits)
```
set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-m1-check-json.log | tail -n 60; echo "exit: $?"
```
Expected: `MANIFEST_JSON_CHECK OK manifests=6`, exit 0. The C-F `--print-digest` runs and approval records wait until WO-10 gives the session 2 burn (C-F cost pairs are PROVISIONAL).

## 6. Batch A after session 1 (2026-11-17; no AWS; run in this order while no agent writes)
```
set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-wo10b-check.log | tail -n 15; echo "exit: $?"
set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-m2-check-json.log | tail -n 10; echo "exit: $?"
set -o pipefail; test -d /Users/winnguyen/scholarchain-data/rehearsal/s-export && find /Users/winnguyen/scholarchain-data/rehearsal/s-export -type f -exec stat -f %z {} + 2>&1 | awk '{s+=$1} END {printf "files=%d bytes=%d MiB=%.1f limit_bytes=134217728 limit_files=1000\n", NR, s, s/1048576}' 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-export-size.log | tail -n 5; echo "exit: $?"
set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-harness.log | tail -n 60; echo "exit: $?"
grep -n " FAIL " /Users/winnguyen/scholarchain-data/logs/phase15-w2-harness.log
```
Expected:
1. `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0. If `terraform fmt -check` fails, run `terraform fmt -recursive infra/terraform` once, then `git diff --stat infra/terraform`, and paste both.
2. `MANIFEST_JSON_CHECK OK manifests=6`, exit 0.
3. One line `files=N bytes=B MiB=M ...`. The runtime starts only if B <= 134,217,728 and N <= 1,000; otherwise the export limit goes to storage-engineer. If the path does not exist (nothing printed, exit 1), name the local directory that holds `_SUCCESS` and `export-manifest.json`.
4. Harness: 1,659 + 85 Wave 2 cases. Expected failures: the alignment items WO-10 named (existing cases on fast/slow services, Container Insights disabled, `desired_count` 0-2, retention 3 days, runtime header, `session-down` contents, IAM role and statement counts or ids, `container_environment`, SG lists), the `env-read` load-client exemption, and any `collect.sh` case pinning the removed `list-instance-groups` call (R14). Paste the FAIL list; they go to their owners in one batch.

## 7. Batch B after repair batch w2-1 (2026-11-17; no AWS; run in this order; no agent holds sbt or writes)
```
set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rb1-check.log | tail -n 15; echo "exit: $?"
set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rb1-check-json.log | tail -n 10; echo "exit: $?"
set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-rb1-harness.log | tail -n 60; echo "exit: $?"
grep -n " FAIL " /Users/winnguyen/scholarchain-data/logs/phase15-w2-rb1-harness.log
set -o pipefail; SCHOLARCHAIN_S_EXPORT_DIR=/Users/winnguyen/scholarchain-data/rehearsal/s-export sbt -J-Xmx1g "testOnly scholarchain.storage.ExportHeapMeasureSuite" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-heap-measure.log | tail -n 60; echo "exit: $?"
```
Expected:
1. `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0.
2. `MANIFEST_JSON_CHECK OK manifests=6`, exit 0.
3. `RESULT pass=1752 fail=0` (test-engineer's count by reading: 1,747 + 5 new self-tests); `grep` prints nothing (exit 1 is fine). Any FAIL: paste the list.
4. HEAP-MEASURE: the suite prints retained and peak heap for the selective load (three tables, ~53.4 MiB on disk) and passes. If retained <= 400 MiB and peak <= 800 MiB, service memory goes to 2048 MiB; otherwise it stays 4096. An `OutOfMemoryError` at `-Xmx1g` also means 4096. Paste the printed heap lines. Note: this export predates SP-2, so it holds the old 11 tables; the six new tables are sized after A.

## Morning batch (2026-11-17; after repair batch w2-2; no AWS; run in order; no agent holds sbt or writes)
Supersedes section 7 items 3-5. HEAP-MEASURE is not in this batch: it runs on A's 17-table export in the A + B session (P15-29).
```
cd /Users/winnguyen/ScholarChain
rm /Users/winnguyen/ScholarChain/scratch-unused.tmp
set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-check.log | tail -n 15; echo "exit: $?"
set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-check-json.log | tail -n 10; echo "exit: $?"
set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-harness.log | tail -n 60; echo "exit: $?"
grep -n " FAIL " /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-harness.log
set -o pipefail; python3 -I load-tests/lib/rehearse_shell.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-rehearse-shell.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash experiments/b-burst/rehearse-collect.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-rehearse-b-collect.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash experiments/b-burst/rehearse.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-rehearse-b.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -I load-tests/query-load/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-rehearse-load-client.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -I experiments/c-embedding-backlog/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-rehearse-c.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -I experiments/f-failure-recovery/rehearse.py 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-rehearse-f.log | tail -n 60; echo "exit: $?"
```
Then section 3 (dockerLocal and the three image commands) and section 4 (load-client image) as written, with log names `phase15-w2-mb-*`. Optional negative check for m6 (expected: build fails with "PYTHON_BASE must be pinned by digest"):
```
set -o pipefail; docker build -f packaging/load-client/Dockerfile --build-arg PYTHON_BASE=python:3.12-slim-bookworm -t scholarchain-load-client:nodigest load-tests/query-load 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-w2-mb-load-client-nodigest.log | tail -n 20; echo "exit: $?"
```
Expected:
1. `CHECK_RESULT=PASS_WITH_SKIPS`, exit 0 (terraform fmt and validate cover the B1c/B2 and OD-1 gating Terraform, which no agent could validate; if fmt fails run `terraform fmt -recursive infra/terraform` once and paste `git diff --stat infra/terraform`).
2. `MANIFEST_JSON_CHECK OK manifests=6`, exit 0.
0. `rm` of the stray file infra-engineer created by mistake (content `x`): no output.
3. `RESULT pass=1883 fail=0` (by reading, unrun: 1,764 + 16 collector cases + 98 gating cases (w2rb4) + 2 smoke-exemption cases (w2rb5) + 3 smoke-key cases (w2rb6)); `grep` prints nothing. Any FAIL goes to test-engineer with its `CASE ... FAIL` line.
4. Rehearsals (counts by reading, unverified): shell `pass=27 fail=0`; b-burst collect `pass=35 fail=0`; b-burst `rehearse.sh` `fail=0`; load client `pass=45`; C `pass=35`; F `pass=8`; exit 0 each.
5. dockerLocal `[success]`; `--help` outputs list `SCHOLARCHAIN_EXPORT_RUN_ID` for AwsRuntime; no `ClassNotFoundException`.
6. Load-client build succeeds with the digest; `id` prints `uid=10001`.
Paste each RESULT/REHEARSAL line and any FAIL.

## Pending
Session commands for C-F, the WO-10 Terraform plan/apply, approval records and the local rehearsal of the services against fixtures are added after WO-10 (held until the Wave 1 commit) and the WO-7b decision.
