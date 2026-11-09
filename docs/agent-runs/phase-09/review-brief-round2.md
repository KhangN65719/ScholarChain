# Review brief, round 2 (code-reviewer, read-only, Opus): re-review of the repair only

You have Read, Grep and Glob only. Do not edit anything. This is a fresh reviewer. Cover ONLY the items that changed plus anything the repair newly touched. Return `APPROVE` or `REQUEST CHANGES` with every finding batched (id, severity, file:line, evidence, rule broken). This is the last allowed round (limit 2): list any remaining blocker or major explicitly.

## Read first
`docs/agent-runs/phase-09/review.md` (round 1 findings B1, M1, M2, m1 to m4), `work-order-04-rag-repair1.md`, `work-order-05-test-repair1.md`, `report-05-test-repair1.md`, `decisions.md` (G3: the rag repair report did not arrive, so verify the rag repair from source; P11: storage side of m1 deferred), `CLAUDE.md`, `docs/agents.md`, and F1 in `docs/agent-runs/phase-08/decisions.md`.

## Re-verify, from source (not from reports)
- B1: in `modules/rag/src/main/scala/scholarchain/rag/Verification.scala`, `Claims.scala`, `Workflow.scala` and `Synthesis.scala`, confirm that no model-authored free text (semantic claim text, abstention text, analysis paragraph, per-claim or top-level uncertainty) reaches the final answer, claims or audit as Verified content; confirm the replacements are system-written; try to construct a bypass (other claim kinds, caveats, Unicode/digit forms, spelled numbers, fields I did not list).
- M1: verified claims render from the asserted value; a number matching a different field cannot verify.
- M2: every data string (names, titles, gaps derived from names) is under an untrusted structure; prompt rule covers all string fields; names and titles are scanned and flagged.
- m1 (rag side): the allowlist wrapper compares query text and parameter names, not only the name. The storage fake is deferred (P11): confirm that is acceptable.
- m2: literal hashes are pinned in tests; versions bumped (synthesis prompt 1.1.0, schema `synthesis-output/2`); confirm the pins in `tests/adversarial/Phase9RepairSuite.scala` match the sources' computed hashes by reading how they are computed.
- m3: `EvidenceCaps.maxBytes` vs `maxInputTokens` can no longer disagree; budget failure has its own reason `input-over-budget`.
- m4: per-kind assertion schemas with `additionalProperties: false`.
- Tests: no test weakened or deleted. The single changed expectation is `Phase9ModelSuite` "a model input over the token budget is refused before any call" (`synthesis-failed` to `input-over-budget`, strengthened). Check `git diff --stat` and `git diff` on tracked files yourself is not possible (no Bash); orchestrator evidence: tracked changes are still only the 3 storage files (+165/-2) from round 1; all Phase 9 test files are untracked/new, so test weakening can only be judged by reading `Phase9ModelSuite.scala` and `Phase9GateSuite.scala` against `report-03-test.md`.
- Regression: any change in rag behavior that could break gate items 1, 2, 7 to 11 from round 1 (re-check quickly the ones the repair touched: audit contents, abstention reasons, retries).

Known and not findings: P1 to P11, G3, the round-1 non-blocking notes unless the repair worsened them.
