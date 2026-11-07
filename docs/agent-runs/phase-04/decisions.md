# Phase 4 architect decisions

Recorded by the orchestrator 2026-11-06, as given by the human architect after the [final review](final-review.md). These are routing and design decisions, not the ratification decision, which the human owns separately.

## 1. The `.fail` tripwire — reviewer's refinement accepted

Each must-fix defect is paired with a `.fail` twin asserting the correct behavior, dispatched in the same work order as its repair. **No `.fail` twins for F2 and F6**, because their remedy was an unmade design decision — now made, below.

### Orchestrator note on how this is implemented, and why

The thirteen pinned cases are characterization tests: they assert the **current, defective** behavior. So repairing F1, F3, F4 and F7 necessarily turns their pins red, because the behavior they pin will have changed. The pins and the repair must therefore land in the same cycle or the check fails for a reason that is not a defect.

That makes the implementation of this decision concrete:

- For the **five must-fix findings being repaired now**, the existing pin is **converted** to a plain assertion of the corrected behavior, using the corrected assertion the reviewer specified for each. A `.fail` twin would be wrong here: with the fix landing in the same cycle, the twin's body would pass, so the `.fail` case itself would fail, producing a guaranteed red run carrying no information.
- The **`.fail` form is used for the carried-forward defects that remain open** — F5, F8's skip-region behavior, F12 and its digest collision, F13b–e, R3, R5, R6. There the twin earns its keep exactly as the reviewer described: green while the defect is open, red the instant it is fixed, so no fix can land silently.

This preserves the decision's intent — no must-fix defect is graded by prose, and no open defect can be silently fixed — while avoiding a deliberately red run. Flagged to the architect; overrule if a three-phase sequence (twin green, repair turns it red, twin flipped) is wanted instead.

## 2. F2 and F6 remedies — design decisions now settled

**F2.** Retain the original characters in all stored records. Transliterate or fold **only** into a separate matching/blocking key. So the stored `WorkRecord` and `AuthorMention` keep `"Bjørn Ødegård"` intact, and only the derived blocking key is folded.

This settles what the reviewer identified as an unmade policy decision and removes the reason `.fail` pairing was withheld for F2. Owner for implementation: `hadoop-engineer`. Condition from the review stands: **before Phase 5 consumes blocking keys.**

**F6.** When a block is truncated, select **round-robin across sources** so no source is starved. This replaces the current `(source.wire, memberId)` ordering at `Blocking.scala:98-99`, under which source is the first sort component and a 60/60 block at bound 50 retains 50 DBLP and zero OpenAlex.

Owner: `hadoop-engineer`. Same Phase 5 condition. The reviewer confirmed the consequence `test-engineer` had left unasserted: a block retaining one source yields **zero** cross-source candidates, since `MatchCandidates.candidates:231-242` enumerates within-block pairs and `source.distinct` is only a feature, never a filter.

**Tests for both are to be added once implemented.** Neither is in the current repair dispatch — both are Phase 5 conditions, not Phase 4 ratification blockers.

## 3. The minicluster experiment — approved

`build-engineer` removes `build.sbt:152` (`hadoop-client-minicluster % Test`); the human runs `sbt check`; the entry is removed permanently if `MapReduceEndToEndSuite` still passes, and restored if it fails.

This settles by experiment what the reviewer could not determine by reading: whether `LocalJobRunner` resolves from `hadoop-client-runtime` or from the minicluster artifact.

**Orchestrator sequencing note:** this experiment is held until after the repair cycle's check is green. Running it concurrently with the five repairs would confound it — a failing `MapReduceEndToEndSuite` could then mean either "the minicluster was load-bearing" or "a repair broke a job," and the experiment's whole value is that it has exactly one variable.

## 4. R6 recorded as a Phase 5 blocker

The real DBLP dump uses named HTML entities (`&auml;`, `&eacute;`, …) extensively. `XmlFragment.factory():27-30` sets `disallow-doctype-decl` and `Strict:52-53` throws on any error, so each such reference becomes a `MALFORMED_XML` reject. On a full snapshot this plausibly rejects a large fraction of records, making the accepted/rejected ratio meaningless and starving the graph; with R5 it also concentrates that reject traffic on one reduce partition.

**The security posture is correct and is not to be changed.** The remedy is to pre-resolve DBLP's fixed, published entity set before parsing, with a counter — not to enable the DTD.

Owner: `hadoop-engineer`, with `dataset-engineer` supplying the entity list. **Not a Phase 4 gate blocker. Phase 5 blocker, and a condition on the first full-snapshot or EMR run.** Recorded here because before the final review it existed nowhere as a defect: `test-engineer` had classified it as correct-and-deliberate, flagging only reject-output sizing.

## 5. Repair dispatch scope

The five must-fix items, with F3 and F7 in one change and F3's bound landing first, since fixing F7 alone restores the full `n(n-1)` fan-out for exactly the same-name teams that currently emit nothing.

Nothing committed.
