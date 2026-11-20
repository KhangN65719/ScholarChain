# Repair 03: A5b findings F3 (documentation-engineer), F4 and L2 (orchestrator), 2026-11-19

- **F3, documentation-engineer** (report as received, condensed): only `README.md:10` changed. "is being rewritten ... (planned, not yet done ...) ... in the current files" now reads "was rewritten to build every fake value at runtime from joined fragments (`tests/adversarial/P14LoaderRig.scala:22-32`), so that no key-shaped literal remains in that file; the runtime strings the redaction tests check are unchanged." The agent compared the fragments with the old literals by reading; no test was run.
- **F4, orchestrator:** `phase-00/human-gate.md` now states that Phase 1 accepted `2313f50` as its baseline (`phase-01/ratification.md:4`) and that `08b95c3` was committed after `9ee8b22`. It explains why the Phase 1 records are cited besides the commit messages, credits the hash choice to plan D2 under P17-1, and asks the human to confirm `08b95c3` or replace it with `2313f50` at H-4.
- **L2, orchestrator:** `phase-15/human-gate.md:5` now also cites P16-14 (`phase-16/decisions.md:19`).
- **L1:** accepted deviation, recorded in `../decisions.md` P17-15.
