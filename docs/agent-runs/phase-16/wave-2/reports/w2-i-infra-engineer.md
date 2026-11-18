# W2-I: infra-engineer report

Author: infra-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (lightly condensed formatting, content unchanged).

---

**Status: COMPLETE for the edits; no harness case run.** Owner: infra-engineer. Work order: W2-I.

**Diff summary**
- `scripts/run-experiment.sh:144` is now `A | B | C | D | E | F | G | H) ;;`. Refusal message at :145: "unknown EXPERIMENT_ID '$EXP_ID': the experiments are A to F (course) and G and H (Phase 16 evaluation)" (still contains "unknown EXPERIMENT_ID").
- `scripts/README.md`, "Experiment manifest contract": the `EXPERIMENT_ID` row allows A to F plus G (retrieval quality) and H (answer quality); all eight ids take the same validation, no per-id table. The refused-list paragraph notes the G and H harness cases are not yet observed.
- No `lib.sh` change needed: read in full (about 1050 lines); no experiment-id table, entry-point map or letter mapping. The only id check is `run-experiment.sh:144`. Tier caps, `tier_caps`, the `experiment-size-<tier>.tfvars` overlay, the `ENTRY_POINT` regex and the run id `exp-${EXP_ID}-...` are id-agnostic; the harness derives the letter from `${bn:11:1}`.

**Test per item**
1. G and H accepted with A-F validation: `8.committed-manifest.G` and `.H` (`--print-digest` then `--dry-run`; `MANIFEST_VALID experiment=G`, `LIMITS max_minutes=`, `CHECK_SKIPPED entry-point`, `APPROVAL_VERIFIED action=experiment`, `APPROVAL_NOT_CONSUMED dry-run`, `DRY_RUN_COMPLETE`). G's tier S limits are within the caps; its `ACCOUNT_ID` placeholder 123456789012 is passed as `$ACCT` by the harness (match not checked).
2. Unknown ids still refused: `8.refuse.unknown-experiment-id`. It currently sets `EXPERIMENT_ID G`, which now passes, so the case will fail until test-engineer changes the id (e.g. `Z`), `tests/scripts/run.sh:1725`. Routed.
3. Message wording: the same case, via the unchanged "unknown EXPERIMENT_ID" fragment.

**Routed requests**
- test-engineer: change `8.refuse.unknown-experiment-id` input from `G` to an invalid id.
- `8.committed-manifest.count` expects 6 (line 1817); test-engineer is updating counts.
- `CHECK_SKIPPED entry-point` holds only while `experiments/g-retrieval/run.sh` and `experiments/h-answers/run.sh` are not tracked in the harness's temporary repository; if they are committed there, the line is not printed and the case fails. test-engineer to confirm.
- `SCRIPT_DIGEST` of `run-experiment.sh` changed; no approval record exists for it. The G and H manifest comments "BLOCKED until infra-engineer widens ..." are now stale (experiment-engineer's).

**Verification**
- Refused by the guard, not run: `bash -n scripts/run-experiment.sh` ("Shell and interpreter wrappers are blocked"); `grep` over `lib.sh` and `README.md`; a compound command (chaining). `tests/scripts/run.sh`, `shellcheck`, `--print-digest` not attempted.
- `scripts/README.md` read only to line 209; a stale "A to F" after that is possible.
- Pending: human or orchestrator runs `bash -n`, `shellcheck -x scripts/run-experiment.sh`, and harness cases `8.committed-manifest.G`, `.H`, `8.refuse.unknown-experiment-id`.

Files modified: `scripts/run-experiment.sh`, `scripts/README.md`. Next gate: harness after test-engineer's update, then independent review.
