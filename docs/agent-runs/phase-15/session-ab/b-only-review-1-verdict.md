# B-only runbook review 1 (code-reviewer, received 2026-11-17; summary as received)

**REQUEST CHANGES.** 1 BLOCKER, 1 MAJOR, 6 MINOR. Read-only.

- B1 :119 (0.5): uncommitted changes outside `docs/` (SESSION2.md, three course files, `tests/scripts/run.sh`, `experiments/pipeline-inputs/*`) make 0.5 print `PATHS_OUTSIDE_DOCS=7` and stop before 1.3e. Accept exactly the 7 paths of 1.3; stop on anything else (`modules/`, `build.sbt`, `project/`, `packaging/`).
- M1 :211-214 (1.1) vs P15-40: print billed + lag + 2.93 + 4.5 against 14 with GO/OVER; with `LAG_USD=1.10` billed must be <= 5.47 (5.33 + 1.10 + 7.43 = 13.86). Blocking beyond B is the human's call; record that P15-40 applies.
- m1 :275-278: pin the allowlist (1.4 and 1.3d) to the two new `experiments/pipeline-inputs/` files (`s-b-burst.template.json` is read by 1.7b-new).
- m2 :332 (1.7a) fails open on STS/terraform/s3 errors (counts 0 -> `B_RUN_REUSABLE=yes`); require a 12-digit account and non-empty `DATA_BUCKET`.
- m3 :475, :641: a re-plan into the same out-dir leaves two `course-*.tfplan`; use a new out-dir.
- m4 :211, :214: awk float compare at the boundary; compare in cents or say "below".
- m5 :235 (1.3a): name `CHECK_RESULT=PASS_WITH_SKIPS` (`scripts/check.sh:354`) and the other `GATE ...=PASS` lines.
- m6 :62, :309: close two UNVERIFIED notes (`terminate-clusters` valid; jar layout `upload-inputs.sh:116-128`, `run-s-pipeline.sh:131-132`); state that 1.6b skips `lib/*.jar` (acceptable: 1.4 excludes `build.sbt`, `project/`).

Checked, no finding: up 11/3/3 (A-up 11/7/3 minus 4 retention; `SESSION2.md:87,91`), down 0/1/8 course + session-down only (:602), IAM none automated; 1.4 gate (40-hex pinned, ancestry, branch, clean, tfvars comment-only, `variables.tf:421`/`:415` single-line descriptions); 1.3d fail-closed; B column (:430, :488); records single-use (`lib.sh:428-432`, `run-bounded-pipeline.sh:291`, `run-experiment.sh:266`); arithmetic 2.93, endpoints 8 x 0.015, B 2.27/2.33; wall clock (B by T0+3:05, down by T0+4:00); commands; no repository write after 1.3e.

Pending: regexes in 1.4, 2.2b, 2.3b, 2.6b unrun; 1889 harness count; actual diff; `LAG_USD` choice.
