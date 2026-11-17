# Wave 1 repair batch 3: harness run 3 and shellcheck (orchestrator, 2026-11-17)

Source: `phase15-rb2-harness.log` (`RESULT pass=1649 fail=7`, exit 1) and `phase15-rb2-shellcheck.log` (SC2155, exit 1). Human direction: send all 7 plus SC2155 to owners in one batch; do not weaken any assertion; the manifests case and R5's check must agree on the JSON spelling of the list keys. This batch repairs check failures; it is not a review repair round.

## Per-case decision (orchestrator, from the R5 design and the code as landed)

| # | Case / finding | Wrong side | Reason | Owner |
|---|---|---|---|---|
| 1 | `15.experiment.b-collect.static.no-aws-call-outside-aws-read-and-the-directory-is-made-with-mkdir-without-p` | stale case | R5 m5 (review finding) deliberately moved writes into `collected.staging/` with one rename; `mkdir "$STAGE" || die`, `(set -C; aws_read ... > "$STAGE/$name" ...)`, `chmod a-w "$STAGE"/*` and `chmod a-w "$OUT"` keep each protection under the new names | test-engineer (R10) |
| 2 | `15.experiment.entry.static.committed-pipeline-limits-...` (`exp_run_pipeline` 2 times, expected 1) | code comment | second hit is the comment at `experiments/b-burst/run.sh:32`; keep the case strict and reword the comment | experiment-engineer (R9) |
| 3 | `15.experiment.manifests.each-of-the-six-manifests-has-a-json-copy-...` (MISSING `stop_conditions`, `metric_names`, `metric_namespaces`) | case parser | JSON copies hold these three keys as arrays (R5 design, `manifest-json.py` and the human's `MANIFEST_JSON_CHECK OK manifests=6` agree); the case's independent flattener drops array values. Fix: compare an array to the KEY=VALUE value split on `,` element by element, order kept, exact match | test-engineer (R10) |
| 4 | `15.presession.every-aws-call-is-a-read-get-list-or-describe-...` (block 9 `aws stepfunctions validate-state-machine-definition`) | case | call is approved (P15-8 pre-session check 5b, `doc-verification.md`) and creates nothing; add it as the ONLY entry of an explicit, commented allowlist of non-mutating calls in the case; every other verb still must be get/list/describe | test-engineer (R10) |
| 5 | `15.presession.every-block-has-the-paste-ready-form...` (block 7 DLQ loop) | doc | block must end `| tail -n 60; echo "exit: $?"` like the rest | infra-engineer (R11) |
| 6 | `15.emr.data-path.the-real-conf-makes-profile-b-burst-fail-clearly-when-no-core-node-registers` (stderr says profile `none`) | to determine; human reads it as a script bug | profile not reaching the data-path check | infra-engineer (R11) |
| 7 | `15.emr.spark-master.profile-a-baseline-pins-yarn-even-with-a-remote-node` (stdout lacks `SMASTER=yarn`) | to determine; human reads it as a script bug | same family as 6 | infra-engineer (R11) |
| 8 | SC2155 at `experiments/b-burst/collect.sh:69` | code | declare `failed` then assign | experiment-engineer (R9) |

For 6 and 7: infra-engineer reads the case text in `tests/scripts/run.sh` and the script path. If the defect is in `scripts/emr/**`, fix it. If the case does not pass the profile the way the script reads it (test plumbing), report `BLOCKED-ON test-engineer` with the exact line evidence and change nothing in `tests/`.

## Common rules
Phase 15 common rules from `wave-1/work-orders.md` apply: own paths only, no AWS, no git writes, no weakening of any assertion, report exact proof per item, repeat the full report when the stop hook fires.
