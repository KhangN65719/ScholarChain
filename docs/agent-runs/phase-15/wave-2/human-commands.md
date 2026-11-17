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

## Pending
Session commands for C-F, the WO-10 Terraform plan/apply, approval records and the local rehearsal of the services against fixtures are added after WO-10 (held until the Wave 1 commit) and the WO-7b decision.
