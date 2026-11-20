# Re-review 2: README H-8 updates (repair B-H8d)

Author: code-reviewer. Recorded by the orchestrator on 2026-11-19, as received.
Inputs: the orchestrator's `git diff --stat -- README.md` (`README.md | 52`, 26 insertions, 26 deletions; 25 and 25 before B-H8d), and direct reads of README.md :74-89 and :390-401.

**Verdict: APPROVE.** N-1 and N-2 are resolved. There are no new findings.

- N-1, README.md:80: resolved. It now reads `OBSERVED (record), earlier runs (not H-8 replays):`, which fits items :81-85, all pre-H-8 runs (`ccbc1ec`, `5157e0e`, `2151c01`, and the pre-`5157e0e` hook runs).
- N-2, README.md:397 (D12): resolved. It now reads `validate and plan results PENDING (human; target is the submitted tag, itself PENDING)`; the unsupported `dc15299` target is removed.
- D12 row: still has 4 cells and its three links, and all three link targets exist. The citation `docs/agent-runs/phase-12/decisions.md:44` still supports "security scan not run".

Limits stated by the reviewer: there was no per-round `git diff` for B-H8d, so D12 was checked for structure and link targets, not character by character against the pre-repair text. The 25 to 26 `--stat` change is taken from the orchestrator's evidence.

Review chain: `review-B-H8-verdict.md` (REQUEST CHANGES), then `review-B-H8-rereview-1.md` (REQUEST CHANGES, N-1 and N-2), then this file (APPROVE).
