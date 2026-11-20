# Re-review of the B12d and B12e repairs (code-reviewer, Opus), as received 2026-11-19 (condensed by the orchestrator)

## Verdicts per file
- **`docs/demo-script.md`: APPROVE.**
  - R1 to R7 and A1 to A6 are verified against their sources.
  - Path (i) has no live Haiku anywhere (step 4 uses the A2-stub set; `services-session.tfvars:49` is `stub`).
  - The cost and risks match B8b.
  - P17-30: a repo-wide search finds no "Autograder notes" outside `docs/agent-runs`. "Known check findings" appears at `README.md:9`, `demo-script.md:145` and `final-report.md:132, 192, 313`.
  - Advisory: `:318` "4/4 PASS" matches the digit/digit pattern.
- **`modules/hadoop/JOB-CONTRACTS.md`: REQUEST CHANGES.**
  - `:87` "the only `hadoop jar` mentions are …" is false; the phrase also appears at `PipelineDriver.scala:248`, `DblpExtractionJob.scala:195` (scaladoc) and `tests/scripts/run.sh:4392, 6329, 11047, 11048`. None of these is a local recipe. The list came from the reviewer's own B12e item 5.
  - Items 1 to 4 are verified, and the file has no digit/digit hits.
- **`datasets/schemas/*.md`: APPROVE.**
  - Items 6 and 7 are verified against the manifest lines and parsers.
  - The only digit/digit hit is a DOI at `orcid.md:57`.
  - "Prose unchanged" is accepted on the orchestrator's diff evidence.
  - Advisory: `dblp.md:11` "records the mismatch" should be "records the release mismatch" (`:532` is about release versus snapshot).

## Orchestrator routing
- The required `:87` fix goes to hadoop-engineer.
- Both advisories are applied in the same round, to keep the V2 pre-scan clean and the citation exact: `demo-script.md:318` "4 of 4 PASS" (documentation-engineer) and `dblp.md:11` (dataset-engineer).
- Then a re-review of those three lines only.
