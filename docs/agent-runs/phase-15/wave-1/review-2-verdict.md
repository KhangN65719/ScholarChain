# Wave 1 re-review (review 2) verdict, code-reviewer (Opus, fresh, read-only; received 2026-11-17)

Recorded by the orchestrator with attribution. The reviewer's full verdict message was replaced by its stop-hook reply (known hook behavior); this file preserves the verdict and findings exactly as restated in that final reply.

**Verdict: APPROVE.** No blocker or major finding remains. Every review-1 finding (K1, K2, M1-M6, m1-m7, a1-a8) is resolved; M4 carries one new minor defect (N1).

## New findings
| ID | Severity | Owner | Finding |
|---|---|---|---|
| N1 | minor | experiment-engineer | `experiments/a-baseline/derive-table.py:172-189` reads Spark row counts from a `tables` key; the real Spark run manifest writes `outputs[].table` / `outputs[].rows` (`modules/spark/src/main/scala/scholarchain/spark/CollaborationPipeline.scala:1276-1279`). Spark graph rows would always show NOT RECORDED. The fixture `experiments/a-baseline/fixtures/synthetic-run-records/reports/spark-run-manifest.json:4` has the wrong shape, so the rehearsal passes. |
| N2 | minor, human decision | experiment-engineer | If B's 88-min pipeline stop fires, the cluster is still terminated (cost bounded), but the following teardown check can take up to 300 s (`scripts/run-bounded-pipeline.sh:482-491`), so the 90-min entry kill can cut the end of the run record. Comment at `experiments/b-burst/run.sh:30-32` understates this. |
| N3 | minor | infra-engineer | `scripts/README.md:584` says every command is get-/list-, but item 5 uses `validate-state-machine-definition` and `terraform show`; `scripts/README.md:648` still calls that API unverified although `doc-verification.md` verified it. |
| N4 | minor | experiment-engineer | Redaction example `experiments/b-burst/README.md:21-22` redacts only `collected/*.json`, not the other raw files listed at line 19. |
| N5 | advisory | experiment-engineer | Run command at `experiments/b-burst/README.md:68` uses a 20 USD cost ceiling, above the 14 USD session rule (P15-10). |
| N6 | advisory | infra-engineer | `infra/terraform/README.md:59` does not say the primary node is subtracted from the scaling limits. |

## Pending gates named by the reviewer
1. Clean-tree harness rerun (human: at the phase gate).
2. Human `git check-ignore` for the new `.gitignore` line.
3. New approval records for B (limits changed).
4. Read-only check of the rendered state machine and scaling limits before apply.
5. A fresh `sbt quick` (after Wave 2 stops writing).
