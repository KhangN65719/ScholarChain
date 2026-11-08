# entity-resolution-engineer report: exporter on real Phase 4 v4 output

Author: entity-resolution-engineer (archived by the orchestrator, condensed). Date: 2026-11-07. Edits only under modules/entity-resolution/**; no commit, no network, nothing written under phase4-out.

## Findings
- Candidates: real line parses as core `MatchCandidate`; mention-level researcher pairs; provenance snapshotId `openalex:2026-10-14`, run `run:v1:c9952ced-...`.
- Records: use `stage=graph-export/works/v1` as the single `--records` directory. Byte sizes of dblp-extraction (13,414,082) + crossref (1,033,087) + openalex (1,679,079) works = 16,126,248 = graph-export works total, so it is the full union. `dblp-extraction/works` alone is wrong (first real candidates are OpenAlex-OpenAlex). Expected missing-record rate about 0 (derived from sizes/IDs, not counted; export prints `excluded:`).
- Layout: `v1/part-r-0000{0-3}` plus hidden `.crc`; `_SUCCESS` at stage level. Fixed in InputFiles: recursion in name order skipping `_`/`.` names; fail with "No data files under ..." on empty dir; streaming of about 450 MB candidates (reduced to per-pair entries); directory digest uses relative paths. Dedup keeps smallest block key.
- Added: `pairs.Locations` (refuse --out/--pairs/--gold inside the repo, under any `target/`, or inside inputs), `LabelServer.serveUntilInterrupted` (Ctrl-C frees the port), manifest field `recordsIndexed`.
- Policy notes already present in Policy.scala lines 19-32 (researcher: ORCID-only CONFLICT; work: DOI mismatch CONFLICT; other work-level IDs pending human ruling); IdentifierRulesSuite covers it. No change.

## Tests added (entity-resolution module)
InputFilesSuite (6), RealPhase4FormatSuite (real candidate/work lines as fixtures, one records dir resolves all sources, wrong dir detected, markers-only dir fails, dedup, streaming equals in-memory, malformed line named), LocationsAndLifecycleSuite (6). Existing PairsExportSuite (11) still passes.

## Agent's `sbt check` (not the recorded gate)
`[success]`. core 41, entity-resolution 151, tests 190, hadoop 177; total 559 passed, 0 failed, 0 errors. Two earlier runs failed because AdversarialLeakageSuite flagged the word "Gold" in a doc comment in pairs/Io.scala; comment reworded. Mid-output truncation: the failure was found in the surefire XML.

## Commands (human, `!`, repo root)
Export: `sbt -J-Xmx3g "entityResolution/runMain scholarchain.er.sampling.PairsExport --candidates <run>/snapshot=26a3ad334ddf/stage=candidate-features/match-candidates/v1 --records <run>/snapshot=26a3ad334ddf/stage=graph-export/works/v1 --out /Users/winnguyen/scholarchain-data/gold/phase5 --seed scholarchain-er-phase5-v1 --total 230"` with `<run>` = `/Users/winnguyen/scholarchain-data/phase4-out/subset-v4/run=run-v1-c9952ced-c040-4db3-a350-03ddc92c7ec5`.
Outputs: `pairs.jsonl`, `private/strata-key.jsonl`, `private/export-manifest.json`.
Label: `sbt "entityResolution/runMain scholarchain.er.eval.labeling.LabelServer --pairs .../gold/phase5/pairs.jsonl --gold .../gold/phase5/gold.jsonl --reviewer <name>"` (optional `--suggestions <csv>`, `--port`; default 8765, loopback, token in URL; Ctrl-C stops and prints totals; rerun resumes).

## Open / risks
- Snapshot provenance: real DBLP records carry snapshotId `dblp:2026-11-01`; the subset derives from the 2026-11-05 dump (see subset-run-logs.md). Gold labels copy snapshots from records. Needs a human decision before labeling. Candidates carry `openalex:2026-10-14`.
- Untested: real-scale run (time/memory/missing-record rate), actual stratum availability, `main()` argument wiring. Hard-common-name stratum may fall short (agreeing-context pairs excluded by default); no top-up.
- Display noise: source-local keys appear as `other:*` identifiers on the labeling page.
- For test-engineer: new symbols `pairs.Locations`, `InputFiles.dataFiles/withLines`, `PairsExport.runAll/withCandidates/exportFiles`, `LabelServer.serveUntilInterrupted`; AdversarialLeakageSuite scans `pairs` for "Gold".
