# Re-review 3 of WO-F1 (N1 fix only)

Author: code-reviewer (Opus). Recorded by the orchestrator on 2026-11-20, verbatim as received. Orchestrator note: the reviewer's "human decision still needed on demo items 13 and 14" was already decided as P17-37 before this verdict; it is routed in WO-F5.

---

**Re-review 3 of WO-F1 (N1 fix only): APPROVE**

The fix holds, and the WO-F1 chain is now closed.

**Status:** COMPLETE. **Owner:** code-reviewer (Opus). **Work order:** WO-F1, re-review 3, scope limited to the re-review 2 blocking finding. **Files created or modified:** none. **Commands run:** none. I have only Read, Grep and Glob.

## Checks

1. **The note is dated, attributed and cites P17-36 and N1: PASS.**
   - The note is at `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-17/wave-a/work-orders.md:5`.
   - It reads "Redaction note (orchestrator, 2026-11-20, decision P17-36)". It cites "re-review F1-1 finding N1".
   - This meets the requirement in `decisions.md:41` (P17-36) for "a dated note in each file or header".

2. **The note is accurate: PASS.**
   - It says the quoted secret-assignment form was replaced by a description on the WO-A3a and WO-A3b lines, marked `[REDACTED ..., P17-36]`. That matches the only two marks in the file:
     - `:31` (WO-A3a) carries "[REDACTED wording, P17-36]".
     - `:35` (WO-A3b) carries "[REDACTED quotation, P17-36]".
   - Grep finds no other REDACTED mark in the file.
   - The added clause at `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-17/final-gate/review-F1-rereview-1.md:3`, "(since decided: P17-36, redact all)", matches `decisions.md:41`: "Human, 2026-11-20, on re-review F1-1 finding N1: redact all."
   - "Nothing else in this file was changed" is not proven byte for byte. The file is untracked and I had no copy from before the edit, so this stays PENDING, as in re-review 2. The surrounding text at `:29-:43` reads the same as what I cited earlier.

3. **Neither edit brings back a banned pattern: PASS.**
   - **Key pattern** (AKIA followed by 16 uppercase letters or digits): zero hits across the whole repository.
   - **Quoted assignment form** (the word secret, optional spaces, an equals sign, optional spaces, then a double or single quote; case-insensitive): zero hits under `docs/agent-runs/phase-17/`.
   - The word AKIA still appears on its own at `review-F1-rereview-1.md:13, :21, :31, :104` and `work-orders.md:31`. These are only descriptions and do not match the key pattern.

## Non-blocking observation

The new note and its blank line push later lines in `work-orders.md` down by two. As a result, earlier citations now point two lines early:
- `review-F1-rereview-1.md:28-29` cites `work-orders.md:33` and `:29`.
- `review-F1-rereview-2.md:37` cites `:29` and `:33`.

The redacted lines are now at `:31` and `:35`. The archived verdicts are kept as received, so I'm not asking for an edit. The line-number shift explains the gap.

## WO-F1 chain

**Closed.** N1 is resolved, all of the B1 to B6 repairs were accepted in re-review 1, and no blocking finding remains.

## Still routed from re-review 1, not ruled on here

- **WO-F5, hadoop-engineer:** finding (a). The wording at `modules/hadoop/JOB-CONTRACTS.md:259` should match `:213`.
- **WO-F5, documentation-engineer:**
  - Finding (b): `docs/api.md:205` (stale "(H-8)") and `docs/runbook.md:3` (the "until H-8 is archived" wording).
  - Item 1: `docs/threat-model.md:145`, the leftover "Replay result lands with H-8."
  - Item 2: `reports/final-report.md:94`, the P17-33 source framing and the grammar nit.
  - Item 3: `reports/final-report.md:241`, which should show the final-gate outcome.
  - Item 4: `docs/service-limits.md:95`, which needs a capital "The".
  - D03, D12, D14 and D19.
- A human decision is still needed on demo item 13, and possibly item 14 (`:43`), for the optional laptop S3 Vectors or Bedrock runs.

## Pending

- The proof that nothing else changed in `work-orders.md`. It needs a copy of the file from before the edit, because the file is untracked.
- WO-F2 (test-engineer, `sbt check` counts), WO-F3 (infra-engineer, Terraform checks), and the WO-F5 lines when they land.
- H-14 (human): `sbt clean check` on the final commit, V1 including K01, and the strict account-id scan. Only V1 can confirm what K01 matches.

## Next gate

The orchestrator records this verdict and dispatches WO-F5. A re-review then covers only the WO-F5 lines.
