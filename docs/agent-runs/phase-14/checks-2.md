# Checks 2: script harness after the us-east-2 migration (human run, read by the orchestrator from the log)

Date: 2026-11-13. Log: /Users/winnguyen/scholarchain-data/logs/phase14-harness-1.log. `bash tests/scripts/run.sh`.

Result: `RESULT pass=680 fail=1`, SKIPPED_CASES 0. (Test-engineer's expected 682 was an estimate; the harness ran 681 cases.)

The one failure:

```
CASE 8.refuse.region-us-east-1 FAIL stderr lacks: manifest REGION must be us-east-2, got: us-east-1exp_refuse [rc=1 err1=EVIDENCE: manifest REGION must be us-east-2, got: us-east-1]
```

Reading: the script's stderr is correct (`EVIDENCE: manifest REGION must be us-east-2, got: us-east-1`, rc=1). The expected fragment in the case has the stray text `exp_refuse` glued to its end, so the substring match can never succeed. This is a test-definition defect in tests/scripts/run.sh (owner: test-engineer), not a script defect. Routed as work order 06.

## Re-run after work order 06 (human, log phase14-harness-2.log)

`RESULT pass=682 fail=0`, SKIPPED_CASES 0, exit 0. The restored `8.refuse.unknown-experiment-id` case passes as well. Observed from the last 8 log lines the human's run printed; the full log is on disk.

All other Region, ceiling, tfvars, manifest (A to F, count) and Terraform-default cases passed, as did the 12.* and 0.* hygiene cases (no key material in output, real repository untouched).
