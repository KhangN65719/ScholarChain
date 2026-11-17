# Wave 1 human commands after repair batch 3 (orchestrator, 2026-11-17)

Run from `/Users/winnguyen/ScholarChain` with `!`. No AWS.

## 1. Syntax, lint, rehearsals
```
set -o pipefail; bash -n scripts/emr/run-s-pipeline.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-bash-n-1.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n scripts/emr/lib-emr.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-bash-n-2.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-bash-n-3.log | tail -n 60; echo "exit: $?"
set -o pipefail; shellcheck -x scripts/emr/run-s-pipeline.sh scripts/emr/lib-emr.sh scripts/emr/capability-check.sh experiments/b-burst/run.sh experiments/b-burst/collect.sh experiments/check-manifest-json.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-shellcheck.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash experiments/b-burst/rehearse.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-rehearse-b.log | tail -n 3; echo "exit: $?"
set -o pipefail; bash experiments/b-burst/rehearse-collect.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-rehearse-collect.log | tail -n 3; echo "exit: $?"
```
Expected: exit 0 each; shellcheck no output; B pass=59; collector pass=25.

## 2. Harness
```
set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-rb3-harness.log | tail -n 60; echo "exit: $?"
grep -n " FAIL " /Users/winnguyen/scholarchain-data/logs/phase15-rb3-harness.log
```
Expected: `RESULT pass=1659 fail=0`, `SKIPPED_CASES 0`.
