# Repair 01 for review B12b (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE for findings 1 to 14; README.md only; "Autograder notes" untouched; no commands run. Each cited source was read before editing.
1. Slow-deadline facts with citations; the demo setting is PENDING (B8). Disclosed: the claim "`services-session.tfvars` does not raise it" rests on the verdict and `variables.tf`; the agent did not open that file.
2. The exercise starts at `cc2385e` (P17-23; `phase-06/human-gate.md:10`); the hedge and NOT VERIFIED are removed; `sbt quick` and the 66 s comparison are kept.
3. The D17 demo-script reference is now in backticks.
4. 18 examples over 8 categories.
5. D07 links `gold-v3.jsonl`; D10 cites `Model.scala:64-88`, and `prompts/` holds only `.gitkeep`.
6. The ER command cites `EVALUATION.md:912`; its inputs are outside the repository.
7. Infra adds `plan.sh --dry-run`; the security scan is stated as not run (`phase-12/decisions.md:44`, `report-04:51`).
8. 1949/0 on `2151c01`; 114/0 on the uncommitted Wave A2 files.
9. `repairs.md` "where present".
10. `local[2]` cites `Config.scala:77, 194`.
11. Incident-responder cites `policy.py:146-147`.
12. Step 5 states the slash commands are not verified here.
13. New "Instructor-approved deviations" section (public repository, `prompt.md:7-8`).
14. Neutral wording ("how to verify them", "A reader without AWS access"); the log path in the pasted order is noted as being on the original author's machine.
