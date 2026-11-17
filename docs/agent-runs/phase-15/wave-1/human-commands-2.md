# Wave 1 human commands after repair batch 2 (orchestrator, 2026-11-17)

Run from `/Users/winnguyen/ScholarChain` with `!`, in order. No AWS. Logs in `/Users/winnguyen/scholarchain-data/logs/`.

## 1. Syntax and lint (one file per `bash -n`)
```
set -o pipefail; bash -n scripts/emr/run-s-pipeline.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-bash-n-1.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n scripts/emr/lib-emr.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-bash-n-2.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-bash-n-3.log | tail -n 60; echo "exit: $?"
set -o pipefail; shellcheck -x scripts/emr/run-s-pipeline.sh scripts/emr/lib-emr.sh scripts/emr/capability-check.sh experiments/b-burst/run.sh experiments/b-burst/collect.sh experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-shellcheck.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-check.log | tail -n 60; echo "exit: $?"
```

## 2. Experiment rehearsals and the manifest JSON check (expected in brackets)
```
set -o pipefail; bash experiments/b-burst/rehearse.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-rehearse-b.log | tail -n 60; echo "exit: $?"            [pass=59 fail=0]
set -o pipefail; bash experiments/a-baseline/rehearse.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-rehearse-a.log | tail -n 60; echo "exit: $?"         [pass=44 fail=0]
set -o pipefail; bash experiments/b-burst/rehearse-collect.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-rehearse-collect.log | tail -n 60; echo "exit: $?"  [pass=25 fail=0]
set -o pipefail; bash experiments/rehearse-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-rehearse-json.log | tail -n 60; echo "exit: $?"    [pass=10 fail=0]
set -o pipefail; bash experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-check-json.log | tail -n 60; echo "exit: $?"         [MANIFEST_JSON_CHECK OK manifests=6]
```

## 3. Harness (long), then the failure list if any
```
set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb2-harness.log | tail -n 60; echo "exit: $?"
grep -n " FAIL " /Users/winnguyen/scholarchain-data/logs/phase15-rb2-harness.log
```
