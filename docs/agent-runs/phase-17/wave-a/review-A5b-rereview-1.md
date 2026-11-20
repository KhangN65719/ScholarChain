# Review A5b, re-review 1 (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

Verdict: APPROVE. F1, F2, F3, F4, L2 and L3 are closed; L1 is accepted as a deviation (P17-15).
- F2: `run.sh` heredoc 13029-13140. `STUB_BEARER` appears at 13033, 13091, 13125 and 13128; whole-word `TOKEN` occurs only inside the string fragment; `SCHOLARCHAIN_QUERY_TOKEN` is kept (13091, 13142).
- F4: `phase-00/human-gate.md:7` and `:10` match `phase-01/ratification.md:4` and the reflog. `commit.txt` equals the gate hash.
- sbt per-group totals: accepted as pending until the human's phase-gate `sbt clean check`. The last `sbt quick` ran after all three Scala edits and ended `[success]`; none of the edited suites is in `slowSuites`.
- Pending: H-3 (`bash -n tests/scripts/run.sh`, `hooks-guard.sh`, `run.sh`, `rehearse.py`), the execute bit, the orchestrator `git diff` check, the V1 re-preview, and H-4.

## Orchestrator check (pending item)
`git diff --stat`, 2026-11-19: 12 tracked files changed, 137 insertions and 38 deletions. `README.md` +14 lines (the new section only). The six K01 files changed as reported (10, 14, 16, 15, 8 and 8 lines). The four constitutional files changed as reviewed in A5a. Untracked: the phase 00 to 16 evidence files, `docs/agent-runs/phase-17/`, the six Wave B docs (not yet reviewed, B12) and `tests/scripts/hooks-guard.sh`.
