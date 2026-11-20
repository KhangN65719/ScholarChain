# Review verdict: README H-8 updates (B-H8a, B-H8b)

Author: code-reviewer. Recorded by the orchestrator on 2026-11-19, as received.
Scope: `README.md` diff after work orders B-H8a and B-H8b (documentation-engineer); evidence `docs/agent-runs/phase-17/human-commands.md`, "Results recorded".
Orchestrator note: the diff given to the reviewer was abridged ("..." elisions). The reviewer flagged this as pending item P-1. The orchestrator held the full `git diff -- README.md` output, ran before the review, and did not pass it on verbatim.

**Verdict: REQUEST CHANGES**

## Findings

1. README.md:306. "(the formatter ran)" overstates P2. The record (`human-commands.md:93`) has the hook message "likely a formatter", records that the blank line was removed, and says no `scalafmtOnly` text was shown. Quote those instead.
2. README.md:324, :328. "the final tree" and "the final tree's gate" are not in the record and are inaccurate, because the final tag is PENDING and the README edit is after `dc15299`. Say "commit `dc15299`".
3. README.md:306. P1b is a direct script probe, but it is listed in step 5 as fresh-session automatic-firing confirmation. CLAUDE.md, "Hook operation", keeps direct probes separate. Drop it from step 5 or label it.
4. README.md:319. The heading says "steps 1 to 7 OBSERVED (record)", but steps 2 and 6 ran on `dc15299`, not the replay. Qualify the heading.
5. README.md:7 versus :300 and :319-332. The definition of OBSERVED (record) says it "is not a replay", yet the H-8 replays are now marked with it. Update line 7 or use a separate mark.
6. README.md:300. Workflow steps without a record lost their NOT REPLAYED mark. Add "Steps and items without an OBSERVED (record) note remain NOT REPLAYED."
7. README.md:323. "the human created branch" names an actor the record does not. Use the record's wording.
8. README.md:327. The record says "the replaying agent"; it does not name `domain-engineer`. Use "the replaying agent", or mark the name as inferred.
9. README.md:326. Step 4 is marked observed, but the record does not say how the work order was supplied (the `<final-tag>` is PENDING). Say so.
10. README.md:332. The status line omits that the replay branch was deleted and nothing was committed (`human-commands.md:111`). Add it.

## Pending

- P-1: the verbatim current `git diff -- README.md` and `--stat` are needed for the re-review, because the supplied diff was abridged.

## Advisory: stale passages outside the diff

- :7 "the human replays commands at gate H-8" (tense; see finding 5).
- :245 "Every command in this README is NOT REPLAYED until H-8", contradicted by :319-332.
- :108 The Spark slow suites ran in the H-8 part 1 `sbt check` on `dc15299`; this could be OBSERVED (record).
- :97 `PipelineDriverOutputsSuite` passed in the same run (indirect evidence).
- :89 Partly covered. :96 and :100 are still not replayed.
- :148 The "until H-8" wording is stale; still NOT REPLAYED.
- :308-310 "human-relayed" results lack a mark and a source.
- :127, :372, :397 PENDING (human, H-8) items are not covered by H-8; the label needs restating.
- :64, :91, :96, :100, :112, :179, :193, :225, :314 have no conflict.

Next gate: documentation-engineer repairs findings 1-10 in one round; re-review covers only the changed items and newly touched lines, with the verbatim diff.
