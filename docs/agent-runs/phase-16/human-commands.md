# Phase 16 human-side commands and results

Archived by the orchestrator. Each entry records who ran it (P16-5). Logs are under `/Users/winnguyen/scholarchain-data/logs/`. Raw evaluation outputs go to `/Users/winnguyen/scholarchain-data/experiments/runs/phase16` (P16-8).

## Results recorded

| Date | Step | Ran by | Result (as relayed by the human) | Log |
|---|---|---|---|---|
| 2026-11-18 | WO-5 load-client rehearsal (`python3 -I load-tests/query-load/rehearse.py`) | Claude in chat (P16-5) | `REHEARSAL pass=45 fail=0` | `phase16-wo5-loadclient-rehearse.log` |
| 2026-11-18 | WO-5 derive off and on over the Phase 15 D requests (`results-copy/p15-tn-d`), `cmp` of `latency-by-level.csv` | Claude in chat (P16-5) | `cmp` exit 0; `latency-ci-by-level.csv` written | `phase16-wo5-derive-off.log`, `phase16-wo5-derive-on.log`; outputs `phase16-ci-off`, `phase16-ci-on` |
| 2026-11-18 | WO-7 harness `bash tests/scripts/p16-wo7.sh` | Claude in chat (P16-5) | `RESULT pass=23 fail=1`. Real failure: `test_no_pending_wo_text_remains_outside_docs` finds `load-tests/lib/ecs-load-client.sh:14`, `experiments/d-query-load/collect.sh:18`, `experiments/f-failure-recovery/collect.sh:11` (repair P16-9) | `phase16-wo7-harness.log` |
| 2026-11-18 | WO-6 `check.sh --skip-sbt --skip-terraform` | not run separately | covered by the full harness at the gate; watch for `run.sh` cases asserting the old bounded-query text (WO-7 note) | |

| 2026-11-18 | WO-7 harness `bash tests/scripts/p16-wo7.sh` (after repair batches 1-4) | Claude in chat (P16-5) | `RESULT pass=44 fail=0` | |
| 2026-11-18 | `grep -n 'RESPONSE_HEAD\|bounded-query-answer' tests/scripts/run.sh` | Claude in chat (P16-5) | no matches | |
| 2026-11-18 | Wave 1 full harness `bash tests/scripts/run.sh` | human (`!` prefix, in the orchestrator session) | `RESULT pass=1941 fail=8`, `SKIPPED_CASES 0`, `0.real-repository-untouched PASS`. Failures: `5.smoke.question-empty.{real,dry,digest,order}-refused-before-any-work` (stderr was `EVIDENCE: --question needs a value`; lacks `--question is required` and `no default`; repair P16-16); `8.committed-manifest.{G,H,count}` and `15.experiment.manifests.each-of-the-six-manifests-has-a-json-copy-...` (8 manifests, expected 6; G and H `--print-digest` exit 1, `unknown EXPERIMENT_ID`; caused by uncommitted Wave 2 files, P16-17) | `phase16-wave1-run.log` |
| 2026-11-18 | `bash scripts/check.sh --skip-sbt --skip-terraform` | human (`!` prefix, in the orchestrator session) | `CHECK_RESULT=PASS_WITH_SKIPS` (constitution-json, hook-syntax, shell-syntax, shellcheck PASS; sbt and terraform SKIP), exit 0 | `phase16-wave1-check-sh.log` |
| 2026-11-18 | Wave 1 full harness rerun after repair P16-16 (`bash tests/scripts/run.sh`) | human (`!` prefix, in the orchestrator session) | `RESULT pass=1945 fail=4`, `SKIPPED_CASES 0`, `0.real-repository-untouched PASS`. The four `5.smoke.question-empty.*` cases now pass. Remaining failures are exactly the P16-17 set: `8.committed-manifest.{G,H,count}` and `15.experiment.manifests.each-of-the-six-...` | `phase16-wave1-run-2.log` |
| 2026-11-18 08:07 | H-1 billing reading | human | 8.91 USD month-to-date; about 2.73 USD for Phase 15 session 2 so far. Gate: 8.91 + about 0.50 lag + 2.40 HIGH = about 11.81 <= 14: GO (P16-15) | |

## Pending (after the Wave 1 commit)

Use these output paths, not the in-repository ones in the WO-4 report (P16-8). Ran by: ______

```zsh
cd /Users/winnguyen/ScholarChain
mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/experiments/runs/phase16
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.graph.GraphChecks /Users/winnguyen/scholarchain-data/rehearsal/a-export /Users/winnguyen/scholarchain-data/experiments/runs/phase16 --seed 16 --sample 50 --run-id graphchecks-a-export-1" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-graphchecks.log | tail -n 60; echo "exit: $?"
```

```zsh
cd /Users/winnguyen/ScholarChain
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.factory.FactoryCounts docs/agent-runs experiments/evaluation/factory-mapping.tsv /Users/winnguyen/scholarchain-data/experiments/runs/phase16 --rewrites experiments/evaluation/factory-rewrites.tsv --phases 1-15 --run-id factorycounts-1" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-factorycounts.log | tail -n 60; echo "exit: $?"
```

BenchmarkGen (after the Wave 1 commit, so `--generator-commit` names the commit that holds the generator):

```zsh
cd /Users/winnguyen/ScholarChain
set -o pipefail
sbt "evaluation/runMain scholarchain.evaluation.benchmark.BenchmarkGen /Users/winnguyen/scholarchain-data/rehearsal/a-export experiments/benchmark --seed 20261117 --version v1 --vector-export /Users/winnguyen/scholarchain-data/rehearsal/s-export --chunkset-manifest datasets/tier-s/chunks-c/chunkset-manifest.json --generator-commit $(git rev-parse HEAD)" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase16-benchmarkgen.log | tail -n 60; echo "exit: $?"
```
