# WO-F5 report, documentation part (documentation-engineer), verbatim as received 2026-11-20

---

STATUS: COMPLETE

OWNER: documentation-engineer
WORK ORDER: WO-F5, documentation part (Phase 17 final gate)

I ran no commands, no sbt and no link or text check after the edits. The edits are checked against the cited records only.

**FILES MODIFIED**
- `README.md`
  - D03 row of the deliverable index: `phase-17/` now has `agent-report.md` and a draft `human-gate.md`. `commit.txt` and the final `human-gate.md` come in the docs-only follow-up commit (P17-6). The folder is committed with the close.
  - D12 row: fmt exit 0 and validate "Success! The configuration is valid." on a fresh clone of `264f24b` on 2026-11-20, run by the human because WO-F3 was refused by the guard. Plan checksum NOT RECORDED because no plan ran. The security scan citation is unchanged.
  - D14 row: links `docs/releases/phase-17/README.md` and says it is a manifest only, with no live session (P17-33). Status is "partial; manifest present, with the final commit and tag PENDING (human)".
  - Two further spots aligned:
    - The `plan.sh` paragraph now says the plan checksum is NOT RECORDED because no plan ran.
    - The not-covered list now says the same.
  - D19 is untouched.
- `reports/final-report.md`
  - Section 14, "What was and was not checked": no longer says validate is PENDING. It cites the F3 results, says the versions printed by `init` were not relayed, and says the plan checksum is NOT RECORDED.
  - Class 4 decision list:
    - P17-33 moved to the "both" group.
    - The double "and" fixed.
    - P17-36 and P17-38 added to the "both" group.
    - P17-37 and P17-39 added to the human's.
    - Short notes added for P17-33, P17-36 and P17-38.
  - Section 17 demo paragraph: added P17-37.
  - Section 19, final-gate paragraph (was `:243`; your `:241` has shifted):
    - F1 is APPROVE after re-reviews 1 to 3.
    - F2 `sbt quick` gave `[success]` in 158 s, labelled turn evidence only.
    - The human's gate `sbt clean check` on `264f24b` exited 0 in 1360 s, 9 groups all Failed 0 and Errors 0 (P17-39).
    - The first attempt hit OutOfMemoryError beside an agent's sbt (P17-38).
    - `hooks-guard.sh` gave 114 pass and 0 fail.
    - `run.sh` is PENDING (human).
    - The release manifest path is cited.
  - Final section, H-14 bullet: records the gate and the `hooks-guard.sh` result. `run.sh`, `scripts/check.sh`, the automated review runs and the account-id scan stay PENDING.
- `docs/demo-script.md`
  - Header and item 11: P17-37 added.
  - Section 1.4: the optional laptop Bedrock, Titan and S3 Vectors sentence is replaced by a statement that Phase 17 makes no such call.
  - Time table: item 13 is now "ARCHIVED".
  - Item 13: label is "ARCHIVED only", and the Optional LIVE bullet is removed.
  - Item 14: label is ARCHIVED, with the local stub-model segment kept and marked as making no AWS call. The optional H-answer bullet is replaced by a no-live-call line citing `exp-H-20261119T053926Z-2e8943cf`.
  - Items 15 and 22 and the item 19 console view are kept as they were.
  - Item 7: the PENDING "final-tree" line is replaced by the P17-39 result.
  - Item 18: fmt and validate cite the human's F3 result. The plan checksum is NOT RECORDED in Phase 17.
- `docs/api.md`: "(H-8)" removed from "carry their own replay marks".
- `docs/runbook.md`: line 3 now reads "is marked NOT REPLAYED; the H-8 records ... cover only ...".
- `docs/threat-model.md`: "Replay result lands with H-8." removed from the numbered list.
- `docs/service-limits.md`: "The Phase 17 plan ..." capitalised.

**ITEM STATUS**
1. D03, D12, D14: FIXED. D19 untouched.
2. PENDING alignment: FIXED in the README and final report (validate, release manifest, plan checksum), and in the demo script.
3. P17-37: FIXED. The AWS parts of items 13 and 14 are ARCHIVED, and the optional laptop AWS run is removed from section 1.4, the time table, item 13 and item 14. I found no such offer in the checklist, the README or the final report. The local segments of items 14 and 15, the item 22 inventory and the item 19 console view are kept.
4. Re-review 1 finding (b): FIXED.
5. Non-blocking items 1 to 4: FIXED. Items 1, 3 and 4 are exactly as specified. For item 2, the threat-model "Replay result lands with H-8." sentence is removed, and the existing line 3 form already matches.
6. By-source list (P17-36, P17-37, P17-38, P17-39): FIXED.

**VALIDATION**
- Every new figure and path comes from `decisions.md` (P17-33 to P17-39), `human-commands.md` (F3 section) or `docs/releases/phase-17/README.md`.
- I did not read the `rereview-1` finding text or the F2 report directly. The F1 APPROVE chain comes from rereview-3 and the work order. The 158 s F2 figure comes from the release manifest, section 9.

**RISKS**
- The two group totals and the exit code of the F2 `sbt quick` are not recorded, so I gave only the 158 s `[success]`.
- The F3 relay says only that fmt and validate passed on a fresh clone. The `hooks-guard.sh` 114/0 figure is from the manifest and `human-commands.md`, not from my own read of the result lines.
- The D12 row and demo item 18 cite `human-commands.md`, "Phase 17 final gate, F3". That section is the human's relayed record and has not been independently checked.
- Demo item 21, the checklist and some README text still say the main working tree is dirty until `phase-17/` is committed. That stays true until the close.
- The `run.sh` result stays PENDING (human).
- The demo script and the report still say the demo results are PENDING until the human records the H-9 laptop runs.

**BLOCKED-ON:** none.

**NEXT GATE:** independent re-review of these docs, then the human's `run.sh` result, the video link (D19), and the final commit and tag.
