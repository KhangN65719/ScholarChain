# WO-A3d report (experiment-engineer): record of what reached the orchestrator

Recorded by the orchestrator, 2026-11-19. The agent said that its full course-field report is "above" in its own transcript. Twice, only its closing Stop-gate note reached the orchestrator, so the full report is NOT RECORDED here. The facts below were observed by the orchestrator or quoted from the agent's closing messages.

- Edit observed by the orchestrator (Read): `load-tests/query-load/rehearse.py:32` is now `STUB_BEARER = "-".join(("REHEARSAL", "TOKEN", "NOT", "A", "SECRET"))`. It was `TOKEN = "REHEARSAL-TOKEN-NOT-A-SECRET"`. The runtime value is unchanged. The name changed, so every later use must be renamed. The agent reports a leftover-name grep, and the orchestrator has not verified it. code-reviewer checks it in A5.
- sbt: none, as the work order requires. The Stop gate stays open and non-blocking for this file, because sbt does not cover Python.
- Proof of behaviour, by the human (H-3): `python3 -I load-tests/query-load/rehearse.py`, expected `pass=45 fail=0` (quoted from the agent).
