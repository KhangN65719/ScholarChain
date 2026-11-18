# Phase 16 Wave 2 review 5 verdict (docs-only re-review of W2-RB4)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep/Glob only.

**Verdict: APPROVE.** N2 RESOLVED.

- `experiments/README.md:85` "the then 60 (now 75) and 90 minute caps of A and B": "now 75" matches row `:71` and `MAX_MINUTES=75` at `experiments/manifests/experiment-a-baseline.manifest:29` (comments at lines 13 and 18 agree). "then 60" is supported by the Phase 13 O1 option "caps 60/90/150" (`docs/agent-runs/phase-13/report-01-infra-engineer-f1-f4.md:37`). B's 90 matches `:72` and `:83`. Fits the "Known limit accepted by the human (decision O1)" paragraph. Only line 85 changed. The owner did not re-check 75; this review did.

**Wave 2 review closure:** this approval closes the Wave 2 review (reviews 1 to 5) with no open findings. Review 4's out-of-scope notes (stale README lines 20 and 30, optional line 83) stay a later cleanup candidate.

**Pending non-review checks**
1. Human `sbt clean check` (`!` prefix) at the Phase 16 final gate and before the phase commit.
2. Human `run-experiment.sh --print-digest` then `--dry-run` per `experiments/README.md:93-98` (clean tree, approval record binding the manifest).
3. An agent `sbt quick`/`sbt check` against the current sources for the Scala changes (`experiments/evaluation/**`, `modules/services/**`), if not recorded earlier.
4. Other pending gates from reviews 1-3, carried by the orchestrator.

Assumption: "then 60" refers to the A cap under the Phase 13 O1 option.
