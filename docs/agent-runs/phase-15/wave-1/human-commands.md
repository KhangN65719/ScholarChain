# Wave 1 human commands (orchestrator, 2026-11-17)

Agents cannot run these (the guard allows them only `ls`, the three sbt forms and file tools). Run from `/Users/winnguyen/ScholarChain` with the `!` prefix, in order. Only 1 and 2 touch AWS, and both are read-only. Paste the tails back, or say "done" and the logs are read from `/Users/winnguyen/scholarchain-data/logs/`.

## 1. Quotas (read-only; codes verified in `doc-verification.md`)
```
set -o pipefail; AWS_PROFILE=scholarchain aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A --region us-east-2 --query 'Quota.[QuotaCode,QuotaName,Value,Unit,Adjustable]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-ec2-quota.log | tail -n 60; echo "exit: $?"
set -o pipefail; AWS_PROFILE=scholarchain aws service-quotas get-service-quota --service-code fargate --quota-code L-3032A538 --region us-east-2 --query 'Quota.[QuotaCode,QuotaName,Value,Unit,Adjustable]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-fargate-quota.log | tail -n 60; echo "exit: $?"
```

## 2. Bedrock model access (read-only; the command exists per docs, the model ID is unverified)
```
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock get-foundation-model-availability --model-id anthropic.claude-haiku-4-5-20251001-v1:0 --region us-east-2 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-bedrock-availability.log | tail -n 60; echo "exit: $?"
set -o pipefail; AWS_PROFILE=scholarchain aws bedrock list-inference-profiles --type-equals SYSTEM_DEFINED --region us-east-2 --query 'inferenceProfileSummaries[].[inferenceProfileId,status]' --output text 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-precheck-bedrock-profiles.log | tail -n 60; echo "exit: $?"
```

## 3. Dataset (WO-4; no AWS)
```
set -o pipefail; python3 -B datasets/scripts/chunkset.py snapshot 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-1.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py chunks --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export --built-on 2026-11-16 --pipeline-run-id run-v1-192de0fd-1442-4508-9065-09c44775643b 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-2.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py validate --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-3.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/chunkset.py replay --export-dir /Users/winnguyen/scholarchain-data/rehearsal/s-export --output /Users/winnguyen/scholarchain-data/phase15-chunk-replay 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-4.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B datasets/scripts/datasets.py validate 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-5.log | tail -n 60; echo "exit: $?"
set -o pipefail; python3 -B -m unittest discover -s datasets/scripts -p 'test_*.py' 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wo4-6.log | tail -n 60; echo "exit: $?"
```

## 4. Infra static checks (WO-1, WO-1b; no AWS)
```
set -o pipefail; bash scripts/check.sh --skip-sbt 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-check.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n scripts/emr/run-s-pipeline.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-bash-n.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n scripts/emr/lib-emr.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-bash-n-lib.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash -n scripts/emr/capability-check.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-bash-n-cap.log | tail -n 60; echo "exit: $?"
set -o pipefail; shellcheck -x scripts/emr/run-s-pipeline.sh scripts/emr/lib-emr.sh scripts/emr/capability-check.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-shellcheck.log | tail -n 60; echo "exit: $?"
```

## 5. Experiment rehearsals (WO-3; no AWS; expected `REHEARSAL pass=24 fail=0` each)
```
set -o pipefail; bash experiments/a-baseline/rehearse.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-rehearse-a.log | tail -n 60; echo "exit: $?"
set -o pipefail; bash experiments/b-burst/rehearse.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-rehearse-b.log | tail -n 60; echo "exit: $?"
```

## 6. Script harness (WO-12a; no AWS; long)
```
set -o pipefail; bash tests/scripts/run.sh 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase15-wave1-harness.log | tail -n 60; echo "exit: $?"
```

Manifest digests (`--print-digest`) wait until the WO-4 snapshot ID is in the manifests, since that edit changes the digests.
