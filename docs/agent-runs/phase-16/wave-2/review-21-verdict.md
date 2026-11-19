# Phase 16 review 21 verdict (re-review of W2-RB15, EVALUATION.md)

Author: code-reviewer. Received by the orchestrator on 2026-11-19 and recorded with its attribution. Read/Grep only.

**Verdict: REQUEST CHANGES** (two minor, one advisory; owner experiment-engineer). Review 20 findings 1, 2 and advisory 3 closed: line 147 attribution matches `human-commands.md:37`; line 149 citations `factory-counts.json:262` (6), `:168` (5, within `repairFilesByScope` 154-172), totals 264 (`:265`), 135 (`:453`), 153 (`:454`), 150 (`:455`); lines 169 and 924: `:462-464` 86/74/12 totals, 2.2 denominators sum to 74; line 927 added. No names, titles or question/answer text. Redaction Grep `[0-9]{12,}|scholarchain-course-|arn:`: 0 hits.

Findings (left stale by the repair):
1. **Minor, `EVALUATION.md:147`.** Still says no count is recomputed from `factory-evidence.tsv` or `factory-counts.json` "(not read for this subsection)", while 149 and 169 cite the JSON. Fix: the JSON was read only at those lines for comparison; nothing recomputed.
2. **Minor, `EVALUATION.md:934-935`.** Sources list `factory-counts.json (first 150 lines)`; the W2-DOC5 sources omit the JSON; the repair cites 168, 262, 265, 453-464. Add those ranges with W2-RB15 as reader.
3. **Advisory, `EVALUATION.md:927`.** "That is separate evidence from the review" is ambiguous; suggest "review evidence, separate from the author's scan"; review 21's 0-hit Grep may be added.

(The reviewer lists the phase-gate `sbt clean check` as pending; it passed at 08:36:12, `human-commands.md`.) Next: one repair of 147, 934-935 (927 optional); re-review of those lines only.
