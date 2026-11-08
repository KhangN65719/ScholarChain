# Work order 11: rag-engineer, repair round 2 (real-data finding R1)

Owner: rag-engineer (modules/rag/**). This is the LAST permitted repair round. Same rules as work orders 04 and 08: local only, no new dependency, no weakening or deleting tests, no edits outside modules/rag. If a root test under tests/** encodes the old behaviour, report `BLOCKED-ON test-engineer` naming the test and the new assertion; do not edit tests/**.

## Evidence (human's real-data run, 2026-11-08 14:35, log `/Users/winnguyen/scholarchain-data/logs/phase-08-ingest.log`)
The ingest of the real export succeeded: all five indexes, planned = written (35,160 / 6,663 / 20 / 42 / 0 vectors), `rejected=0 unmapped=0 failedBatches=0`, 41,885 documents (62,086,569 bytes), `fetchText: textHash verified` on every sample, local timing 23,287 ms. The run then ended `PHASE8-INGEST RESULT: FAILED a sample query failed` and exit nonzero, because of ONE sample:

```
SAMPLE collaboration-path-v1: LOCAL latency 0 ms, 3 hits, nearest is the source row: false
  query: In window all the shortest collaboration path from Ying Zhang 0001 to
  filter: documentType=collaboration-path years=1..9999
WIDE-SAMPLE collaboration-path-v1: ... 5 hits among 42 rows of this type, source row rank: 2 of 5, other candidates: true
```
The query is the first 12 words of the source summary, which stop before the distinguishing endpoint, so another path summary beats the source row. Templated summaries of the same type share their opening words (the reviewer's N6). The index, embedder and ingest are not at fault; the sample's query choice is.

## Required work
1. Fix the sample-query selection in `IngestMain.scala` (~187-193, ~239-275) so a sample query identifies its source row on real templated data. The check "the source row is the nearest hit among the filtered candidates" is an identity check, so build that query from the source row's FULL rendered text (the same text that was embedded), which must retrieve itself at distance ~0 unless two rows have identical text. Keep the printed `query:` line short and clearly labelled as a display prefix of the query actually used. Do the same for the WIDE-SAMPLE query. If two different rows have identical rendered text, a tie is legitimate: treat the source row being among the top-ranked ties (same distance) as a pass and print that fact; any other rank still fails.
2. Keep the check strict: the run must still FAIL if the source row is not retrievable (not nearest, or not among exact ties, in its filtered sample; not in the top 5 of the wide sample when other candidates exist), if `fetchText` fails or a hash mismatches, or any reject, unmapped, failed batch or count mismatch occurs. Do not turn the failing check into a warning.
3. Make a rerun into the same output directory work after a failed-sample run: today a FAILED run may leave `ingest-report.json` behind. A rerun with the same export and bound must either reuse the directory (identical deterministic report) or give a clear message telling the human to delete the directory, whichever the existing "output dir already holds a different export or bound / not empty without a report" rules imply. State in your report exactly what the human must do before rerunning (the human can delete `/Users/winnguyen/scholarchain-data/phase8-out/ingest/run=run-v1-4600a8f4-d438-4683-8852-db8743eb23f0` themselves; agents cannot).
4. Feature tests in `modules/rag/src/test/...`: a fixture where several path or profile summaries of one type share their first 12+ words and differ only later; with the old 12-word query the source row is NOT nearest (assert that as the regression precondition), with the full-text query it IS nearest; an exact-duplicate-text pair passes as a tie; a corrupted index (source row missing or its vector altered) still FAILS the run; the printed `query:` line is a labelled display prefix. Keep every existing rag test (adjust only assertions that encoded the old 12-word query, naming why).

Test-to-item list FIRST in your report. Run `sbt quick` ONCE at the end (no one else is running sbt). Put the COMPLETE report in your final message text, then the gate status, never instead of it. If a root test outside modules/rag turns the run red, still give the full report and name that test as BLOCKED-ON test-engineer. Orchestrator archives it as `report-11-rag-repair2.md`.
