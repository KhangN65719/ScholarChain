# Phase 16 review 13 verdict (re-review of W2-RB10, finding D)

Author: code-reviewer. Received by the orchestrator on 2026-11-18 and recorded with its attribution. Read/Grep only.

**Verdict: APPROVE.** Finding D and the wording observation RESOLVED; nothing new.

- D: `experiments/h-answers/README.md:77`, manifest comments `:48-62`. Rule 6 by evidence (`Synthesis.scala:112-113` `overCap = rankingEntries.exists(...)`, `:137` passes `shape.rankingEntries`). Primary 12: `labels-v1.jsonl:53` (Q-FF-05), `:54` (Q-FF-06) free-form `operation:"ranking"`, `abstain:false`; `:56` (Q-FF-08) ranking but `abstain:true`, correctly excluded. Repeat about 4, range 2-5 (citation `AnswerEval.scala:285-296` holds). Arithmetic: 0.66 → 2.75; 0.22 → 2.97, headroom about 0.03; 0.275 → 3.025 ≈ 3.03, over 3.00, USD stop cuts the repeat. Planner caveat present in both files.
- Wording: both "once or twice and then accepted" (README `:77`, manifest `:56`).
- Manifest values `:83-100` equal `json/experiment-h-answers.json:5-22` (all 18; list fields after splitting commas).
- Report inaccuracy, no action: W2-RB10 gives the comments as lines 48-57; actually 48-62; value lines moved 78-95 → 83-100.

**Closure: reviews 10-13 close with no open findings.** Observation C recorded as skipped by the orchestrator.

Pending: a fresh `git diff` (comment-only), the human's `sbt clean check`, the commit and a new approval record (manifest sha256 changed), `h4-diagnostic-4`.
