# Phase 6 prompt: temporal coauthorship graph and Spark analytics

Drafted by the orchestrator, 2026-11-07. Phase 5 accepted at `befe9c4`. Nothing in this phase is committed by agents; the human accepts and commits after the gate passes.

## Part A. Course text (verbatim)

Source: Phase 6 section of the course README, `https://raw.githubusercontent.com/0x1DOCD00D/ScholarChain/main/README.md`, fetched by the human into `target/course-README.md` on 2026-11-07.

- Source revision: the raw URL carries no commit SHA, so none is recorded. Digest stands in as the revision.
- SHA-256 of the whole README: `6a0eddaddbb91435198ec868699ca99aec22ae8ee5ff515b6f7b34f18e362695`
- SHA-256 of the extracted section, `docs/agent-runs/phase-06/course-phase-06-section.md` (byte-exact, produced by the human with awk from `## Phase 6:` up to but excluding `## Phase 7:`): `b6af4043fc46f83bcc158fd0481bbe48a47498780cb28a66820e0e26b53312ec`
- If the text below and the extracted file differ, the extracted file is authoritative.

~~~~text
## Phase 6: build the temporal coauthorship graph and run Spark analytics

Purpose:

Convert resolved publication and researcher records into a temporal weighted collaboration graph and compute the measures required by the three research questions.

Why:

MapReduce prepares records and pair candidates. The main research problem, however, requires iterative graph algorithms, repeated aggregations, community computation, path analysis, and temporal comparison. Those operations are better suited to Spark's distributed execution model.

Use this work order:

```text
Use the spark-engineer agent to implement the ScholarChain Spark collaboration-analysis pipeline.

Required jobs:

1. Canonical researcher and authorship builder
- Read accepted researcher and work entity links plus Hadoop author-paper outputs.
- Construct canonical Researcher, Work, AUTHORED, Topic, Institution, and Venue tables.
- Preserve unresolved author mentions without forcing a merge.
- Validate that every canonical authorship edge points to existing nodes.

2. Temporal weighted coauthorship graph builder
- Map Hadoop pair candidates onto canonical researcher IDs.
- Drop self-pairs created by identity resolution.
- Canonicalize pair order so each logical undirected pair has one identity.
- Aggregate duplicate pairs.
- Store jointPublicationCount, firstCollaborationYear, lastCollaborationYear, and supporting work IDs or a stable pointer to them.
- Support configurable year windows.

3. Researcher collaboration breadth and team-size statistics
For each researcher and time window compute:
- publicationCount P_r
- distinctCoauthorCount C_r
- totalCoauthorOccurrences T_r
- averageCoauthorsPerPaper A_r
- maximumCoauthorsOnOnePaper M_r
- weightedDegree W_r
- the work IDs responsible for M_r
Keep every component visible. Do not replace these measures with one opaque collaboration score.

4. Connected components
- Compute connected components of the researcher coauthorship graph.
- Store component ID and component size.
- Report the fraction of researchers in the largest component and the number of singleton researchers when applicable.

5. Researcher connectivity and path support
- Provide a scalable method for shortest paths between selected researcher pairs.
- Materialize or cache bounded path evidence for benchmark queries.
- Where feasible, estimate component-level average path length or effective diameter using sampling or another documented scalable method.
- Never compute all-pairs shortest paths by collecting the graph on the driver.

6. Collaboration communities
- Compute a documented community method appropriate to the selected Spark stack, such as label propagation or another justified algorithm.
- Store community membership, community size, and cross-community edges.

7. Bridge analysis
- Compute at least one documented bridge measure such as exact or approximate betweenness centrality, cross-community degree, or participation coefficient.
- Record algorithm parameters and approximation strategy when exact computation is impractical.
- Report the number of distinct communities directly connected by each selected researcher when derivable.

8. K-core or equivalent dense-subgraph measure
- Compute coreness or a justified alternative that captures whether a researcher is embedded in a dense collaboration structure.
- Test against small hand-checkable graphs.

9. Temporal collaboration analysis
- Recompute selected metrics over configured time windows or yearly snapshots.
- Measure changes in distinct coauthors, team sizes, component membership, community membership, and bridge behavior.
- Do not compare windows with incompatible inclusion rules without stating the limitation.

10. Semantic-context preparation
- Produce researcher-profile summaries, community summaries, collaboration-path summaries, and selected paper or topic summaries for later embedding.
- Summaries must be generated from computed data and source records, not invented metrics.

11. Export
- Write partitioned Parquet node, edge, metric, community, component, path-evidence, and semantic-summary tables.
- Produce load manifests for Neptune, DynamoDB, S3 Vectors, and ordinary S3.

Operational requirements:
- Explain partitioning and shuffle boundaries.
- Avoid collecting the full graph on the driver.
- Configure checkpointing where iterative algorithms require it.
- Emit counts and invariant failures.
- Make every run reproducible from a run manifest.

Run sbt check, a local or small-cluster Spark integration test, and a bounded EMR Spark run.
Report stage DAGs, partition counts, shuffle metrics, graph counts, component counts, community counts, metric distributions, path-test results, temporal windows, rejected integrity violations, and output paths.
```

Gate:

- Spark performs real distributed transformations and graph analytics.
- No complete graph is collected to the driver.
- Distinct coauthor counts and pair weights pass small known-graph tests.
- Connected components, community assignment behavior, path queries, and k-core or equivalent tests are demonstrated on hand-checkable fixtures.
- Every bridge measure is defined and its limitations are stated.
- Time windows are explicit in outputs.
- Partition and shuffle behavior are documented.
- Code review returns APPROVE.
~~~~

## Part B. Scope note (human instruction, not course text)

- **Local Spark only.** No AWS, EMR, Terraform, deploys or live operations.
- **Deferred:** the README's "bounded EMR Spark run" is DEFERRED. The gate uses a bounded local Spark integration run instead. Deferral is a human decision of 2026-11-07 and does not claim the EMR requirement is met.
- **Load manifests** for Neptune, DynamoDB, S3 Vectors and ordinary S3 are generated as local artifacts only. Nothing is loaded anywhere.
- **Logs** go under `/Users/winnguyen/scholarchain-data/logs/`, never `target/`.
- **Inputs.** Verify the exact Phase 5 graph-export and ER input paths under `/Users/winnguyen/scholarchain-data/` first. The graph-export lead is the recorded subset-v6 run. Gold labels and candidate pairs are evaluation or matching inputs and must never serve as accepted links.
- **No accepted-link artifact means ER first.** An ER batch run over all match-candidates goes to `entity-resolution-engineer` before any Spark work, with its own tests and a command the human runs. Any other missing required artifact is recorded as BLOCKED-ON and routed to its owner.
- **Spark module** is `sparkAnalytics`, at `modules/spark` (verified in `build.sbt`), owned by `spark-engineer`, including its module tests.
- **Routing.** Independent fixtures in `tests/**` go to `test-engineer`. Build changes, including Spark, Scala 3 and Java 21 compatibility, go to `build-engineer`. Core contract changes go to `domain-engineer`. The N1 XmlFragment stack overflow or Crossref normalizer goes to `hadoop-engineer` only if Phase 6 touches the affected Hadoop path. The `MatchCandidate` copy in ER goes to `entity-resolution-engineer` only if Phase 6 touches it.
- **Constitutional changes** (`CLAUDE.md`, `docs/agents.md`, `.claude/**`): the human switches to manual mode first, and the work goes exclusively to `factory-engineer`.
- **Rules for agents.** Do not weaken or delete tests. Agents may run `sbt check` and `sbt Test/compile`. The human runs `sbt clean check` and the Spark integration run. Agents do not commit.
- **Records.** Owner reports, commands, exit codes, log paths, results, pending checks, review verdicts and repair history live in `docs/agent-runs/phase-06/`, each item naming its producer.
- **Close.** A fresh, read-only `code-reviewer` (Opus) reviews the gate with the scoped diff, course criteria, local-run evidence and hand-checkable fixture results (distinct coauthors, pair weights, components, communities, paths, coreness or equivalent). Findings are routed to owners, revalidated and re-reviewed. The final verdict is recorded, then work stops for the human's acceptance after APPROVE.
