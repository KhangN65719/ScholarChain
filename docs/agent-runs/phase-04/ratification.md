# Phase 04 human acceptance

Status: ACCEPTED by the human architect.

- Date: November 7, 2026
- Baseline: `74543bb` (accepted Phase 03).
- Evidence: [prompt.md](prompt.md), [hadoop-work-order.md](hadoop-work-order.md), [owner-report.md](owner-report.md), [test-report.md](test-report.md), [review.md](review.md), [re-review.md](re-review.md), [final-review.md](final-review.md), [decisions.md](decisions.md), [resumption-work-order.md](resumption-work-order.md), [must-fix-re-review.md](must-fix-re-review.md), [repairs.md](repairs.md), [validation.md](validation.md), [versions.md](versions.md).

## Gate results

| Gate item | Result |
|---|---|
| `sbt clean check` (human-run, run 11) | Passed: root project 81/81 (`tests/adversarial`), hadoop module 148/148 |
| At least two real MapReduce jobs | Closed: `LocalJobRunner` execution with real submission, the project's input format, shuffle, `FileOutputCommitter` v2, job 2 reading job 1's committed output (`MapReduceEndToEndSuite`) |
| DBLP record boundaries | Passing |
| Pair generation for 1, 2, 3 and larger author lists | Passing |
| Counters and reject outputs visible | Passing on runtime evidence; double counting (R1, R2) repaired |
| Repeated runs equivalent | Passing |
| Provenance in sample results | Passing |
| Adversarial cases by `test-engineer` | Delivered under `tests/adversarial`; must-fix defects repaired with executed assertions |
| `code-reviewer` | REQUEST CHANGES (review, re-review, final review), then APPROVE in [must-fix-re-review.md](must-fix-re-review.md): Phase 4 ratifiable |

## Authorship disclosure

Repair 8 was authored with Codex while Claude Code usage was exhausted, under Professor Grechanik's written permission of November 6, 2026: "Please feel free to use any LLM tool that best fits your preferences or budget." It was verified by the orchestrator and approved by `code-reviewer`. The reviewer noted that the one-writer rule held nominally rather than structurally for that repair and that this "should not become routine."

## Decisions recorded

- `.fail` twins paired with must-fix repairs, per [decisions.md](decisions.md).
- F2: retain original characters in stored records; fold or transliterate only into a separate matching key.
- F6: truncate common-name blocks round-robin across sources.

## Accepted as open (not blocking)

1. N1: `XmlFragment.text` recursion is unbounded and `parse` catches `Exception`, not `Error`; deeply nested input can raise `StackOverflowError`. Low reachability.
2. R6: the full DBLP dump uses named HTML entities that the no-DTD policy rejects. Phase 5 blocker; remedy is pre-resolving DBLP's published entity set.
3. The `hadoop-client-minicluster` removal experiment is held, one variable at a time.
4. `.codex/`, `.agents/` and `AGENTS.md` are an unreviewed parallel control plane, deliberately not committed. A factory-engineer decision is pending.
5. Multi-reducer fan-out is not exercised: `LocalJobRunner` runs a single reduce task.

The human architect stated:

> I accept Phase 4.
