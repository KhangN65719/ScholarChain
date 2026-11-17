# Wave 1 follow-up batch 4: review 2 minor findings (orchestrator, 2026-11-17)

Source: `review-2-verdict.md` (APPROVE with N1-N6). Human decisions P15-19 and P15-20. Common rules of `work-orders.md` apply: own paths only, no AWS, no sbt, no git writes, do not weaken any test or rehearsal, repeat the full report when the stop hook fires.

## R12 experiment-engineer
- N1: `experiments/a-baseline/derive-table.py:172-189` must read Spark row counts from the real run-manifest shape `outputs[].table` / `outputs[].rows` (`modules/spark/src/main/scala/scholarchain/spark/CollaborationPipeline.scala:1276-1279`, read only). Fix the fixture `experiments/a-baseline/fixtures/synthetic-run-records/reports/spark-run-manifest.json` to that real shape. Add a rehearsal check in `experiments/a-baseline/rehearse.sh` that the derived table shows the fixture's Spark row counts (not NOT RECORDED), and a check that the old `tables` shape is NOT accepted as Spark rows (or is reported NOT RECORDED). State the new expected pass count.
- N2 (P15-20): correct the comment at `experiments/b-burst/run.sh:30-32`: if the 88-minute stop fires, the cluster is still terminated (cost bounded), but the teardown check can take up to 300 s (`scripts/run-bounded-pipeline.sh:482-491`), so the 90-minute entry limit may cut the end of the run record. Keep the token `exp_run_pipeline` only on the call line. Add the same limitation to `experiments/b-burst/README.md` limitations.
- N4: `experiments/b-burst/README.md:21-22` redaction example must cover every raw file listed at line 19 that can hold an account ID (not only `collected/*.json`), still writing redacted COPIES and leaving raw files unchanged.
- N5: the run command at `experiments/b-burst/README.md:68` uses a 20 USD cost ceiling; set it consistent with the B manifest ceiling (2.40) and the P15-10 session rule (at most 14 USD billed plus session HIGH).

## R13 infra-engineer
- N3: `scripts/README.md:584` claims every command is a get-/list- call; reword to name the two exceptions in item 5 (`aws stepfunctions validate-state-machine-definition`, non-mutating, and local `terraform show -json` of a saved plan). `scripts/README.md:648`: the validate API is verified (`docs/agent-runs/phase-15/wave-1/doc-verification.md`); remove the "unverified" wording and cite that file.
- N6: `infra/terraform/README.md:59`: state that `emr_managed_scaling_max_units` counts all instances including the primary and the module subtracts the primary for the ComputeLimits (core and task only), with the elasticity-s render 1/3/3/3.
- Check that `15.presession.*` harness cases still pass against the new wording (read `tests/scripts/run.sh`, do not edit it); if a case pins the old sentence, report it as BLOCKED-ON test-engineer with the line.
