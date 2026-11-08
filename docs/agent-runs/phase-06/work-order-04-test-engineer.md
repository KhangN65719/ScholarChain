# Work order 04: independent Spark fixtures and adversarial tests (`tests/**`)

Issued by: orchestrator, 2026-11-07. Owner: `test-engineer`. Normative text: `docs/agent-runs/phase-06/prompt.md` Part A (course Phase 6 jobs and gate) and Part B. Local only; no AWS, no network, no commit.

## Why independent
`spark-engineer` wrote 44 module tests in `modules/spark/src/test/scala/scholarchain/spark/` and a pipeline that already ran on the real subset (`validation.md` V3). Your tests are the independent check: expected values come from the course specification and your own hand computation on tiny graphs, NOT from running the implementation and copying its output. Where the implementation and your hand computation disagree, report the disagreement as a finding for the owner; never adjust an expectation to match the output without showing the hand derivation.

Read for interface facts only: `docs/agent-runs/phase-06/spark-owner-report.md` (table names, columns, algorithm definitions, CLI), `modules/spark/DESIGN.md`, `modules/spark/src/main/scala/scholarchain/spark/**`, the ER batch output layout in `er-batch-owner-report.md`, and `modules/core` contracts. Do not copy module test code or fixtures; do not edit `modules/**`.

## Placement and build rules (from `build-engineer-report.md`)
- Tests live under `tests/**` (existing roots: `tests/contracts`, `tests/adversarial`, `tests/integration`, `tests/end-to-end`). The root project has `sparkAnalytics % "test->compile"`.
- A Spark-using root suite MUST have `Spark` in its class name (or a `.spark.` package) so the build's `testGrouping` runs it in the forked JVM with the `--add-opens` flags; otherwise it fails for a reason unrelated to the code. Suites that start no Spark (for example class-byte scans) stay in-process and need no such name.
- Shared fixtures are your own: write the input files in the real ER-batch and Hadoop output layouts (JSON lines, `part-*` files) into a temp directory from your own helper. Each end-to-end pipeline run takes roughly a minute under Spark, so batch many hand-checkable cases into a few runs per suite (one multi-window fixture run can carry many assertions). Keep the added `sbt check` wall time modest and report it.
- Existing tests, including `AdversarialLeakageSuite`, must keep passing. Do not weaken or delete any test. If an existing independent test asserts something Phase 6 legitimately changes (for example module directory shape), report it rather than editing the assertion silently.

## Required coverage (each as a named test; expected values derived by hand and written out in comments or fixtures)
1. **Distinct coauthors and pair weights.** A graph where one researcher publishes many papers with the same three coauthors and another publishes few papers with many coauthors (the course's breadth versus count example): assert `C_r`, `P_r`, `T_r`, `A_r`, `M_r`, `W_r`, per window and for `all`; ties in `M_r` list every work; a merged work cluster (two records of one canonical work) counts once; a pair given in both orders collapses to one undirected edge with the right `jointPublicationCount`, `firstCollaborationYear`, `lastCollaborationYear`.
2. **Self-pairs and identity.** Two mentions of one canonical researcher in one work produce no edge; an unresolved mention (null `canonicalResearcherId`, both `unlinked` and `integrity-failure`) stays a separate node and is never merged with a same-named canonical researcher; an ORCID-bearing mention in an integrity-failed cluster is not merged.
3. **Components.** Known small graphs: path, triangle, two cliques, isolated singletons; assert membership, sizes, largest-component fraction, singleton count, per window.
4. **Communities.** Two cliques joined by one bridge edge, and a ring or path where several valid labelings exist: assert only what is invariant (membership partition properties, community sizes where forced, cross-community edge count, determinism across two runs, stop-at-cap recorded), not an arbitrary labeling.
5. **Bridge measures.** On two cliques plus a bridge, derive `crossCommunityDegree`, `distinctCommunitiesConnected` and `participationCoefficient` by hand from the documented formulas in `DESIGN.md` and assert them. Also assert that the module states the limitations of each measure.
6. **Shortest paths.** Hand-checkable graphs: a unique shortest path with per-hop supporting work IDs, two equal-length paths (assert length and that the chosen path is deterministic across runs and is one of the valid shortest), an unreachable pair, a pair beyond the hop bound, source equals target, a pair outside the window or not in the graph.
7. **Coreness.** Triangle plus pendant plus isolated node, K4, path, and a case where peeling needs several rounds (for example a node whose coreness drops because its neighbor is peeled first); derive by hand.
8. **Time windows.** Boundary years inclusive, works with no year (excluded and counted), a researcher present in one window only, overlapping and `all` windows, explicit window columns on every windowed table, and the stated inclusion rules in the temporal comparison.
9. **Giant team and degenerate inputs.** A work with many authors (pair expansion n(n-1)/2 mapped correctly), a work with one author (no pair), an empty window, empty accepted-links, duplicate IDs, a pair candidate whose endpoint is missing (recorded as an integrity violation or counted), reversed pair order, a missing input directory (refused with exit code 2 semantics).
10. **No full driver collect.** An independent scan of the main class bytes or sources for collect-like calls outside the single guarded helper, in the style of the existing leakage scans, plus a runtime check that the guard rejects a result above its cap.
11. **No label or decision leakage.** Class-byte scan: `scholarchain.spark` main classes reference no `gold` path, no ER `decisions` tree and no `scholarchain.er.eval`, `review` or `sampling` package.
12. **Output policy.** Output inside the repository or under `target/` is refused; strict argument parsing; no AWS identifier or bucket name in load manifests; every load manifest carries SHA-256 per file and `planned-not-uploaded`.
13. **Provenance and determinism.** Every output row carries provenance; two runs over identical inputs produce identical logical tables (use order-insensitive comparison); changed input changes the run ID.
14. **Summaries.** Semantic summary text contains no number that is not in the evidence columns it cites.

## Execution rules
- Before writing tests, list which specific test proves each numbered item. Put that list at the start of your report.
- A written but unexecuted test is not evidence. Run `sbt Test/compile` and `sbt check` yourself and report exact pass and fail counts per module (core, entityResolution, hadoop, sparkAnalytics, root) and the added wall time. Report each failure verbatim with its hand derivation; do not "fix" production code and do not edit `modules/**`; route production findings to the owner (spark-engineer for `modules/spark/**`, domain-engineer for core, build-engineer for build).
- No other sbt invocation.

## Report
STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN (exit codes), TEST RESULTS (exact counts), VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. Add: the numbered test-to-item list, the hand-derived fixtures (graph, window, expected values), every disagreement between your derivation and the implementation, and what you could not test.
