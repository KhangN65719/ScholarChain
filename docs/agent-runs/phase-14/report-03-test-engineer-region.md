# Report 03: test-engineer, us-east-2 harness (condensed by the orchestrator; author: test-engineer)

STATUS: BLOCKED-ON experiment-engineer (resolved by work order 05). Harness edited, nothing run (bash blocked for agents, Phase 13 D11).

Modified: tests/scripts/run.sh, tests/scripts/README.md.
- Fixtures flipped to us-east-2 (targets, approval record writer, plan metadata, base manifest, build defaults, smoke/read-secret ARNs). Three region-not-us-east-1 cases renamed region-not-us-east-2 with the flipped message (reason: D1). cost-above-25 and cost-just-above-25 became cost-above-ceiling and cost-just-above-ceiling. Every literal 25/26 now derives from CEIL read from the copied lib.sh; cost-differs values use CEIL-5. No assertion deleted or loosened.
- 112 new cases: 63 across nine approval-gated keys (Region refusals with D1 note, print-digest refusal, record Region mismatch), 2 smoke ARN cases, 2 manifest/argument cases, and 45 `13.*` cases (ceiling pinned to D6, plan/deploy/smoke/run-experiment real-run refusals, 16 check_tfvars_region cases, 5 direct lib.sh cases, per-committed-tfvars cases, 3 Terraform default text reads including the Haiku `us.` profile, smoke defaults vs Terraform defaults and vector dimension 256).

Expected counts (not observed): 570 + 112 = 682 cases. On the tree before work orders 02 and 05: pass 675, fail 7 (six 8.committed-manifest.A-F because manifests still say us-east-1; 13.ceiling-pinned-to-D6 until lib.sh is 20). Work order 02 has since set lib.sh to 20; work order 05 fixes the manifests, so expected 682/0 after both.

Risks: tests/** not searched; ~58 Scala suites unread, so "no other us-east-1 in tests/**" is unproven (sbt clean check is the net). The Terraform text cases match literal spacing. Record REGION branch at lib.sh line 485 unreachable in test. Changing a manifest changes its sha256.

Human: run `bash tests/scripts/run.sh`, plus bash -n and shellcheck -x on tests/scripts/run.sh; paste RESULT and every FAIL line.
