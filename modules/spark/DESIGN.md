# Spark collaboration-analysis pipeline (`sparkAnalytics`)

Owner: `spark-engineer`. Entry point: `scholarchain.spark.CollaborationPipeline`.
Local Spark only: nothing here reads the network, calls a model or writes outside the
run directory. The EMR run of the course text is deferred by the human (Phase 6 scope
note), so no claim about cluster behaviour is made.

## Inputs and identity

- Accepted ER links, `accepted-links/v1/{researcher-links,work-links,canonical-researchers}`
  and `manifest.json` of one ER snapshot directory. Nothing under `decisions/` or `gold/`
  is read: only accepted links define identity.
- Hadoop exports of one snapshot directory: `stage=graph-export/{works,author-mentions,institutions,topics,venues}/v1`
  and `stage=team-expansion/{author-papers,pair-candidates,teams}/v1`.
- All inputs are JSON lines read with explicit DDL schemas and `FAILFAST`, so a malformed
  row fails the run. The run ID is derived from the digests of every input directory, the ER
  manifest, the configuration, the transform version and the SHA-256 digest of the module's main
  sources (`RunContext.seed`), so the same inputs under the same code give the same ID and a
  changed source file gives a new one. `transformVersion` (`spark-analytics/1.1.0`) is bumped by
  hand only when the output contract (table names, columns, meaning) changes; it does not track
  every code change, the sources digest does. Outside a git checkout the digest is `unavailable`,
  and then the run ID does not change with code: only the transform version separates code
  versions. The manifest entry `code.inRunIdSeed` is true only when the digest is available; when
  it is `unavailable` it is false and `code.runIdTracksCode` says so.

Identity rules (job 1, `Canonical`):

- A repeated input row is one fact. Before any join, researcher links (key `mentionId`),
  work links and works (key `workId`), canonical researchers and mentions are reduced to one
  row per key (`Canonical.distinctByKey`; rows that share a key but differ keep the smallest
  row by struct order, so the choice is deterministic). Author-papers are distinct by
  (mention, work, position) and pair candidates are distinct by (a, b, work) downstream. A
  repeated works row is therefore not a merge of two records and a repeated link row makes no
  second AUTHORED edge. `input.*` counts in the run manifest are raw row counts. Per input
  dataset (researcherLinks, workLinks, canonicalResearchers, works, mentions, authorPapers,
  pairCandidates) the run also counts `input.duplicateKeyRows.<dataset>` (rows beyond the first of
  a key) and `input.conflictingDuplicateKeys.<dataset>` (keys whose rows differ, so one row was
  chosen). Exact repeats are only counted. Conflicting keys are never integrity violations (the
  violation table and its ceiling describe link and work breakage), and the severity depends on
  who owns the input:
  - `researcherLinks`, `workLinks` and `canonicalResearchers` are accepted-link inputs. Entity
    resolution owns identity, so one key with differing rows (above all two different
    `canonicalResearcherId` values) is the error invariant `conflictingIdentityInputKeys`: Spark
    must not pick an identity. The run still writes its tables and manifest, then ends with
    `FAILED invariants conflictingIdentityInputKeys` and exit code 1, without `_SUCCESS`.
  - `works`, `mentions`, `authorPapers` and `pairCandidates` are Hadoop inputs. A conflict there
    has a deterministic rule (smallest row by struct order) and decides no identity, so it is the
    warning invariant `conflictingDuplicateInputKeys`; the run succeeds.
  Both are in the manifest and `summary.txt` and name the datasets and counts in their detail.

- Researcher node ID is `canonicalResearcherId` when the link has one. A mention whose
  canonical ID is null (`unlinked` or `integrity-failure`, or no link row) is its own node,
  `nodeKind = unresolved-mention`, identity = the mention ID. It is never merged by name or by
  anything else.
- Work nodes are canonical works (`canonicalWorkId`). The authors of a canonical work are
  those of its representative record (the anchor record, else the smallest member ID).
  A duplicate record (a DBLP record and its Crossref or OpenAlex copy) therefore adds nothing
  to any publication count, pair weight or team size. Its resolved mentions stay in AUTHORED
  with `countedInMetrics = false`; its unresolved mentions are not nodes and appear in
  `unresolved-mentions` with `isNode = false`.
- Year of a canonical work: the representative record's year, else the smallest year of the
  member records, else none. A work with no year is in no window (the all-years window
  included) and is counted (`canonical.worksWithoutYear`).

## Windows

`--windows` is a comma list of `<start>-<end>` and `all` (all = years 1 to 9999). The default
is `1900-1999,2000-2004,2005-2009,2010-2014,2015-2019,2020-2029,all`. A work belongs to every
window that contains its year, bounds inclusive. Every windowed output row carries `window`
(label), `windowStart` and `windowEnd`, and windowed tables are partitioned by `window`.

## Stage DAG

Each box is one labelled stage (`Stages.run`), recorded in `stage-metrics.json` under that
label. Boundaries marked `shuffle` are Exchange nodes in `plans/*.txt`.

```
inputs (JSON, explicit schema)
  |
  v
[canonical-builder]  works members: shuffle (join works x work-links)
  |   canonical works: shuffle (groupBy canonicalWorkId)
  |   mention base: shuffle (author-papers x links x members x works x canonical, persisted)
  |   AUTHORED, researcher nodes, unresolved mentions, violations, venues, topics, institutions
  v
[coauthorship-graph]  pair dispositions: shuffle (pairs x base, twice)
  |   distinct (a, b, work): shuffle (distinct)
  |   windowed pair works: join with the windows table (non-equi, tiny side)
  |   edges: shuffle (groupBy window, a, b); adjacency: union of both directions (persisted)
  |   per researcher metrics: shuffle (groupBy window, node), join with degree (groupBy window, node)
  v
[connected-components]  repeat: join adjacency x labels, shuffle (groupBy window, dst), checkpoint
  v
[communities]  repeat: votes (join, groupBy window, dst, label), tops, best, proposal, checkpoint
  v
[bridge]  join adjacency x community x community, two groupBys
  v
[coreness]  repeat: join adjacency x alive x alive, groupBy window, src, checkpoint
  v
[researcher-metrics]  join metrics x components x bridge x communities x coreness (persisted)
  v
[path-evidence]  repeat per level: join frontier x adjacency x visited, groupBy, checkpoint; then a walk back
[path-length-estimates]  same BFS from the sampled sources, then a histogram per window
  v
[temporal-comparison]  full outer join of two window slices, overlap groupBys
  v
[semantic-summaries]  joins of metrics, researchers, communities, paths; template columns
  v
[export:<table>]  persist, count, repartition(window | 1), sortWithinPartitions, Parquet write
```

## Partitioning, shuffles and joins

- Shuffle partitions: `--partitions` (default 8) sets `spark.sql.shuffle.partitions`. Adaptive
  query execution is off, so the partition count of a stage is the configured number and a
  plan in `plans/` is the executed plan, not an initial one.
- Keys: node tables and edges are keyed by `(window, nodeId)` or `(window, researcherA, researcherB)`;
  BFS is keyed by `(pairKey, nodeId)`; works by `canonicalWorkId`. Putting `window` in every key
  keeps windows independent, so all windows run in the same iteration.
- Broadcast: automatic broadcast joins are off (`spark.sql.autoBroadcastJoinThreshold = -1`) and
  `spark.buffer.pageSize` is 1m, because with the 2g driver heap of a local run each concurrent
  hash-join build and broadcast asked for a 32 MB page and the run stalled in garbage collection.
  Every equi-join is therefore a shuffle join (an Exchange in the plans). The join with the windows
  table (a handful of rows, no equality key) is a cartesian product filtered by the year range.
  Raise the threshold, or hint a broadcast, on a cluster where executors have the memory.
- Skew: a hub researcher makes the group of one `(window, node)` key large in the
  aggregates. There is no salting, and adaptive skew handling is off. The local data (max degree
  in the hundreds) does not need it; a cluster run should enable adaptive execution first.
- Export: a windowed table is `repartition(window)` then `sortWithinPartitions(keys)` and written
  with `partitionBy(window)`; other tables are `repartition(1)` (one file) because they are small.
  Both repartitions are shuffles and appear in the table plans. After the write every part file is
  renamed to `part-NNNNN.parquet` (position in task order), because Spark's own names carry a random
  write UUID: the names then repeat between runs and no manifest path holds a random token.
- Evidence of what ran: `plans/<stage>.txt` holds `explain("formatted")` of each iterative step
  (taken at the first pass) and of every exported table. `stage-metrics.json` holds, per stage,
  the task count, shuffle read and write bytes, and input bytes and records, from a
  `SparkListener`, plus totals and per stage label totals.

## Checkpointing and iteration

Components, communities, coreness and BFS checkpoint every pass with `Algo.checkpoint`: the frame
is written as Parquet to `<run>/_checkpoints/ckpt-<n>` (an eager action) and read back with its
schema, so the returned frame is one file scan and no lineage grows with the iteration count. The
directory is removed at the end of a successful run and the number of checkpoints taken is in the
run manifest. The scratch directory `<run>/_work` (warehouse and Spark local files of a session the
run created) follows one rule: deleted after a successful run (after the run stops its own
session), kept after a failure, including a failed invariant, for diagnosis.

Spark's own `Dataset.checkpoint` was tried first and is not used: on Spark 4.0.5 it threw
`NoSuchElementException: key not found: hop#...` in `LogicalRDD.rewriteStatsAndConstraints` when
checkpointing a union (the path walk-back), which is a failure on a valid plan. The manual
checkpoint has the same effect (truncated lineage, files under the run output) without that code path.
Every SQL input is also re-aliased (`toDF`) so frames that share attribute IDs never meet in one
join.

## Algorithms

- **Connected components** (job 4): min-label propagation. Labels start as node IDs; a pass sets
  each label to the minimum over the node and its neighbours; a pass that changes nothing ends it.
  Labels only decrease, so it terminates, after at most the longest shortest path plus one pass.
  The component ID is the smallest node ID in the component, window-local. Reaching the iteration
  cap fails the run. Outputs: membership, components (`componentSize`, `isLargest`) and a window
  summary (`largestComponentFraction`, `singletonCount`).
- **Communities** (job 6): weighted label propagation (weight = joint publication count). Proposal =
  the neighbour label with the largest total weight; smallest label on a tie; own label if it is
  among the largest. Only nodes with `pmod(hash(nodeId, salt), 2) = 0` adopt their proposal in a
  pass (salt from `--seed` and the pass number), which stops adjacent nodes swapping labels for
  ever. A window is converged at the first pass in which no proposal differs from the labels, a
  true fixpoint. The run stops when all windows converged or at `--max-iterations`; per window
  `passesToFixpoint` and `converged` are in the run manifest. Not converged means the labels are
  still a valid partition, only not a fixpoint. The result depends on the seed.
- **Bridge** (job 7): see `Bridge` for each formula: `crossCommunityDegree`,
  `crossCommunityStrength`, `distinctCommunitiesConnected` (other communities only),
  `distinctNeighborCommunities` (own included), `participationCoefficient`
  = 1 - sum_c (s_ic / s_i)^2 with s the weighted degree, 0 without coauthors, `bridgeScore` = the
  participation coefficient. Limitations: every measure depends on the community method and its
  partition; they are local measures; there is no betweenness centrality; and the graph can be
  fragmented (see the fragmentation limit below), which makes every score describe a small group.
- **Coreness** (job 8): iterative peeling. For k = 1, 2, ...: remove every node with fewer than k
  surviving neighbours, all at once, until a pass removes none; nodes removed at level k get
  coreness k - 1. It terminates because each pass removes a node or ends its level and k grows only
  while nodes remain.
- **Paths** (job 5): multi-source BFS by joins, keyed by `(pairKey, node)`, with hop distance and the
  smallest predecessor ID per node, so the path is deterministic: walk back from the target through
  the stored predecessors. A pair stops expanding when its target is reached; the search stops at
  `--hop-limit` or an empty frontier. `path-evidence` has one row per hop (`hopIndex`, `fromId`,
  `toId`, `edgeId`, `jointPublicationCount`, `supportingWorkIds`) and one row with a `status` for a
  pair that is `trivial`, `endpoint-not-in-window` or `unreachable-within-bound`. Pairs come from
  `--path-pairs` (lines `window,from,to`) or, without it, all pairs among the top `--auto-pair-top`
  researchers by weighted degree of each window. `unreachable-within-bound` has two causes, named
  in the column `unreachableReason` (null for every other status): `different-component` (the two
  nodes are in different components of the window graph, so no path exists at any limit) and
  `beyond-hop-limit` (same component, but no path within `--hop-limit`). The text of the path
  summary says which. The status value itself is unchanged.
- **Fragmentation limit** (applies to paths, bridge scores and path-length estimates): the
  coauthorship graph can be split into many small components. A path exists only inside one
  component, a bridge score sees only the neighbours inside its own component, and the sampled
  path length covers only the largest component. When that component holds a small share of the
  nodes, most pairs have `different-component` and the estimates describe a small part of the
  graph. Read `component-summary` (`largestComponentFraction`, `componentCount`,
  `singletonCount`) beside any path or bridge result. On the subset run reviewed in Phase 6
  (run `312ea79b-637c-440b-85f7-5c0640c3609c`, the observed run for these figures) the largest
  component held 148 of 17,490 nodes (0.85 percent) in 3,035 components. The text is in the run manifest under
  `algorithms.paths.limitations` and `algorithms.bridge.limitations`.
- **Sampled path length and effective diameter**: per window, the largest component; the
  `--sample-size` nodes with the smallest `hash(nodeId, seed)` (ties by ID) are BFS sources (depth
  cap 64). Average = mean distance over (source, other node) pairs. Effective diameter = the smallest
  distance d with at least 90 percent of those pairs within d hops (integer arithmetic). When the
  sample covers the component (`allNodesSampled`) the values are exact for it. Limitations: only the
  largest component is measured; pairs beyond the depth cap are counted in
  `unreachedPairsDueToDepthCap` and left out.

## Metrics (job 3) and unresolved mentions

For researcher r in window w, over the canonical works of r in w, each once:
`P` publicationCount; `team(x)` = distinct author nodes of work x; `T` totalCoauthorOccurrences =
sum of (team(x) - 1); `A` = T / P; `M` = max of (team(x) - 1) with every tied work in
`maximumTeamWorkIds`; `C` distinctCoauthorCount = distinct coauthor nodes (graph degree);
`W` weightedDegree = sum of the joint publication counts of the incident edges.

Unresolved mentions enter every measure as their own nodes. An unlinked coauthor adds one to `C`
and one to `T` exactly like a resolved one, and the same real person appearing as two unlinked
mentions counts twice. That inflation is a limitation. It is visible, not hidden:
`unresolvedCoauthorOccurrences` and `distinctUnresolvedCoauthorCount` hold the unresolved part of
`T` and `C`. Two mentions of one canonical researcher in one work count once in `team(x)` and their
pair is dropped (`pairs.self-pair`). W equals T when the Hadoop pair candidates cover every pair of
every team; a difference is the warning `weightedDegreeNotEqualToCoauthorOccurrences`.

Known limitation of the ER input, not repaired here: 896 mentions in integrity-failed clusters have
a null canonical ID although some carry an ORCID, so they are separate nodes.

## Temporal analysis (job 9)

Consecutive windows are the configured windows in order, `all` excluded. See `Temporal` for the
inclusion rules: a change column is filled only for a researcher present in both windows; component
and community membership change is the Jaccard overlap of member sets (IDs are window-local);
windows may differ in length (`sameWindowLength`); unresolved mentions are window-local nodes.

## Summaries (job 10)

Fixed templates filled from computed columns (`Summaries`): researcher profile, community,
collaboration path and the `--paper-summaries` works with the largest teams. Each row lists
`evidenceWorkIds`, `evidenceMetrics` and `evidenceNodeIds`, the window, `nodeKind` and
provenance. Every number in a text is a column named in that row's `evidenceMetrics`, a column of
the table the summary describes: `researcher-metrics` for a profile (it cites
`unresolvedCoauthorOccurrences` for the unresolved count it states), `communities` and the member
`weightedDegree` for a community, `path-evidence` (`pathLength`, `jointPublicationCount`) for a
path, and the work and team columns `teamSize`, `unresolvedAuthors` and `year` for a work. An
unreachable-path text states no hop number: it says "within the configured hop limit", or that
the two researchers are in different components, and the limit is `hopLimit` in `path-evidence`
and the run manifest. No topic summary exists because the Hadoop exports hold no work to topic
link.

Unresolved mentions in summaries. A profile of an unresolved mention begins "Unresolved author
mention", not "Researcher", and says that no accepted link joins it to a canonical researcher.
Component and community sizes count every node, so a text says "nodes" and states that nodes are
canonical researchers and unresolved author mentions. `nodeKind` (`canonical`,
`unresolved-mention`, null for community, path and work summaries) is a metadata column of the
`s3-vectors` manifest so a retrieval filter can separate them. A profile whose canonical
researcher shares its ORCID with another canonical researcher (a split person, derivable from
`nodes-researcher.orcid`) carries an uncertainty sentence: the profile may cover only part of one
person's record. Splits that share no ORCID are not derivable here and carry no marker.

## Driver policy

Only configuration, the selected path pairs, aggregate counts and bounded samples reach the driver,
through `DriverGuard.rows`, which refuses a result over `--driver-row-cap` (default 5000). A source
scan test forbids every other collect, take, head, first, tail and show in the main sources.

## Output (`<out-root>/run=<run id>/`)

`tables/<name>/` Parquet (windowed tables `window=<label>/`), `load-manifests/{neptune,dynamodb,s3-vectors,s3}.json`
(planned tables, relative prefixes, row counts, schemas, SHA-256 per file; nothing uploaded and no
cloud identifier), `run-manifest.json` (inputs and digests, ER and Hadoop run IDs, snapshot,
configuration, windows, algorithm parameters and convergence, counts, invariants, versions, git head,
sources digest, outputs), `stage-metrics.json`, `plans/`, `run-metrics.json` (wall time only),
`summary.txt`, `_SUCCESS`.

Tables: nodes-researcher, nodes-work, nodes-venue, nodes-topic, nodes-institution, edges-authored,
edges-published-in, edges-coauthored, edge-support, researcher-metrics, component-membership,
components, component-summary, community-membership, communities, community-cross-edges, bridge,
coreness, path-evidence, path-length-estimates, temporal-comparison, temporal-window-summary,
semantic-summaries, unresolved-mentions, integrity-violations. Every row has `provenance` with the core
`Provenance` fields (source `derived`, snapshot `hadoop-snapshot:<id>`, record key, `scholarchain:spark-analytics/<table>`,
`spark-analytics/1.1.0`, run ID).

The core contracts `CoauthorEdge`, `GraphEdge` (COAUTHORED_WITH) and `ResearcherMetrics` accept
an unresolved author mention (`mention:v1:<hex>`) as an endpoint. `edgeId` is always the core
`StableIds.coauthorEdgeFor` value: `coauthor:v1:<hex>` between two canonical researchers and
`mention-coauthor:v1:<hex>` when either endpoint is a mention, so every `edges-coauthored` row maps
to a core `CoauthorEdge` (the `CoauthorshipSuite` round-trip test builds one for each row). Spark
holds no local ID form; a malformed endpoint ID fails the run.

Spark-local columns with no core form: `nodeKind` (`canonical` or `unresolved-mention`),
`unresolvedCoauthorOccurrences`, `distinctUnresolvedCoauthorCount`, and the edge flags
`endpointAUnresolved` and `endpointBUnresolved`. Core `ResearcherMetrics` carries none of them.

Version. `spark-analytics/1.1.0` is the first transform version that emits the core
`mention-coauthor:v1:` edge IDs. Runs with `transformVersion` `spark-analytics/1.0.0` carry legacy
Spark-local `coauthor:v1:` IDs on mention-endpoint edges. A minor bump was chosen: columns and
tables are unchanged, only the value of one ID column changed for mention edges. A loader can
separate the two generations by the version in `provenance.transformVersion`.

Run compatibility. Runs on disk written before this change (Phase 6, including run
`312ea79b-637c-440b-85f7-5c0640c3609c`) carry the old Spark-local `coauthor:v1:` ID on every
mention-endpoint edge, and `path-evidence.edgeId` and `edge-support.edgeId` repeat it. Those IDs do
not validate as core `CoauthorEdge` IDs. Phase 7 loaders must read only runs produced after this
change. The change alters the main sources, so the sources digest and therefore the run ID differ
for any new run, and `transformVersion` is now 1.1.0, so a loader can also reject runs by version.
The real-data pipeline was not rerun here; a rerun is recommended before any load.

## Failure and invariants

The entry point prints summary lines and ends with `SPARK-PIPELINE RESULT: SUCCESS`, or
`FAILED <reason>` with exit code 2 for arguments, paths and missing inputs, 1 for a run failure or a
failed invariant. Invariant errors: integrity violations above `--max-integrity-violations`, AUTHORED edges
without a researcher or work node, edges out of canonical order or outside their window, nodes without
component, community or coreness, and `conflictingIdentityInputKeys`. Warnings are recorded without
failing the run: weighted degree differing from coauthor occurrences, teams and author-papers covering
different works, and `conflictingDuplicateInputKeys`.

`summary.txt` repeats the printed lines. Each table of rows (`metrics`, `components`, `communities`,
`coreness`) is preceded by a `<name>-columns [...]` line naming its columns. `pairs.<disposition>`
counts Hadoop pair-candidate rows and `pairs.<disposition>.distinct` counts distinct
(work record, ordered node pair) among them.

The path tables exist only as Parquet, so `summary.txt` also prints them in text (`PathReport`):
`path-status-columns [window, status, unreachableReason, pairs]` then one `path-status [...]` line
per window, status and unreachable reason (pairs counted by distinct pair key; `unreachableReason`
is `null` unless the status is `unreachable-within-bound`), and
`path-length-estimates-columns [...]` then one `path-length-estimates [...]` line per window with
every column of the `path-length-estimates` table except `provenance`, plus `windowStart` and
`windowEnd`. The same rows are in `run-manifest.json` under `pathResults.statusCounts` and
`pathResults.lengthEstimates`. Both are one row per window (or per window and status), read through
`DriverGuard.rows`.
