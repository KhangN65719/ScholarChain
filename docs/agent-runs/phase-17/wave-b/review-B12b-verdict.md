# Review B12b verdict on README.md (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator; findings kept)

Verdict: REQUEST CHANGES. 3 major and 11 minor findings, all for documentation-engineer.

## Major
1. `:188` says P16-34 is "applied by B8" and NOT RECORDED. It should state:
   - `services-smoke.tfvars:26` = 60000, the A2-only overlay (lines 1 and 8-9)
   - SMOKE1 ran on the A2 overlay (`smoke-tn.md:11`)
   - A3 left it out (`deploy-tn-s3-a3.md:32`, UNVERIFIED; `SESSION2.md:112`)
   - the default 0 and `services-session.tfvars` keep 10 s (`variables.tf:491-498`)
   - the demo-session setting is PENDING (infra-engineer, WO-B8)
2. `:320-328`: the exercise starts at `54fe82f`, but `sbt quick` was added later (`phase-06/human-gate.md:10`), the order says `sbt quick` (`work-order-01-domain.md:14`), and the archived run used it (`report-01-domain.md:3`, 5 groups at 66 s). Start at `cc2385e`, or use `sbt check`; delete "NOT VERIFIED".
3. `:397`: the link to `docs/demo-script.md` points at a file that does not exist yet; use backticks until B4.

## Minor
4. `:308`: 18 examples over 8 categories, not one per category.
5. `:387` D07 gold set is `docs/agent-runs/phase-05/gold/gold-v3.jsonl` (`EVALUATION.md:912`). `:390` D10 prompts are in `modules/rag/.../Model.scala:64-88` (`Planner` 1.0.0, `Synthesis` 1.2.0).
6. `:263`: the ER verification command should cite `EVALUATION.md:912` (inputs outside the repository).
7. `:267`: infra verification should add `bash scripts/plan.sh --dry-run ...`. `:126` and `:392`: the security scan was "not run" (`phase-12/decisions.md:44`, `report-04-infra-engineer-terraform.md:51`), not "NOT RECORDED".
8. `:83`: the 114/0 run was on the Wave A2 files before `5157e0e`. `:82`: the 1949/0 run was on tree `2151c01`.
9. `:315`: `repairs.md` "where present".
10. `:105`: `local[2]` is set in Spark `Config.scala:77, 194`, not `build.sbt:71-94`.
11. `:266`: cite `.claude/hooks/policy.py:146-147` for the incident-responder sbt exclusion.
12. `:305`: say `/agents`, `/memory`, `/mcp` and the `claude` start command are standard usage, not verified here.
13. No "instructor-approved deviations" line: add the public-repository override (`prompt.md:7-8`).
14. Optional: neutral wording for "a grader" (`:282`, `:303`), and note that the log path in the pasted order is on the original author's machine.

## Verified
- Catalog: 18 rows; fences, models and owned paths match; 14 example instructions match their archives; 4 honestly labelled.
- The 11 steps, the version record (values match their sources) and the prerequisites table.
- Facts: 1949/0, 114/0, validator 4/4, `ccbc1ec` 1314 s, Phase 7 1128 s, Billing 8.91, 8.93 and 10.39, Spark `fc4bbd1a`, `build.sbt` refs, `8cde783`.
- The Hadoop and Spark text matches P17-20 and P17-21 (no `hadoop jar` claim, placeholders only).
- Autograder-notes facts are accurate. The reviewer notes for the human only that the K03 phrase leaves out two other comment lines (`persistent.example.tfvars:3`, `session-down.tfvars:10`); it stays true that only comments matched.
- Privacy: only the `000000000000` placeholder; identity exactly as supplied.

## Orchestrator checks (pending items, 2026-11-19)
- `git diff -U0 HEAD -- README.md`: the hunks touch old lines 2, 4 and 19 onward. The "Autograder notes" lines (old 5-17) are unchanged, so the section is verbatim. Closed.
- `git show --stat cc2385e` ("Phase 6 follow-up: Add sbt quick ..."): it changes `build.sbt` (+105/-~20), `.claude/settings.json`, `policy.py`, `probe_policy.py`, `CLAUDE.md`, `docs/agents.md`, 10 role files and the workflow-speedup records. `git log --oneline 54fe82f..8cde783` lists only `cc2385e` and `8cde783`. Decision for finding 2: start the exercise at `cc2385e` (P17-23).
