# Work order 01: ER batch run over all match-candidates

Issued by: orchestrator, 2026-11-07. Owner: `entity-resolution-engineer`. Phase 6 prerequisite for Spark work. Context: `docs/agent-runs/phase-06/prompt.md` (Part B scope note) and `preflight.md`.

## Why
Spark's first job must read accepted researcher and work entity links (course Phase 6, job 1). No such artifact exists. Phase 5 delivered the matcher (`scholarchain.er.policy/1.4.0`) and a gold-set evaluator, but nothing runs the matcher over every Hadoop match-candidate and writes decisions.

## Inputs (verify each path exists and report counts before implementing)
- Match candidates: `/Users/winnguyen/scholarchain-data/phase4-out/subset-v6/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420/stage=candidate-features/match-candidates/v1/part-r-0000{0..3}`
- Records the matcher needs (works, researchers, author-mentions): `.../stage=graph-export/{works,researchers,author-mentions}/v1/`, and any other stage output the matcher already needs for `Records`. Name every path actually read.
- Forbidden as links or features: anything under `/Users/winnguyen/scholarchain-data/gold/` (gold labels, `pairs.jsonl`, evaluation outputs). Gold labels and the exported evaluation pairs are evaluation inputs only. The existing leakage tests must still pass, and add a test that the batch runner reads no gold path.

## Required behavior
1. A batch entry point (a `main` in `modules/entity-resolution`, bounded and local, no HTTP, no credentials, offline LLM stub only) that runs the existing matcher with the existing policy over ALL match-candidates, with no sampling or stratification. Do not change policy numbers, thresholds or weights. Any policy or matcher change is out of scope; if you believe one is needed, report it as a finding.
2. Output, written outside the repo under `/Users/winnguyen/scholarchain-data/phase6-out/er/` (run directory containing the run ID and snapshot), idempotent, deterministic (stable ordering, same input gives same logical output):
   - Decision records for every candidate with all four outcomes (ACCEPTED, REJECTED, UNRESOLVED, CONFLICT), each preserving source record ID, candidate target ID, features, rules fired, policy and prompt/model version when applicable, confidence, reason codes, timestamp or an explicitly deterministic substitute, and run ID. Reuse the core `MatchDecision` contract if it fits. If it does not, report `BLOCKED-ON domain-engineer` with the exact gap rather than inventing a parallel wire format.
   - An **accepted-link artifact** that Spark can read directly: for researchers, mention to canonical researcher link; for works, work to work link. Define one canonical researcher ID rule (must follow the core stable-ID principles: validated external identifier, otherwise a versioned ScholarChain identity ID). Merging must be derived only from ACCEPTED decisions, with transitive closure handled deterministically. A CONFLICT or UNRESOLVED decision must never merge, and a cluster that would merge two records an authoritative identifier says differ must be reported as an integrity failure, not silently merged.
   - A run manifest and summary: input counts, per-outcome counts, per-reason-code counts, per-path counts (exact id, deterministic bibliographic, probabilistic, LLM), cluster counts and sizes, count of integrity failures, policy version, code revision pointers, input paths and digests where cheap.
3. Provenance on every output record (source, snapshot, transform version, run ID).
4. Never collect unbounded state in a way that cannot handle the full candidate set; the subset has about 362,905 candidates, so measure memory and wall time and report them.

## Tests
- Before writing implementation code, list which specific test proves each item above and put the list in your report.
- Module tests in `modules/entity-resolution/src/test/scala`: hand-checkable fixtures (tiny candidate sets) covering each outcome flowing to the right output, transitive closure, self-link handling, determinism (run twice, equal output), idempotent output path, conflict never merging, UNRESOLVED never merging, and a conflict-inside-cluster integrity failure.
- Do not weaken or delete any existing test. Do not edit `tests/**` (test-engineer works from the specification in parallel). 
- Run `sbt check` yourself and report exact pass and fail counts. A written but unexecuted test is not evidence.

## Command for the human
Supply one exact, paste-ready command in this form (log outside `target/`, exit status preserved):
`! SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt "<runMain ...>" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-06-er-batch.log | tail -n 40`
Preserve the sbt exit status in the capture (for example by reporting the exit code line in the log through a reviewed script if a plain pipe loses it). Agents do not run this batch themselves unless it fits the bounded `sbt check` flow. Name the expected output directory and the expected summary lines.

## Boundaries
- Own only `modules/entity-resolution/**`. Anything needing `modules/core/**`, `modules/hadoop/**`, build files or `tests/**` is `BLOCKED-ON` with evidence and owner.
- Run Hadoop only if needed; do not alter Hadoop outputs. If the `MatchCandidate` copy in ER becomes relevant, report it rather than fixing it outside your module.
- No AWS, no network, no commit.

## Report
STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN (with exit codes), TEST RESULTS (exact counts), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED (the command above), BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Also include: the output schema and file layout of the accepted-link artifact, the canonical researcher ID rule, and the test-to-gate list.
