# Owner report: work order 03 (Spark collaboration pipeline)

Produced by: `spark-engineer`, received by the orchestrator 2026-11-07. Agent statements; the orchestrator has not independently run or diffed any of it. `modules/spark/**` is untracked, so no `git diff` against HEAD exists for it; the reviewer reads the files.

**STATUS:** COMPLETE for implementation and module tests. Real-data run not done (human-run). EMR run deferred by the human. No commit.

## Test-to-gate list (the agent states it was written before implementation code)
Tests in `modules/spark/src/test/scala/scholarchain/spark/`:
- Job 1 canonical builder: `CanonicalBuilderSuite` (resolved mentions share a node; unresolved mention stays its own node; merged work cluster yields one canonical work; unresolved-mentions table carries `unlinked` and `integrity-failure`; integrity violations reported with reason and counts) and `CollaborationPipelineSuite` "the integrity ceiling fails the run when exceeded".
- Job 2 and 3: `CoauthorshipSuite` (pair weights with merged duplicate counted once, self-pair dropped, canonical order, unresolved preserved, window boundary years, no-year works counted, bounded deterministic representative works; P/C/T/A/M/W hand-checked; `M_r` keeps every tied work; unresolved coauthor occurrences visible).
- Job 4, 6, 7, 8: `GraphAlgorithmsSuite` (components with singletons and per-window summaries; two cliques plus bridge; two-node component converges; iteration cap recorded; bridge measures and stated definitions; coreness on triangle with pendant, clique, path).
- Job 5: `PathsSuite` (shortest path with per-hop works, unreachable pair, hop bound, deterministic tie-break, sampled path length and effective diameter).
- Job 9: `TemporalSuite`. Job 10: `SummariesSuite` ("summaries cite evidence and invent no numbers").
- Job 11 and gates: `CollaborationPipelineSuite` (end-to-end tables and manifests; load manifests list SHA-256 per file and contain no AWS identifiers; two runs give the same logical output; every windowed table carries window columns; plans and stage metrics persisted and `DESIGN.md` states the DAG and shuffle; every output row carries core provenance), `DriverGuardSuite` (collect above the cap throws; no unguarded collect in main sources), `OutputPolicySuite` (repository and `target/` refused; missing input refused; strict arguments; window parsing).
- Gate "code review APPROVE": not a test; pending.

## Files
Created under `modules/spark/`: `DESIGN.md`; main `src/main/scala/scholarchain/spark/{Support,Config,Inputs,Canonical,Coauthorship,GraphAlgorithms,Temporal,Summaries,Export,CollaborationPipeline}.scala`; tests `{SparkTestSupport,SyntheticRun,Fixtures,GraphAlgorithmsSuite,PathsSuite,CanonicalBuilderSuite,CoauthorshipSuite,TemporalSuite,SummariesSuite,DriverGuardSuite,OutputPolicySuite,CollaborationPipelineSuite}.scala`. Modified: none (no `build.sbt`, no `tests/**`). No test weakened or deleted.

## Commands (agent-reported)
- `sbt Test/compile`: several runs, compile errors first, final run exit 0.
- `sbt check` four times: run 1 failed (Spark analyzer error, ambiguous column when two frames shared attribute IDs in a join); run 2 failed (Spark 4.0.5 `Dataset.checkpoint` threw `NoSuchElementException: key not found: hop#...` on a union); run 3 failed (3 pipeline tests: "unable to infer schema" on an empty partitioned table); run 4 exit 0, `[success]`, 276 s.
- The guard refused `wc`, `grep`, `unzip`.

## Test results (final `sbt check`, agent-reported)
sparkAnalytics `Passed: Total 44, Failed 0, Errors 0`; entityResolution 389/0/0; hadoop 207/0/0. Core and root totals not located by the agent (log at the agent's task output path); exit 0.

## Proven by the first Spark tests (agent-reported)
Scala 3.3.7 compiles against `spark-sql_2.13` 4.0.5; a local session starts on Java 21 with the shared flags; `Test / fork` works; module classpath resolves scala-library 2.13.16 and Hadoop 3.4.1. Not proven: `sparkAnalytics/runMain` finding the Provided classes (awaits the human run).

## Outputs
Under `<out-root>/run=<run id>/tables/<name>/` as Parquet, each row with a core `Provenance` struct.
- Windowed, partitioned by `window`: `edges-coauthored, edge-support, researcher-metrics, component-membership, components, component-summary, community-membership, communities, community-cross-edges, bridge, coreness, path-evidence, path-length-estimates, semantic-summaries`.
- Not windowed: `nodes-researcher, nodes-work, nodes-venue, nodes-topic, nodes-institution, edges-authored, edges-published-in, temporal-comparison, temporal-window-summary, unresolved-mentions, integrity-violations`.
- Empty windowed tables are written as one schema-only file (a partitioned write of no rows leaves nothing readable).
- Also `load-manifests/{neptune,dynamodb,s3-vectors,s3}.json` (planned tables, relative prefixes, row counts, schema, SHA-256 per file, `planned-not-uploaded`, no cloud identifier), `run-manifest.json`, `stage-metrics.json`, `plans/*.txt`, `run-metrics.json`, `summary.txt`, `_SUCCESS`. Column lists are in `DESIGN.md` and in each load manifest. Key tables: `researcher-metrics` (window, windowStart/End, researcherId, nodeKind, P/C/T/A/M/W columns, `maximumTeamWorkIds`, unresolved-coauthor columns, component, community, bridge columns, `coreness`), `edges-coauthored` (edgeId, researcherA/B, policyVersion, jointPublicationCount, first/lastCollaborationYear, representativeWorkIds, supportTable, endpoint-unresolved flags), `path-evidence`, `integrity-violations`.

## Algorithms (agent-stated)
- Components: min-label propagation; stops at a no-change pass or fails at `--max-iterations`.
- Communities: weighted label propagation, smallest-label tie-break, own label kept on a tie; only nodes with `pmod(hash(nodeId, salt), 2) = 0` adopt a proposal each pass; converged at the first pass with no differing proposal; defaults seed 42, cap 100; per-window convergence in the run manifest.
- Bridge: `crossCommunityDegree`, `crossCommunityStrength`, `distinctCommunitiesConnected` (other communities), `distinctNeighborCommunities`, `participationCoefficient = 1 - sum((s_ic/s_i)^2)` (0 with no coauthors); `bridgeScore` equals the participation coefficient. Limitations: community-method dependent, local measures only, no betweenness.
- Coreness: peeling for k = 1, 2, ...; nodes removed at level k get k-1.
- Paths: multi-source BFS by joins, smallest predecessor, default hop limit 6; pairs from `--path-pairs` (`window,from,to`) or by default all pairs among the top 4 researchers by weighted degree per window; sample = `--sample-size` (default 20) nodes with smallest `hash(nodeId, seed)` in the largest component, depth cap 64; effective diameter = smallest d with at least 90% of pairs within d (integer arithmetic).

## Unresolved mentions
Each unresolved mention is its own node, counted in `C` and `T` like any coauthor, with visible `unresolvedCoauthorOccurrences` and `distinctUnresolvedCoauthorCount`. The same person appearing as two unlinked mentions counts twice (known inflation). An unresolved mention in a non-representative duplicate record is not a node (listed in `unresolved-mentions` with `isNode = false`).

## Stage DAG, partitions, shuffle (agent-stated; full text in `modules/spark/DESIGN.md`)
Stages: canonical-builder, coauthorship-graph, connected-components, communities, bridge, coreness, researcher-metrics, path-evidence, path-length-estimates, temporal-comparison, semantic-summaries, export. `spark.sql.shuffle.partitions` = `--partitions` (default 8). Adaptive query execution off. `plans/` holds `explain("formatted")` per iterative step (first pass) and per exported table; `stage-metrics.json` holds per-stage tasks and shuffle read/write bytes; the integration test asserts shuffle bytes above zero and an Exchange in the components plan. Automatic broadcast disabled (`autoBroadcastJoinThreshold = -1`), `spark.buffer.pageSize` 1m (without it the local `-Xmx2g` run stalled in GC). Equi-joins are shuffle joins; the window join is a cartesian product filtered by year range. No skew salting (adaptive skew handling off).

## Checkpointing (deviation, for reviewer judgment)
Each pass writes the frame as Parquet to `<run>/_checkpoints/ckpt-<n>` and reads it back with its schema, truncating lineage; directory removed at the end; count in the manifest. Spark's own `Dataset.checkpoint` is not used because on 4.0.5 it threw `key not found` in `LogicalRDD.rewriteStatsAndConstraints` on a valid union.

## Hand-checked fixtures (agent-stated; expectations written by hand in the tests)
- Components: window `w`, nodes a..f, edges a-b, b-c, d-e gives {a,b,c}, {d,e}, {f}; 6 nodes, 3 edges, 3 components, largest fraction 0.5, 1 singleton. Window `v`, nodes x,y,z, edge x-y gives {x,y}, {z}, largest fraction 2/3.
- Coreness: triangle a,b,c plus pendant d plus isolated z gives 2,2,2,1,0; K4 gives 3 each; path p1..p4 gives 1 each.
- Communities: two K4 cliques joined by a4-b1 give two communities of 4 (6 internal edges each, 1 cross edge); single edge gives one community; cap 1 records `converged = false` after 1 pass.
- Bridge (bridge weight 2): a4 degree 4, strength 5, cross degree 1, cross strength 2, 1 other community, 2 neighbour communities, participation 1 - (9+4)/25 = 0.48; b1 the same; a1 degree 3, participation 0.
- Paths: line a-b-c-d-e plus f-g: a to e 4 hops, a to g unreachable, a to a trivial, a to zz out of window; hop limit 3 makes a to e unreachable and b to d found; diamond s-x, s-y, x-t, y-t gives s, x, t; P5 full sample: average 2.0, effective diameter 3 (18 of 20 pairs at d <= 3), max distance 4, 20 ordered pairs.
- Synthetic run (authors per work; D, X, U unresolved mentions): W1 2001 A,B,C; W2 2002 A,B; W2b (copy of W2) 2002 A,B,U; W3 2005 A,A,D; W4 no year A,B; W5 2000 B,E; W6 2004 A,X; W7 2006 A,F. Expected: window 2000-2004 edges A-B weight 2 (2001 to 2002), A-C, B-C, B-E, A-X; window 2005-2009 edges A-D, A-F; `all` has those seven. A in 2000-2004: P=3, C=3, T=4, A=4/3, M=2 (work W1 only), W=4, one unresolved occurrence. A in 2005-2009: M=1 with ties W3 and W7. A in `all`: P=5, C=5, T=6, A=1.2, M=2, W=6, two unresolved occurrences.
- Integration counts on the synthetic run: 7 canonical works, 1 without a year, 1 merged; pairs 10 mapped, 1 self-pair dropped, 3 from the duplicate record; 14 edges across windows; clean run 1 violation (orphan work link) and succeeds; defective run 5 violations and fails the 4-violation ceiling with exit 1.

## Assumptions
Authors of a canonical work are those of its representative record (anchor, else smallest member ID); works with no year are in no window, `all` included; window bounds inclusive; a pair candidate counts only from the representative record; windows may overlap; Spark SQL over views counts as DataFrame API; provenance source `derived`, snapshot `hadoop-snapshot:<id>`.

## Risks (agent-stated)
Real run unexercised (Hadoop `team-expansion` layout verified by `ls`: `author-papers`, `pair-candidates`, `teams`, each `part-r-00000..3` plus `_SUCCESS`; counts unobserved; a non-string map value in `institutions` fails fast under FAILFAST); community result seed-dependent and may hit the cap; many small jobs (about a minute on tiny data); the 896 mentions in ER integrity-failed clusters stay separate nodes; core `CoauthorEdge` cannot hold an unresolved-mention endpoint (such edges get a Spark-local `coauthor:v1:` ID, researcher-to-researcher edges use the core ID); `-Xmx2g` plus no broadcast needed for local stability; Hadoop 3.5.0 at root vs 3.4.1 at module level not exercised beyond tests.

## Manual action (as supplied; the orchestrator removed HTML escaping only)
```
! SBT_SERVER_FORCESTART=true SBT_SERVER_AUTOSTART=false sbt "sparkAnalytics/runMain scholarchain.spark.CollaborationPipeline --er-run /Users/winnguyen/scholarchain-data/phase6-out/er/run=run-v1-2f4cd132-afcb-4bca-83c5-1a0ddf001ace/snapshot=8e1be6626420 --hadoop-root /Users/winnguyen/scholarchain-data/phase4-out/subset-v6/run=run-v1-d4722bc7-0d02-42da-bdb6-088ee508a143/snapshot=8e1be6626420 --out-root /Users/winnguyen/scholarchain-data/phase6-out/spark --event-log-dir /Users/winnguyen/scholarchain-data/logs/spark-events" 2>&1 | tee /Users/winnguyen/scholarchain-data/logs/phase-06-spark-run.log | tail -n 40; echo "sbt-exit=${pipestatus[1]}" | tee -a /Users/winnguyen/scholarchain-data/logs/phase-06-spark-run.log
```
Failure prints `SPARK-PIPELINE RESULT: FAILED <reason>`: exit 2 for arguments, paths or missing inputs; exit 1 for a run failure or failed invariant.

## Blocked-on
None. Non-blocking note for domain-engineer: core `CoauthorEdge` cannot represent an unresolved-mention endpoint.

## Next gates
Independent review of `modules/spark/**` (including the checkpoint deviation and heap and broadcast settings); the human real-data run and its log; the human `sbt clean check`; re-review of repairs.
