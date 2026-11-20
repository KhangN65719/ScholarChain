# Review F1: Phase 17 final full review

Author: code-reviewer (Opus). Recorded by the orchestrator on 2026-11-20, verbatim as received (not condensed), except three fake key values marked [REDACTED, P17-36] (findings B5, check 5), replaced on 2026-11-20 by the orchestrator under the human's decision P17-36.
Orchestrator note: the dispatch said 76 files under `phase-17/` and 38 in `wave-b/`; those were orchestrator miscounts. `git status` lists 37 `wave-b/` files; the 76 included `final-gate/work-orders.md`, written just before dispatch.

---

**Status:** COMPLETE, verdict delivered
**Owner:** code-reviewer (Opus)
**Work order:** WO-F1, `docs/agent-runs/phase-17/final-gate/work-orders.md`
**Files created or modified:** none. **Commands run:** none (I have Read, Grep and Glob only).

# Verdict: REQUEST CHANGES

The code, test and constitutional changes are sound. The guard is not weakened, the test rewrites keep their meaning, the hook form and `.mcp.json` match the ratified text, and there is no account id, ARN or real credential. The documents, however, contain stale statements that contradict the recorded H-8 and probe results. The final report's manual-edit class is also missing two disclosed edits. Once the held archive is committed, it would add one key-shaped literal to the tree and one inaccurate "kept as received" claim.

## Blocking findings (one repair round)

**B1. README deliverable index and final-report pointers are stale.** Owner: documentation-engineer.
- `README.md:218`: "Final report ... PENDING (B5; not yet written)". It was committed in `dc15299` (P17-31). Replace it with a link to `reports/final-report.md`.
- `README.md:219`: "Demonstration script ... PENDING (B4)". It was committed in `dc15299`. Link `docs/demo-script.md`.
- `README.md:402` (D17): drop "`docs/demo-script.md` PENDING (documentation-engineer, B4)", add a link to it, and change the status from "partial" to "present".
- `README.md:403` (D18): change it to the `reports/final-report.md` link with status "present". The final sections that still say PENDING can stay marked as such.
- `README.md:388` (D03): "each `phase-NN/` has `human-gate.md`, `commit.txt` and `agent-report.md`". `phase-17/` has no `agent-report.md` and is still untracked. State that `phase-17/` is committed in the close commit (WO-F6), and either add a `phase-17/agent-report.md` index or say it is absent.
- `README.md:397` (D12): the facts are still correct, but update the owner after WO-F3. Terraform fmt and validate belong to infra-engineer under WO-F3; the plan checksum stays with the human. Cite the earlier observation in `docs/agent-runs/phase-13/checks-1.md` (2026-11-11), as `README.md:112` already does.
- `README.md:399` (D14): the release manifest is now WO-F4, not "work order B9". Update it after F4.
- `README.md:405` (D20): under P17-7 and P17-26, the "after cleanup" inventory falls after the deadline (mid-December). Say so, and name what exists at submission: at most the read-only inventory from demo item 22, if it is run.
- `README.md:189`: "The setting for the Phase 17 demo session is PENDING (infra-engineer, work order B8)". This was resolved by P17-25 (reuse `services-smoke.tfvars`) and P17-26 (primary demo has no live session; optional path (i) uses no Haiku, so the 10 s default applies, per `report-B8b`). `reports/final-report.md:324` already says this.

**B2. The final report contradicts the recorded probes and the H-8 results (check 9).** Owner: documentation-engineer.
- `reports/final-report.md:102`: "probes P1 to P4 were not relayed". `human-commands.md:90-96` records P1 PASS, P1b PASS, P2 PASS, P3 PARTIAL and P4 PASS.
- `reports/final-report.md:239`: "The exercise is NOT REPLAYED until Phase 17 H-8; replay results are PENDING". `human-commands.md:102-111` records the H-8 part 2 verdict as PARTIAL: requirements and gate reproduced, edge-id contract not. README:319-332 already says this.
- `reports/final-report.md:283` and `:319` say "every replay result is PENDING (Phase 17 H-8)". Restate them: H-8 part 1 (`validate-agent-files.sh` 4/4; `sbt check` on `dc15299`, 1370 s, groups 49/389/331/473/501/170/260/99/1427) and H-8 part 2 (PARTIAL) are recorded. The rest stays NOT REPLAYED.
- `reports/final-report.md:184` says "NOT REPLAYED until H-8". `:330` says "`docs/demo-script.md`, which is PENDING (B4)", which is stale.

**B3. The "manually edited" class is incomplete (check 7).** Owner: documentation-engineer.
- `reports/final-report.md:69` and the table at `:77-88` list M1 to M8, M10 and M11. Two disclosed class-3 edits are missing:
  - **P17-30:** the `README.md:9` heading rename to "Known check findings", plus the cross-references in `reports/final-report.md:132` and `docs/demo-script.md`.
  - **P17-31 item 1:** the rewording of FILES CREATED and FILES MODIFIED (and COMMANDS RUN in phase-11) in the 16 `phase-01` to `phase-16` `agent-report.md` files.
- Add them as M12 and M13 and update the sentence at `:69`.
- `:92`: the list of Phase 17 decisions by source stops at P17-29. Add P17-30 (human), P17-31 (orchestrator) and P17-32 (human).

**B4. The demo script contradicts the recorded probes and H-8.** Owner: documentation-engineer.
- `docs/demo-script.md:24` and `:120` say the item 4 refusal and the item 5 Stop gate were "NOT RECORDED after the last restart". P1 is PASS and P3 is PARTIAL (`human-commands.md:91,94`).
- `:324` says "NOT REPLAYED until the human archives it at H-8". H-8 part 2 is recorded as PARTIAL.
- `:19`, `:382` and `:414` treat `reports/final-report.md` (and `docs/demo-script.md`) as uncommitted or PENDING. Both were committed in `dc15299`.

**B5. Committing the held archive would add a K01-shaped literal.** Owner: orchestrator; needs a human decision.
- `docs/agent-runs/phase-17/wave-a/report-A3a-rag-engineer.md:7` contains a quoted fake AKIA-prefixed key id [REDACTED, P17-36].
- A Grep for `AKIA[0-9A-Z]{16}` over the whole tree finds only this hit. It is a fake value, but it reintroduces the pattern Wave A removed, and the archive rule says to keep reports as received.
- The human decides before the close commit: either redact it with a dated, attributed note in the archive, or accept a possible K01 regression and record that in `decisions.md`.

**B6. "As received" is inaccurate for most Phase 17 archives.** Owner: orchestrator, plus documentation-engineer for the README; needs a human decision.
- About 58 files under `docs/agent-runs/phase-17/wave-a/` and `wave-b/` have headers reading "as received ... (condensed by the orchestrator)", for example `wave-a/report-A3c-test-engineer.md:1`.
- `docs/agents.md` (boundary rules) says "Preserve each report as received", and `README.md:16` tells readers the archive is "kept as received".
- `wave-b/review-B-H8-verdict.md:5` also records that the reviewer was given an abridged diff.
- Fix: record a human acceptance of this deviation in `decisions.md`, as P17-15 did for L1, and qualify `README.md:16` for Phase 17.

## Non-blocking findings
1. `README.md:82`: a period is missing after "1427". (documentation-engineer)
2. `docs/runbook.md:31,181`, `docs/service-limits.md:176`, `docs/threat-model.md:134` and `modules/hadoop/JOB-CONTRACTS.md:213,259` say "NOT REPLAYED until H-8". H-8 has happened and did not cover these items. Reword to plain NOT REPLAYED. JOB-CONTRACTS is owned by hadoop-engineer; the others by documentation-engineer.
3. `reports/final-report.md:116` versus `README.md:97`: the README now cites the `dc15299` `sbt check` as indirect evidence for `PipelineDriverOutputsSuite`, but the report still says "UNVERIFIED until the human replays". Align them. `:180` could also cite the H-8 part 1 run (1370 s). (documentation-engineer)
4. `docs/service-limits.md:95` says "Phase 15 session 2 billed about 2.7 USD". The final report (`:247`) correctly calls this the orchestrator's reading, not a measured cost. Align. (documentation-engineer)
5. `reports/final-report.md:132` cites "Known check findings, V01 and A01 item", but the README has a separate A01 item (`README.md:21`). (documentation-engineer)
6. `reports/final-report.md:241`: the final review and test run are now WO-F1 and WO-F2, not B12 and B11. (documentation-engineer)
7. `tests/scripts/hooks-guard.sh:131-170`: the R03 cases run as `test-engineer`. Most of those commands would also hit the final catch-all refusal at `policy.py:158`, so a pass does not prove the named category rule fired. Consider also running each case as `infra-engineer`, the most permissive role, or asserting the specific message. Coverage is otherwise complete: 33 cases across all 8 categories, and the 114 total matches my count of the file. (test-engineer)
8. `docs/api.md:185` and `docs/service-limits.md:120` (P17-24): the ";" separator differs from the "/" used in neighbouring rows. The meaning is unchanged.
9. `docs/agent-runs/phase-00/human-gate.md:3,10` still say "to be confirmed at H-4", although `human-commands.md:47` records the Phase 00 confirmation. (orchestrator, at close)

## Verified (evidence)
- **Guard (check 4):** I read `.claude/hooks/guard-dangerous.sh` in full. Before the header was added, the only executable lines were the shebang, the `python3` check and the `exec`. Now the array and `--list-categories` exit only when exactly that one argument is passed. The hook commands in `settings.json:123,135,147,158` pass no arguments, so blocking still goes through `exec python3 -B .../policy.py guard` (line 109). The header's rule citations match `policy.py`: `DIRECT_DANGER` at 23-29, the `aws` branch at 102-110, `:152`, `:158`, `READ_GIT` at 55 and the branch check at 139. `policy.py` is unchanged. The `settings.json` deny-list claims in header lines 6-11 match `settings.json:40-63`.
- **Constitution:** `.mcp.json:7` reads `${SCHOLARCHAIN_GITHUB_AUTH_HEADER}`. `CLAUDE.md:37` and `docs/threat-model.md:61` agree. The old variable name survives only in Phase 17 archives. The four hook commands use the ratified form, with no matcher or timeout change. The `reports/**` text is identical in `docs/agents.md:24`, `validate.py:40` and `documentation-engineer.md:13`.
- **Test rewrites (check 5):**
  - `P14LoaderRig.scala:23-32` builds the same fake P14 AKIA-prefixed key id [REDACTED, P17-36], as the A3c report states.
  - `AwsIngestMainSuite.scala:727` builds the fake AKIA-prefixed key id [REDACTED, P17-36], which equals the old literal quoted in `report-A3a`. Its assertions at 741-743 are unchanged in meaning.
  - `AwsNeptuneQuerySuite.scala:362-386` is a rename only.
  - `Phase10RepairSuite.scala:459-467` keeps the same no-leak assertions.
  - `run.sh` (13033, 13091, 13125, 13128) and `rehearse.py` (32, 70, 168-173) renamed every use.
  - `EVALUATION.md:949`: 9+5+12+10+6 = 42 and 9+5+12+10+8 = 44, so the numbers are unchanged.
- **Evidence files (check 6):** all 17 `commit.txt` hashes equal the "Accepted commit" line of the matching `human-gate.md`. `phase-02` matches `ratification.md:4-5`, `phase-03` `:6`, `phase-15` `:5` and `phase-16` `:5`. Every `agent-report.md` index path I globbed exists, and no "see the reports below" remains.
- **Consistency (check 9):** the README, final report, demo script and `human-commands.md` agree on the clean check (9 groups, 49 to 1427, 1363 s), the harness (1949/0) and `hooks-guard` (114/0). The README carries P3 PARTIAL and the H-8 PARTIAL verdict correctly. The final report and demo script do not (B2, B4).
- **Privacy (checks 2, 3, 10):** the only 12-digit values in the reviewed docs are `000000000000` placeholders. No ARN, real bucket or credential appears in the new docs or `phase-17/` (the B5 literal is fake). The identity matches H-7. NetID and UIN appear only as placeholders. No text is addressed to graders, and OpenSearch and the separate services are stated as not implemented (`README.md:34,236`; `final-report.md:150,289`).

## Pending (owner to run or record)
- **H-4** (human): confirmation of the `human-gate.md` files for phases 01-16 is not recorded. Only Phase 00 is (`human-commands.md:47`). The phase-01 choice of `027c9b7` is the one `report-A1` flagged.
- **Live Stop-hook firing** under the new command form (human, at the demo): P3 showed no block with no changed source. That result looks the same whether or not `verify-tests-ran.sh` ran. P1, P2 and P4 cover only the PreToolUse and PostToolUse hooks.
- **WO-F2** (test-engineer): `sbt check` with exact counts. **WO-F3** (infra-engineer): Terraform and harness checks. **H-14** (human): `sbt clean check` on the final commit, harness, strict account-id scan, and V1 to V3 on the final commit.
- **Value check** (orchestrator): the pre-rewrite literals in `Phase10RepairSuite.scala:459`, `run.sh:13033` and `rehearse.py:32` were not supplied as diffs. "Values unchanged" rests on the reports. Supply `git diff 6072310..HEAD` for those files if exact equality must be proven.

## Limits of this review
- `README.md` and `reports/final-report.md` were read in full. `docs/demo-script.md` and `modules/hadoop/JOB-CONTRACTS.md` were read in full.
- `docs/architecture.md`, `data-lineage.md`, `api.md` (except :183-186), `runbook.md`, `service-limits.md` (except :118-121), `threat-model.md` (Grep only), three of the four `datasets/schemas/*.md`, 15 of the 17 `human-gate.md` bodies, and most of the `phase-17` archive files were checked by Grep and sampling, not line by line. They rely on the earlier B12a to B12e and A5 approvals.
- I did not re-verify code line citations such as `Config.scala:192` or `Model.scala:64-88`.
- Glob counted 75 files under `phase-17/` (37 in `wave-b/`) against the 76 and 38 stated in the dispatch. A hidden file such as `.DS_Store` may be present. The orchestrator should check `git status --porcelain --untracked-files=all` before the close commit.

## Next gate
Route B1 to B4 to documentation-engineer in one repair round, and B5 and B6 to the orchestrator for a human decision. The re-review covers only the changed lines.
