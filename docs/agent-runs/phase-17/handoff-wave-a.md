# Handoff to the fresh orchestrator session (Phase 17, after the Wave A ratification)

Written by the orchestrator on 2026-11-19 before the restart. Read `plan.md`, `decisions.md` (P17-1 to P17-15), `human-commands.md` ("Results recorded") and `wave-a/` first.

## State
- Wave A is ratified and committed at `2151c01`. Review A5a (factory) passed APPROVE after two repair rounds; A5b passed APPROVE after one.
- Held and uncommitted: `docs/agent-runs/phase-17/**`, plus the Wave B docs `docs/architecture.md`, `data-lineage.md`, `api.md`, `threat-model.md`, `service-limits.md` and `runbook.md`. Their reports are in `wave-a/report-B2-*` and `report-B3-*`, and they are not yet reviewed (B12).

## Update after Wave A2 (2026-11-19)
- Wave A2 (F07 and R03, P17-16) is ratified and committed at `5157e0e`. D1 is decided as option (c), within 2.30 USD (P17-17). F4 is kept as is.
- Next: probes P1 and P1b (and P2 to P4 if not yet run); Claude in chat re-runs V1 on a fresh clone of `5157e0e`; push; V2 and V3 (the venv at `~/CS441_Autograder_For_Students/.venv` first on `PATH`); email the three CSVs.
- Then Wave B: B12 review of the B2 and B3 docs (`threat-model.md:61` was already updated in repair 06); B1 README; B4 demo script (option c labels); B8 P16-34 (owner resolved at dispatch); the live session Friday or Saturday after B4.

## Next, in order (as written before Wave A2)
1. Probes P1 to P4 in the fresh session (`human-commands.md`). Record each result. P4-D stays open.
2. The human's full harness `bash tests/scripts/run.sh`, plus the H-2 pre-check outputs, if not yet relayed.
3. Claude in chat runs the V1 re-preview on a fresh public clone of `2151c01`. Expected: P01, K01, F05 and G02 pass. R03 is uncertain (prose header against a static scan). K02 FAIL, F07 FAIL and E01 to E03 FAIL are explained in the README. W01 and W02 show "later" while phase 16 is the highest folder.
4. Push, then H-5: the human runs V1, V2 and V3 and emails the three CSVs this evening (P17-8).
5. D1, after the human's Billing reading.
6. Wave B: B12 review of the B2 and B3 docs; then B1 (README), B4 (demo script, after D1), B5 (final report; `reports/**` is now owned by documentation-engineer), B7, B8 (if D1 is a or c), B9, B11.

## Process notes from this session
- The Stop gate blocks each agent's first stop while any Scala source is newer than the marker, and the agent's final message then holds only the gate note. Every work order now says: "Make your FINAL message the full course-field report." When a report is lost anyway, verify by reading the files and record that the report is NOT RECORDED.
- Keep one sbt and one Scala writer at a time (P17-12).
- Per-group sbt totals from the Wave A `sbt quick` runs are NOT RECORDED; the human's phase-gate `sbt clean check` supplies them.
