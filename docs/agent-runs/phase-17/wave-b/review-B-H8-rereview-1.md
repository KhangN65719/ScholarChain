# Re-review 1: README H-8 updates (repair B-H8c)

Author: code-reviewer. Recorded by the orchestrator on 2026-11-19, as received.
Inputs: the verbatim `git diff -- README.md` (29.9 KB) and `git diff --stat` (`README.md | 50`, 25 insertions, 25 deletions), both supplied by the orchestrator.

**Verdict: REQUEST CHANGES.** P-1, findings 1-10 and every advisory item are resolved. Two new items arose on lines the repair touched or depends on.

## Resolved

- P-1: the full diff was read; its 25 changed lines match `--stat`.
- Finding 1: P2 now quotes "likely a formatter", says the blank line was removed, and says no `scalafmtOnly` text was shown (`human-commands.md:93`).
- Finding 2: "final tree" is gone from steps 6 and 7 and the heading. The D12 change is new item N-2.
- Finding 3: step 5 labels P1b a direct script probe and points to step 6.
- Finding 4: the heading separates the replay steps from the `dc15299` steps.
- Finding 5: line 7 is redefined.
- Finding 6: line 300 has the NOT REPLAYED fallback.
- Finding 7: step 1 names no actor.
- Finding 8: step 5 says "the replaying agent", and the name is marked as inferred.
- Finding 9: step 4 says the record doesn't say how the work order was supplied.
- Finding 10: the status line says the branch was deleted and nothing was committed.
- The other figures on touched lines match the record.
- Advisory items resolved: :7, :245, :108, :97, :89, :148, :308-310, :127 and :372. The :397 label is restated, but its new wording raised N-2.

## New findings

- N-1, README.md:80. "OBSERVED (record), not replays:" is left over from the old line 7 definition, so it conflicts with the new one. The items listed are pre-H-8 runs, so this is a wording inconsistency, not a false claim. Suggested fix: "OBSERVED (record), earlier runs (not H-8 replays):".
- N-2, README.md:397 (D12). "validate and plan results for commit `dc15299` PENDING (human)" names a target that no record sets. Suggested fix: "validate and plan results PENDING (human; target is the submitted tag, itself PENDING)", or cite a record that sets `dc15299` as the target.

## Reviewer assumption

The zero-failure `sbt check` group totals are taken to imply that the slow Hadoop and Spark suites ran and passed. The README already labels this indirect evidence.

Next gate: documentation-engineer repairs N-1 and N-2; re-review 2 covers only those lines and anything newly touched.
