# Work order: entity-resolution-engineer, pairs export and labeling tool (Phase 5, step 4)

Orchestrator to `entity-resolution-engineer`. Owns modules/entity-resolution/** only. Human direction below is quoted intent.

## Pairs export (blind, stratified)
Export a blind, stratified sample of about 230 candidate pairs to a pairs file with evidence only. Roughly one third exact-ID, one third clear non-matches, one third hard common-name cases.
- Input: Phase 4 `MatchCandidate` output (core type) plus the records it references. The real candidate output does not exist yet (subset extraction and the Phase 4 run are pending); build and test the exporter on fixtures, and give the exact command to run on the real output later.
- Strata are assigned from raw candidate and record features ONLY (shared exact identifiers; weak or name-only block with clearly different names or contexts; same normalized name with ambiguous or conflicting context). Document the rules. Do not use matcher scores or decisions to pick or order pairs, and do not put matcher output in the pairs file (blind). Deterministic: fixed seed, stable ordering, reproducible from the same input. Report the count per stratum and say plainly if a stratum has fewer than its target.
- The pairs file contains per pair: pair_id, both records' evidence (names, title, year, venue, authors/coauthors, affiliations, identifiers, source record IDs and snapshot), strongest evidence first. It must not contain stratum names, scores, decisions or reason codes. Strata live in a separate private key file (pair_id to stratum), not read by the labeling tool.
- Every pair traces to source records (source record IDs, dataset snapshot, run ID).
- Print the pairs file path when run.

## Local keyboard labeling tool
- One pair per screen, strongest evidence first (exact identifiers, then names/coauthors/venue/year/affiliation).
- Optionally imports an external suggestion CSV with header `pair_id,suggested_label,reason`. Suggestions are hidden by default (a key reveals them); when shown, Enter accepts the suggestion. Keys: Enter = accept suggestion, 1 = same, 2 = different, 3 = unsure (these override). Optional free-text note. Back/skip navigation. Reject malformed CSV rows with a clear message; unknown pair_ids are reported, not silently dropped.
- Autosaves after every label (atomic write, never corrupts an existing gold file); resumable (reopening continues at the first unlabeled pair; already-labeled pairs can be revised).
- The gold-set file records per pair: pair_id, the human's final label, the suggestion (label and reason, if any), the suggestion source (CSV file name and its SHA-256), whether the human changed it from the suggestion, optional note, reviewer, review timestamp, review method "human", and the pair's source record IDs and snapshot. The tool reports the change count (labels that differ from the suggestion) and totals by label.
- Must produce gold labels the existing `eval.GoldLabel` constructor accepts (human review method, reviewer, timestamp, source IDs, snapshot); reuse it. Tell me how the reviewer name is supplied.
- Implementation choice is yours, with these constraints: no new dependencies (the version ledger is closed; BLOCKED-ON dependency-updater if you think one is needed), runs locally with no network and no credentials. Recommendation: a tiny localhost web page served by the JDK's built-in `com.sun.net.httpserver`, single-key shortcuts in the page, writing the gold file server-side; a terminal raw-mode UI is acceptable if you judge it more reliable. State the tradeoff, how the human launches it (exact command), and if binding a port is needed (the sandbox may block local binding; the human will run it with the `!` prefix).

## Leakage rules (hard)
- Gold labels and suggestions must never feed matching logic. Put the labeling tool and the exporter in packages separate from `matching` and `llm`; matching code must not import them. The existing LeakageSuite scans `matching` and `llm`; extend your own checks so the new packages are not referenced from there, and tell test-engineer which new package names exist so it can cover them.
- The tool must not display matcher output.

## Process
- Before writing code, list which specific test proves each gate item (blind file has no scores/strata; deterministic sampling; stratum counts; suggestions hidden by default; Enter accepts only when a suggestion exists; overrides; change count; atomic autosave and resume; malformed CSV; GoldLabel validity).
- Do not edit tests/** (test-engineer works there). Run `sbt check` (exact text) and report exact counts, saying plainly what truncated.
- No LLM call, no credentials.

## Report
Required contract plus the gate-item-to-test list, the pairs file schema, stratum rules, the launch commands, and what is untested.
