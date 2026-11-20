# Review F5: close drafts (WO-F4 manifest, WO-F5 edits, F6 drafts)

Author: code-reviewer (Opus). Recorded by the orchestrator on 2026-11-20, verbatim as received. The review ran before the human relayed the `run.sh` result, so its "run.sh PENDING" statements were correct when made.

---

**Review F5 verdict: REQUEST CHANGES**

Three blocking findings, one each for documentation-engineer, deploy-engineer and the orchestrator. Each is a small text fix and they can all go in one repair round. Everything else I checked holds.

**Status:** COMPLETE, verdict delivered. **Owner:** code-reviewer (Opus). **Work order:** review F5 (scope: WO-F4, WO-F5 and the F6 drafts). **Files created or modified:** none. **Commands run:** none. I have Read, Grep and Glob only.

## Blocking findings

**B1. The gate draft gets the F1 review history wrong.** Owner: orchestrator.
- `docs/agent-runs/phase-17/human-gate.md:9` says "One repair round followed, and re-reviews 1 to 3 ended with APPROVE".
- The records show three repair rounds, and two of the re-reviews asked for changes:
  - Round 1 was the documentation and Hadoop repair (WO-F1-R1 and WO-F1-R1h). Re-review 1 then returned REQUEST CHANGES on N1 (`review-F1-rereview-1.md:7`).
  - Round 2 was the P17-34 and P17-36 redactions. Re-review 2 then returned REQUEST CHANGES on the missing dated note (`review-F1-rereview-2.md:8`).
  - Round 3 was that note. Re-review 3 returned APPROVE.
- Fix: state the three rounds and say that re-reviews 1 and 2 asked for changes and re-review 3 approved.

**B2. The README and final report still say no `hooks-guard.sh` run on the committed tree is recorded, and the README lacks the final gate result.** Owner: documentation-engineer.
- `README.md:84` and `reports/final-report.md:104` say "no run on the committed ... tree is recorded".
  - `human-commands.md:67,71` now records `RESULT pass=114 fail=0`, run by the human on a fresh clone of `264f24b`.
  - No `.claude/` file changed after `5157e0e` (P17-31, P17-39), so that run covers the committed guard files.
- `README.md:80-85` lists the `5157e0e` and H-8 runs but not the gate `sbt clean check` on `264f24b` (exit 0, 1360 s, P17-39).
  - The final report (`:243`, `:333`), demo script (`:156`), manifest (`:180`), gate draft (`:12`) and index (`:11`) all carry it.
  - So the README is the one deliverable that is out of line with the others.
- Fix: add the P17-39 result and the F3 `hooks-guard` run to `README.md:80-85`, and reword `README.md:84` and `final-report.md:104`. Optionally also update `final-report.md:184` and `docs/runbook.md:31`.

**B3. The manifest says billed spend is not recorded, but a file it read records it.** Owner: deploy-engineer.
- `docs/releases/phase-17/README.md:213` says "Billed spend for Phases 15 to 17 | NOT RECORDED in the files read".
- The manifest itself cites `decisions.md:38` (P17-33), which records the human's reading: 10.44 USD month-to-date at 12:58 on 2026-11-20, spend limit 20.00, status "Approaching limit".
- `decisions.md:22` (P17-17) records 10.39 USD month-to-date at 10:59 on 2026-11-19.
- **On your question: yes, the manifest should cite both readings.** They are account-level, month-to-date readings by the human, with Billing lag, and not per-phase bills. The fix:
  - Keep the per-phase split marked NOT DERIVED.
  - Keep the final reading PENDING (human, H-10).
  - Optionally note the forecast of 298.10, which the human reads as an extrapolation.

## Non-blocking findings

1. **Wrong line numbers in the manifest** (deploy-engineer). `README.md:20-21` cites `decisions.md:11` for P17-6, which is at `:12` (`:11` is P17-5). `:193` cites `:14` for P17-7, which is at `:13` (`:14` is P17-8). The other `decisions.md` and `human-commands.md` line numbers are correct.
2. **Stale file list in the manifest** (deploy-engineer). `README.md:19` lists the session-start `git status`. The tree now also has `docs/api.md` and `docs/agent-runs/phase-00/human-gate.md` modified. Either drop the list or update it at close.
3. **P16-34 sentence in the manifest is weaker than the records** (deploy-engineer). `README.md:12` says it is "not established" whether an earlier session deployed the 60000 setting. But `docs/releases/phase-15/deploy-tn-s3-a3.md:32`, which the author read, says the A3 apply removed the slow-deadline variable (a runbook statement, UNVERIFIED there). That implies the A2 and `tn-s2b` applies carried it, and `README.md:46` says the same. Cite that and keep the UNVERIFIED mark.
4. **Constitutional ratifications incomplete in the gate draft** (orchestrator). `human-gate.md:10` names A2, A2b and A6, but `human-commands.md:79` also ratified R1 and R2.
5. **No M9 in the final report** (orchestrator). `human-gate.md:17` says "M1 to M13", but the table has no M9: `final-report.md:73` says "formerly M9", and the table is M1 to M8 and M10 to M13.
6. **"Only" is too narrow in JOB-CONTRACTS** (hadoop-engineer, optional). `modules/hadoop/JOB-CONTRACTS.md:259` says the suites passed "only within the aggregate H-8 part 1 `sbt check`". They also passed in the `5157e0e` clean check and the P17-39 gate. Drop "only", or cite P17-39 as well. Otherwise `:259` now agrees with `:213`.
7. **Old billing figure in the demo script** (documentation-engineer). `docs/demo-script.md:43` (section 1.4) gives only 10.39 USD (P17-17). Add 10.44 USD (P17-33).
8. **Duplicated text in the final report** (documentation-engineer). In `reports/final-report.md:94`, the closing sentence repeats P17-37 and P17-39, which are already in the human's list. The long parenthetical also has a full stop in the middle. The source sorting is otherwise correct: I checked all 39 IDs against the source column of `decisions.md`.
9. **Wording in the index** (orchestrator, optional). `agent-report.md:26` says "autograder fixes". "Automated check fixes" would match the neutral wording of P17-30.
10. **Add this review once archived** (orchestrator). Add review F5 to `human-gate.md:7-9` and as a row in `agent-report.md` (`:31` covers only `review-F1-*.md`).

## Verified

- **Manifest values against their sources:**
  - Image digests match `deploy-tn-s2b-a2b.md:31-32` and `deploy-s-session-15.md:25`.
  - Plan, script and args digests match their cited lines.
  - Dataset values match `dataset-manifest.yaml`: the DBLP md5 at `:30` and `:37`, all 12 ORCID md5 values at `:169-246`, the Crossref sha1 at `:312`, the sample sha256 values at `:61`, `:127`, `:270` and `:343`, the subset hashes at `:366-455`, the licences at `:463-466`, and the snapshot mismatch at `:387-402`.
  - Prompt ids and versions match `Model.scala:65-67, 76-78, 97-99`.
  - Model ids match `variables.tf:212, 216-218, 230, 238, 467` and `EmbeddingModels.scala:32-33, 48-55`.
  - Run ids match `runs-s-6-to-11.md:7, 26-87`, `phase-15/README.md:20-45` and `EVALUATION.md:19, 93, 883-884, 931, 958`.
  - Terraform pins match `versions.tf:2, 6-7` and `.terraform.lock.hcl:4-6`.
  - Test results match P17-39, `report-F2-test-engineer.md:22-28` and `human-commands.md:67-82`.
- **Tool scan** of the manifest, both gate drafts, the index and the 9 F5 files:
  - The only 12-digit runs are `000000000000`. The two digests with long digit runs are shortened in the manifest, as the author said.
  - No ARN, no bucket name containing an account id, and no private-key header.
  - The AWS key pattern (AKIA plus 16 uppercase letters or digits) has zero hits across the whole repository.
  - The quoted secret-assignment form has zero hits under `docs/`, `reports/` and `README.md`.
  - `README.md:58` names only the AWS secret-key environment variable, with no value. That text predates this round.
- **P17-37:** no offer of a Titan, Haiku or S3 Vectors call remains in the demo script, README, final report or manifest. The local-services segments of items 14 and 15 (`:240`, `:260`), the item 19 console view (`:335`) and the item 22 inventory (`:372-385`) are kept. Item 11 is ARCHIVED only.
- **`run.sh`** is PENDING in all five documents.
- **Test counts:** the gate figures (1360 s, 9 groups, one skip, Failed 0 and Errors 0), `hooks-guard` 114/0 and F2 158 s agree wherever they appear. The only gap is the README (B2).
- **WO-F5 locations:** each listed location is fixed as reported: D03, D12, D14, `api.md:205`, `runbook.md:3`, `threat-model.md:145`, `service-limits.md:95`, `final-report.md:94` and `:243`, demo items 7, 13, 14 and 18, and section 1.4.
- **No text addressed to graders** in the manifest, either gate draft or the index.
- **`docs/agent-runs/phase-00/human-gate.md:3,10`:** both now cite the H-4 confirmation at `human-commands.md:79`. No "to be confirmed" text remains.
- **Index (`agent-report.md`):**
  - OWNER is one agent name, `documentation-engineer`, the same form as phases 10 to 16.
  - The file-list fields hold no paths.
  - Every row's glob matches existing files: `wave-a/` and `wave-b/` reports, repairs and reviews; `final-gate/report-*` by the five named authors; `review-F1-*`; `plan.md`, `decisions.md`, `human-commands.md`, `prompt.md`, `handoff-wave-a.md`, `autograder-v1-preview.md`; and four `work-orders*.md` files.
- **`commit.txt`** is absent, as P17-6 intends.

## Pending (owners to run or record)

- `run.sh` result (human).
- Final documentation commit and tag (human, P17-6).
- V1 to V3 on the final commit, including K01 (human, H-14).
- Demo results (human, H-9). If demo item 18 runs a live `plan.sh`, or item 22 runs its inventory, deploy-engineer updates the manifest's plan checksum (`:62`) and inventory (`:195`) fields.
- Per-group totals and exit code of the F2 `sbt quick` (not recorded).

## Limits of this review

- I read the F5 changes in the saved diff and the new files in full. The F1-R1 lines were not re-reviewed.
- I did not check the lock file hash lines `:8-23`, the dataset lines `:164-248` beyond the md5 values, or `smoke-tn.md`.

## Next gate

The orchestrator routes B1 and items 4, 5, 9 and 10 to itself, B2 and items 7 and 8 to documentation-engineer, B3 and items 1 to 3 to deploy-engineer, and item 6 to hadoop-engineer. A re-review then covers only the changed lines.
