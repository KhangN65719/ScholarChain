# Phase 4 toolchain and authorship provenance

Recorded by the orchestrator 2026-11-06.

## Why this record exists

The ownership matrix in `docs/agents.md` assumes a named Claude Code subagent authors each artifact class. Part of the Phase 4 repair work was authored with a different tool. That is a provenance fact about the repository and belongs in the phase record, independent of whether the work is good.

## Authorization

Professor Grechanik's email of **6 November 2026**: "feel free to use any LLM tool that best fits your preferences or budget."

The human architect used OpenAI Codex while this Claude account was rate-limited (5-hour limit, reset 21:50 America/Chicago). The substitution was the architect's decision under that authorization, not an agent's.

## Authorship by work unit

| Work unit | Author | Time |
|---|---|---|
| Phase 4 implementation (28 main sources, 8 module test suites) | `hadoop-engineer`, Claude Opus subagent | 01:12–02:38 |
| Repairs 1–4 | `hadoop-engineer`, Claude Opus subagent | 02:10–11:57 |
| Build changes: `hadoop-client-runtime`, `hadoop-client-minicluster`, SLF4J binding, three test source roots, `hadoop % "test->compile"` | `build-engineer`, Claude Opus subagent | 09:51–10:27 |
| Repair 5, end-to-end MapReduce suite, nine main sources | `hadoop-engineer`, Claude Opus subagent | 12:00–12:03 |
| Repair 6, reduce-counter correction, `simplelogger.properties` | `hadoop-engineer`, Claude Opus subagent | 12:14 |
| Adversarial suites, 65 cases, thirteen findings | `test-engineer`, Claude Opus subagent | 12:33–12:42 |
| Adversarial corrections after run 9 | `test-engineer`, Claude Opus subagent | 16:54 |
| Reviews: initial, re-review, final | `code-reviewer`, Claude Opus subagent | 02:33, 10:13, 17:20 |
| **Repair 7, partial** — F3+F7, F4+F13a, R1 | `hadoop-engineer` and `test-engineer`, Claude Opus subagents | 17:34–17:44 |
| **Repair 8, completion** — F1, F9, F10, F11, R2, F8 comment, pin conversions, nine `.fail` twins, `AdversarialCounterSuite` | **OpenAI Codex**, driven by the human architect | 19:24–19:26 |
| All ten `sbt clean check` runs | Human operator, with the `!` prefix | 02:07–16:58 |

Attribution between repairs 7 and 8 is established by file modification time: Claude's edits are 17:34–17:44 and Codex's are 19:24–19:26. The boundary is unambiguous because the two windows do not overlap and no file was touched between 17:44 and 19:24.

## What this means for the review trail

The Codex-authored work has **not** been through the project's independent review. The [final review](final-review.md) predates it (17:20) and covers only up to repair 6. Repairs 7 and 8 both postdate every verdict in the record.

The orchestrator verified repair 8 by reading the current source — scope, boundaries and each must-fix item — and that verification is recorded in [repairs.md](repairs.md). **Orchestrator verification is not independent review.** The five must-fix items still require a `code-reviewer` verdict before Phase 4 can be ratified.

## Unreviewed parallel control plane

Codex also created a second control plane outside the ownership matrix. **It is unreviewed, is not to be used or committed, and whether to keep it is a later `factory-engineer` decision.** Contents:

### `.codex/` — 26 files

- `config.toml`, `hooks.json`
- `agents/` — 18 TOML agent definitions mirroring the ownership matrix: `build-engineer`, `code-reviewer`, `dataset-engineer`, `dependency-updater`, `deploy-engineer`, `documentation-engineer`, `domain-engineer`, `entity-resolution-engineer`, `experiment-engineer`, `factory-engineer`, `hadoop-engineer`, `incident-responder`, `infra-engineer`, `rag-engineer`, `service-engineer`, `spark-engineer`, `storage-engineer`, `test-engineer`
- `hooks/` — `format-scala.sh`, `guard-dangerous.sh`, `policy.py`, `probe-policy.sh`, `probe_policy.py`, `validate-agent-files.sh`, `validate.py`, `verify-tests-ran.sh`

### `.agents/skills/` — 5 files

`source-command-deploy`, `source-command-experiment`, `source-command-incident`, `source-command-rollback`, `source-command-run-pipeline`, each a `SKILL.md`, mirroring the five `.claude/commands/` entries.

### `AGENTS.md` — repository root

### The risk the orchestrator records against this

`.codex/hooks/` holds what appear to be **copies of the constitutional enforcement scripts** — `guard-dangerous.sh`, `policy.py`, `verify-tests-ran.sh`, `validate-agent-files.sh`. Those files are `factory-engineer`'s exclusive outputs under `docs/agents.md`, and the originals live in `.claude/hooks/`. Two consequences:

1. **The enforcement rules now exist in two places and can drift.** A ratified change to `.claude/hooks/policy.py` would leave `.codex/hooks/policy.py` stale, so the same repository would enforce two different policies depending on which tool was driving. Phase 1 closed the native-hook-execution gate on specific measured evidence; a second, unmeasured copy weakens that.
2. **`.codex/**`, `.agents/**` and `AGENTS.md` are unowned paths.** `docs/agents.md` is explicit: "Any unresolved collision or unowned artifact blocks the request until the human ratifies an assignment." Their classification is unresolved and belongs to a `factory-engineer` work order with independent review and human ratification, per the constitutional-change gate — not to this phase.

The orchestrator has not read the contents of these files beyond listing them, has not used them, and has not committed them. The repository's `.gitignore` does not currently exclude them, so they would be picked up by an unscoped `git add`.
