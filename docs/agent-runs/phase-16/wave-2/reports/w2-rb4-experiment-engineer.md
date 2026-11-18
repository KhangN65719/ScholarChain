# W2-RB4: experiment-engineer report (review 4 N2, docs-only)

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received.

---

**Status: COMPLETE.** Edited only `experiments/README.md:85`. The sentence opens "Known limit accepted by the human (decision O1)", so the "then 60 (now 75)" wording was used. The agent did not read `review-4-verdict.md` (hook refused its grep) and did not re-check 75 against row :71 or `experiment-a-baseline.manifest:29` (taken from the work order; review 4 verified it).

Before: `Known limit accepted by the human (decision O1): a pipeline run at the estimate's HIGH hours may exceed the 60 and 90 minute caps of A and B. A timed-out run still spends and needs a new approval record.`

After: `Known limit accepted by the human (decision O1): a pipeline run at the estimate's HIGH hours may exceed the then 60 (now 75) and 90 minute caps of A and B. A timed-out run still spends and needs a new approval record.`

No Scala edits, sbt or commit.
