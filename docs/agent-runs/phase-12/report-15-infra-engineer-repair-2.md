# Report 15: infra-engineer (work order 15, repair round 2: N1, L-a to L-e), as received (condensed)

STATUS: COMPLETE (edits only). No sbt, no terraform, no AWS call, tests/ untouched. Grep was refused, so stale text was found by reading; a stale string may be missed.
Files changed: infra/terraform/COST_ESTIMATE.md, README.md, modules/s3vectors/{main,variables}.tf, modules/workflow/variables.tf, environments/course/{variables,main}.tf, experiment-size-{s,m,l}.tfvars, scripts/lib.sh, smoke-test.sh, README.md. run-bounded-pipeline.sh unchanged (burn rates already assume Neptune on; no number there changed).

## Cost headline (endpoint mode, Neptune provisioned)
| Case | LOW | HIGH |
|---|---|---|
| Whole project S+M+L | $10.32 (was 9.62) | $38.70 (was 36.44) |
| S+M only | $7.46 (was 6.94) | $25.42 (was 23.71) |
| L increment | $2.82 | $13.32 |
| NAT alternative, whole project | $9.62 | $36.64 |
| NAT alternative, S+M only | $6.86 | $23.41 |
| Gate figure (spend before L, at most) | $20.68 | $10.18 |
S+M at the HIGH bound does NOT fit under $25: $25.42, $0.42 over. At LOW it fits with $17.54 to spare. If the old timeline rows held a hidden NAT data share, S+M HIGH could be up to $0.68 lower (about $24.74); not a proof. At HIGH, S+M alone is past the L gate, so L would not run; the whole project at HIGH is $13.70 over. At LOW the $10 alert is first passed during the L run. Stated in the header and sections 0, 3, 3a and 5; new threat row 12 and open item 13.

## N1: fixed (review only)
Overlays and Terraform defaults unchanged (D7). Neptune hours now 19.1 LOW and 34.2 HIGH (were 8.5 and 13), i.e. 10.6 and 21.2 more. The review named 7.6 and 15.7 hours missing (runs 14.1, 14.2, 15.3, 15.4); the agent also added 3 and 5.5 hours for 15.5, because the L overlay keeps Neptune up for the whole 6 to 10 hour session, not just the 3 to 4.5 hour query window (beyond the review's list; the estimate says so). Service-task hours added: M overlay's 2 tasks in 14.2 and 15.3 (5.2 and 11.4 task-hours, $0.06 and $0.14); Fargate line now $1.64 LOW and $3.09 HIGH; 15.4 already prices 4 tasks. Recomputed with arithmetic shown: section 1.2, Fargate rebuild, Neptune rows in 2.2, every timeline row (14.1, 14.2, 15.3, 15.4, 15.5), running totals and alert column, 3a, section 4 table and sums, threats 2 and 11, section 8 ranks and decisions. Timeline and service table still differ by $0.04 (same gap as before).
Proposals only, none built: P1 Neptune off for 15.3 and 15.4: S+M HIGH $24.22 (depends on the stub load test not needing Neptune, UNVERIFIED); P2 shorter load windows in 14.1 and 14.2: saves $0.04 LOW and $0.15 HIGH, $24.07 HIGH with P1; P3 Neptune Free Tier: S+M $6.67 LOW and $23.00 HIGH (eligibility UNVERIFIED); P4 NAT: $23.41 HIGH. None is a proof of fit.

## L-a: partly fixed (documented only)
A precondition cannot see the previous `acknowledge_vector_loss` value, so the true-to-false flip cannot be refused reliably. The comment in modules/s3vectors/main.tf no longer says the flip is harmless; it states the gap, the operating rule (flip it back in its own apply with the dimension unchanged) and the second layer (`plan.sh` flags `aws_s3vectors_index` replacements; the approval record needs `STATEFUL_CHANGE_ACKNOWLEDGED=yes`). Same text in both variable descriptions and the README. Mechanism is a reading of the code, UNVERIFIED until a plan.
## L-b: fixed (variables and tfvars only)
New `pipeline_step_timeout_seconds`, default 3600, range 300 to 7200, wired to the workflow module in environments/course/main.tf. Overlays: S 1800, M 2700, L 3600. Rule: cluster start plus two full steps stays below the run timeout (holds if cluster start is under 1800 s on S and M and under 3600 s on L; the estimate assumes 900 to 1440 s). Cost: a legitimate single step longer than the limit is stopped and cleaned up. README, scripts/README.md and the estimate document the remaining gap (a hang in CreateCluster or a dead client ends in the run timeout, which skips cleanup).
## L-c: fixed (review only)
Every "not run" statement for validate, fmt and `bash -n` replaced with the Run 3 results (all exit 0 on 2026-11-09; shellcheck not installed), each saying what it proves. Also fixed the stale "must run `init -upgrade`" line and the "awaits re-review" cells. Both READMEs now say round 2 edits are not yet re-checked.
## L-d: fixed (review only)
Section 3a: HIGH endpoint range $2.88 (8 endpoints) to $3.24 (9); $2.16 is the 9-endpoint LOW figure. Other quotes were per-day LOW-to-HIGH ranges and already correct.
## L-e: partly fixed (small hardening; proof is `bash -n` only)
lib.sh gains `physical_path`, `path_within`, `check_state_dir`. smoke-test.sh compares physical, case-folded paths (catches symlink, doubled slash, macOS letter case). `check_state_dir` runs in `verify_approval` and `consume_approval`: refuses a relative path, `.` or `..` segments, and a location inside the repo or `--out-dir`; prints `STATE_DIR_OVERRIDE=` when the override is used. Both weaknesses are under "does not stop" in the lib.sh header and scripts/README.md: an empty marker directory elsewhere still forgets consumed records (not fixable in the script); the out-dir check still misses a symlink swapped after the check, a hard link and a bind mount. The new code has never run beyond syntax.

## What proves what
Human `terraform validate`: the new variable and its wiring, description strings, overlay lines. Human `fmt -check`: alignment of new tfvars and main.tf lines (hand-aligned). Human `bash -n`: lib.sh and smoke-test.sh syntax. Review only: every cost number, README text, timeout reasoning. Phase 14 plan or test: the s3vectors flip gap, symlink and case behavior, timeout behavior.

## UNVERIFIED introduced
The stub load test needing no Neptune (P1); Free Tier eligibility (P3; rate V3 is VERIFIED text only); that the 14.0 Neptune window is switched on on its own, not through an overlay; CPU credit and I/O volumes staying flat with the extra hours; the timeout rule's dependence on cluster start staying under the margin; the new path checks' behavior; the L-a gap mechanism; all Neptune and Fargate hours are assumptions and the rates are the same UNVERIFIED rates (U3 and V8 arithmetic).

## Human must run
`terraform fmt -check -recursive -diff infra/terraform`; `terraform -chdir=infra/terraform/environments/course validate`; `bash -n` on the seven scripts. No `init -upgrade`. Likeliest validate failure: the new variable or its wiring in environments/course/main.tf.
Risks: the estimate no longer claims S+M fits at HIGH; the human chooses among P1 to P4 or accepts the overrun risk (each needs a later work order). Changed lib.sh and smoke-test.sh change the script digests, so earlier approval records are invalid. Round 2 was the last allowed repair round (D7). Blocked-on: nothing.
