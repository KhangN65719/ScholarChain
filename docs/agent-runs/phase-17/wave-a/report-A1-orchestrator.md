# WO-A1 record (orchestrator, executed by a fork of the orchestrator session), 2026-11-19

The fork's own summary was lost to the Stop gate twice and is NOT RECORDED. The orchestrator verified the output directly.

- `commit.txt` read for every phase from 00 to 16. Each holds one full 40-character hash, matching a phase commit in `git log --oneline`: 00 `08b95c3`, 01 `027c9b7`, 02 `32ebc6d`, 03 `9ee8b22`, 04 `6d89e3d`, 05 `befe9c4`, 06 `54fe82f`, 07 `8cde783`, 08 `6c39f83`, 09 `0a86da0`, 10 `19ee05f`, 11 `fa21c2c`, 12 `102e1b8`, 13 `f065a33`, 14 `4927595`, 15 `848fb64`, 16 `6072310`.
- Spot-read `phase-04/human-gate.md` and `phase-04/agent-report.md`. The human gate cites `ratification.md` for each line and discloses the Codex-authored repair 8. It states that the ratification names no hash, so the phase commit comes from `git log`. The agent report has the P17-11 label, all 14 fields, `OWNER: hadoop-engineer` alone on its line, no paths in the FILES fields, and an index table.
- Not verified by the orchestrator: the other 32 human-gate and agent-report files, `phase-00/**` and `phase-03/prompt.md`. These go to code-reviewer (A5), with the human's confirmation at H-4. Of the 18 commits in the ratification-versus-history list, the phase-01 choice (`027c9b7` rather than a later Phase 1 commit) is the one to confirm.
