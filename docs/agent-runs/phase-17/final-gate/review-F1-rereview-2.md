# Re-review 2 of WO-F1 (N1 only)

Author: code-reviewer (Opus). Recorded by the orchestrator on 2026-11-20, verbatim as received.
Orchestrator action after this verdict: added the dated P17-36 note after the header of `wave-a/work-orders.md` (at line 5), and the optional "since decided" clause to the header of `review-F1-rereview-1.md`.

---

**Re-review 2 of WO-F1 (N1 only): REQUEST CHANGES**

I'm asking for changes on one point. In `docs/agent-runs/phase-17/wave-a/work-orders.md` the two redactions are correct, but the file has no dated, attributed note. Everything else in N1 passes.

**Status:** COMPLETE. **Owner:** code-reviewer (Opus). **Work order:** WO-F1, re-review 2, N1 only. **Files created or modified:** none. **Commands run:** none. I have only Read, Grep and Glob.

## Checks

**1. Key pattern across the whole tree: PASS.** I searched `/Users/winnguyen/ScholarChain` for the AKIA key pattern (AKIA followed by 16 uppercase letters or digits). There are zero hits.
- `report-A3a-rag-engineer.md:11` and `review-F1-verdict.md:50` still contain the regular expression itself. That text is not a key, and it does not match the pattern.

**2. Quoted assignment form in `docs/agent-runs/phase-17/`: PASS.** The search was case-insensitive: the word secret, optional spaces, an equals sign, optional spaces, then a double quote. There are zero hits.

**3. Each redaction keeps its meaning and has a dated, attributed note: PASS in four files, FAIL in one.**
- **`final-gate/review-F1-verdict.md`: PASS.**
  - The note is in the header at `:3`. It is dated 2026-11-20, credits the orchestrator and cites P17-36.
  - The redactions are at `:49`, `:74` and `:75`. Each now says "fake AKIA-prefixed key id [REDACTED, P17-36]".
  - The findings still mean the same thing. B5 still identifies the A3a literal, and check 5 still says the A3c fixture and the `AwsIngestMainSuite.scala:727` value equal the old literal. No value is restated, so none could change.
- **`wave-a/report-A3a-rag-engineer.md`: PASS.**
  - `:11` now describes the scanner as matching the key pattern and the quoted assignment form, which is the same meaning as before.
  - `:18` is the P17-34 note, reworded to describe the form instead of quoting it. The rewording is disclosed.
  - `:19` adds the second note, dated 2026-11-20, credited to the orchestrator and citing P17-36 and N1.
- **`wave-a/report-A3b-storage-engineer.md`: PASS.**
  - `:11` keeps the assumption: the scanner flags the form, not the value.
  - `:17` is the note, dated, attributed and citing P17-36. It says nothing else changed.
- **`final-gate/review-F1-rereview-1.md`: PASS.**
  - The note is in the header at `:3`. It is dated and attributed, and describes the replaced form without quoting it.
  - Non-blocking: the header still says "following P17-34 and pending the human's N1 decision". You could add that P17-36 has since decided N1. This is optional.
- **`wave-a/work-orders.md`: FAIL. This is the blocking finding.**
  - The marks at `:29` ("[REDACTED wording, P17-36]") and `:33` ("[REDACTED quotation, P17-36]") keep their meaning. `:33` still names the line, the `secret` local and the SENTINEL ORCID value well enough for the instruction to make sense.
  - However, the file has no dated, attributed note anywhere. Its header at `:3` still reads only "Issued by the orchestrator, 2026-11-19 ...", and the file ends at `:54` with the WO-B3 text.
  - This contradicts `decisions.md:41` (P17-36): "with a dated note in each file or header". The dispatch's claim of "a mark and a note" in each file is also inaccurate for this file.
  - **Fix (owner: orchestrator):** add one line after the header at `:3`. It should say that on 2026-11-20 the orchestrator replaced the quoted assignment form on the WO-A3a and WO-A3b lines with a description, under the human's decision P17-36 (re-review F1-1 finding N1), and that nothing else changed.

**4. No other text changed: PARTLY VERIFIED, rest PENDING.**
- `phase-17/` is untracked, and I was given no copies or diff from before the edits, so I cannot prove byte for byte that nothing else changed.
- Indirect evidence that nothing else moved:
  - Every line number cited in re-review 1 still points to the same content (verdict `:49/:74/:75`, work-orders `:29/:33`, A3a `:11` with its P17-34 note at `:18`, A3b `:11`). The only line added is the appended note at A3a `:19`, and it comes after all of them.
  - The surrounding lines I cited in F1 and re-review 1 read the same as before: A3b `:7`, A3a `:7`, verdict B5 and check 5 wording.
- **Pending:** if exact proof is needed, the orchestrator can keep copies from before the edits and supply a diff. It can't use `git diff` because the files are untracked.

## Observations outside N1 (not ruled on)

- `report-A3b-storage-engineer.md:11` still contains the word `secret` and an equals sign with no quote after it. That form fails check 2's pattern and was not part of N1. The K01 scanner's exact pattern is still unverified (re-review 1), so only H-14 V1 can confirm it.
- `work-orders.md:37` and `:41` quote `TOKEN` assignments, one of them with a value. These are not the secret form and were not part of N1. I note them only so that V1 results can be read against them.

## Pending

- WO-F5 items, routed separately.
- H-14 V1 (including K01) on the final commit. Only that run confirms what K01 matches.

## Next gate

The orchestrator adds the dated note to `docs/agent-runs/phase-17/wave-a/work-orders.md`. Re-review 3 then covers only that note.
