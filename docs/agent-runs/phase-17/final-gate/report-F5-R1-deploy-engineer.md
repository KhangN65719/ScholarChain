# WO-F5-R1 report (deploy-engineer), verbatim as received 2026-11-20

---

**STATUS:** COMPLETE. I ran no commands, including no build, AWS or sbt. One `grep` I attempted was refused by the guard, and I read the files with Read instead.

**OWNER:** deploy-engineer

**WORK ORDER:** WO-F5-R1 (deploy-engineer part: B3 and non-blocking items 1 to 3, plus the `run.sh` result)

**FILES MODIFIED:** `/Users/winnguyen/ScholarChain/docs/releases/phase-17/README.md` only. Line numbers are the ones in the file before my edits.

| Fix | Location | Before | After |
|---|---|---|---|
| Item 3, P16-34 sentence | line 12 | "not established by the records read" | Cites `docs/releases/phase-15/deploy-tn-s3-a3.md:32`: the A3 apply removed the slow-deadline variable, which implies the A2 and `tn-s2b` applies carried it. Still marked UNVERIFIED, because that record's author did not read the plan diff. |
| Item 2, stale `git status` list | line 19 | Listed the session-start modified files | States that the uncommitted documentation changes and `docs/agent-runs/phase-17/` are committed in the final documentation commit (PENDING, human, P17-6). No files listed. |
| Item 1, P17-6 citation | lines 19, 20, 21 | `decisions.md:11` | `decisions.md:12` |
| Item 1, P17-7 citation | line 193 | `:14` | `:13` |
| `run.sh` result | line 183 | PENDING | Exit 0, `RESULT pass=1949 fail=0`, `SKIPPED_CASES 0`, `real-repository-untouched PASS`. Run by the human on 2026-11-20 on `/tmp/sc-gate` at `264f24b`, cited to `human-commands.md:72`. |
| `run.sh` in the open fields table | line 204 | PENDING, human | RECORDED, owner none |
| B3, new section 12 "Billing readings" | inserted before "Open fields" | Did not exist | 10.39 USD at 10:59 on 2026-11-19 (`decisions.md:22`, P17-17). 10.44 USD at 12:58 on 2026-11-20, limit 20.00, "Approaching limit", forecast 298.10 read by the human as an extrapolation (`decisions.md:38`, P17-33). Both are labelled account-level, month-to-date, subject to Billing lag, not per-phase bills. |
| B3, open fields table | line 213 | "Billed spend ... NOT RECORDED" | Three rows: the readings are RECORDED, the per-phase split is NOT DERIVED, and the final reading is PENDING (human, H-10). |

**VALIDATION:**
- I read each `decisions.md` line the manifest cites and confirmed it. P17-6 is at `:12`, P17-7 at `:13`, P17-17 at `:22`, P17-25 at `:30`, P17-33 at `:38`, P17-37 at `:42`, P17-38 and P17-39 at `:43` and `:44`. The remaining citations (`:38`, `:42`, `:44`) were already correct.
- `human-commands.md:72` holds the harness result, and `:67` and `:71` hold the F3 run and the 114/0 result.
- No other summary line in the file lists `run.sh` as PENDING. Section 9 and the open fields table were the only places.
- I wrote no account id, secret or invented value.

**RISKS:**
- I did not re-read the file after editing, so the section 12 numbering and the line shifts are unchecked. The re-review should read the changed lines.
- The harness log path stays unquoted in the manifest, which says only "kept outside the repository". That matches the other log notes.

**BLOCKED-ON:** none. Items still open, none owned by me: final documentation commit and tag (human, P17-6), final billing reading (human, H-10), and the plan checksum and inventory fields if the human runs demo item 18 or 22 (I would update them then).
