# Work order 15: infra-engineer, repair round 2 (review 2 findings N1, L-a to L-e)

Owner: infra-engineer. Scope: `infra/terraform/**` and `scripts/**`. No sbt, no terraform commands (the human runs them), no AWS calls, no credentials. Do not weaken any safeguard. This is the last allowed repair round: be exact and do not widen scope.

Read first: review-round-2.md (findings with file and line), decisions.md D7, report-11, terraform-checks.md.

## N1 (Medium): the estimate omits Neptune hours that the overlays switch on
Ruling D7: keep the overlays as they are (Neptune on) and fix the estimate. Add the missing Neptune db.t4g.medium hours for runs 14.1, 14.2, 15.3 and 15.4 (and any other run where the overlay brings Neptune up), and add the Fargate service-task hours for the M and L overlays in those sessions. Recompute the affected rows, section 3a, the S+M, L-increment and whole-project totals (LOW and HIGH, endpoint and NAT columns) and the tier-L gate figures, with the arithmetic shown. Mark rates VERIFIED or UNVERIFIED as before. If S+M at HIGH no longer fits under $25, say so in section 0 and 3a in plain words (do not soften it), and list cheaper options as proposals only: for example a pipeline-only overlay with Neptune off until the load and query windows, shorter Neptune windows, or Neptune Free Tier use (eligibility unverified). Do not change the overlays or the Terraform defaults in this round. Update scripts/README.md and run-bounded-pipeline.sh reference text only if they repeat a number that changed.

## L-a
In modules/s3vectors/main.tf correct the comment at :42-43 (flipping `acknowledge_vector_loss` back is not always harmless). Either refuse the true-to-false flip while the dimension differs, if a precondition can do that reliably, or document the exact gap in the comment, the variable description and the README, and name the second layer that still catches it (`plan.sh` flags `aws_s3vectors_index` replacements; the approval record needs `STATEFUL_CHANGE_ACKNOWLEDGED=yes`). Do not claim the guard is stronger than it is.
## L-b
Set the step timeout per tier (or document precisely that on tier S a long Hadoop step followed by a hung Spark step reaches the run-level timeout first, and that path skips CleanupOnFailure; the idle auto-termination and the human's cleanup are then the only stops). Prefer the per-tier setting if it needs only variable and tfvars changes.
## L-c
Remove every stale "not run" statement for validate, fmt and `bash -n` (infra/terraform/README.md:3, :215-216, :230; scripts/README.md:3, :87), and state the results as terraform-checks.md Run 3 shows: validate, fmt -check and `bash -n` exit 0 (2026-11-09); shellcheck not installed. Say what each proves. Read the whole of both READMEs for other stale statements.
## L-d
Fix section 3a: at HIGH the endpoint range is $2.88 (8 endpoints) to $3.24 (9). Check every other place that quotes it.
## L-e
Add both weaknesses to the "does not stop" lists (scripts/README.md and the lib.sh header): `SCHOLARCHAIN_OPS_STATE_DIR` pointing at an empty marker directory lets a consumed record be reused; and the smoke-test `--out-dir` repository check is a string-prefix match that a symlink, doubled slash or macOS letter-case difference gets past. If a small hardening is possible (for example, resolve both paths with a physical-path function before the comparison; refuse `SCHOLARCHAIN_OPS_STATE_DIR` outside an expected location), apply it and keep `bash -n` clean; otherwise document only.

## Procedure
List which check proves each item (human `validate`, `fmt -check`, `bash -n`, or review only). Re-read each changed block. Keep HCL formatted as `terraform fmt` would.

## Report (plain text in your final message)
Per finding: fixed, partly fixed or not, file and proof; the restated cost headline and the S+M HIGH conclusion in plain words; every UNVERIFIED item introduced; what the human must run.
