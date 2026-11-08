# Storage module design notes

Owner: `storage-engineer`. Local ports, fakes and loaders only; the AWS adapters are
DEFERRED (`AwsAdapters.scala`). Service limits are in
`docs/agent-runs/phase-07/service-limits.md` (captured 2026-11-08).

## Neptune relationship types (decision D13 ruling 2)

The README lists eight relationship types. `SparkGraphProjector` takes a
`GraphScope`:

- `Extended` (the default, per the human ruling that Neptune loads every type Spark
  has data for): Base plus RESOLVES_TO and DERIVED_FROM. It adds vertices and edges.
- `Base`: AUTHORED, COAUTHORED_WITH, PUBLISHED_IN. Selectable explicitly.

Every ID is a core ID except the `DatasetSnapshot` vertex: its ID is the free-form
provenance `snapshotId`, which core checks only for nonblankness (`Contracts.scala:86`),
not a `StableIds` kind. Edges without a Spark ID are `StableIds.graphEdge` (`edge:v1:`)
over the endpoints and a core evidence ID minted from the row's own keys, never from
provenance, so a rerun keeps the ID (`idDerivation` = `core:StableIds.graphEdge`).

| Type | Status | Source and rule |
|---|---|---|
| AUTHORED | loaded (Base) | `edges-authored`: `researcherNodeId` to `canonicalWorkId`, evidence record = `mentionId`. |
| COAUTHORED_WITH | loaded (Base) | `edges-coauthored` with `edge-support`; ID is the Spark/core `edgeId`, checked by rebuilding a core `CoauthorEdge`. |
| PUBLISHED_IN | loaded (Base) | `edges-published-in`: `fromId` (work) to `toId` (venue). |
| RESOLVES_TO | loaded (Extended) | `edges-authored` rows whose `mentionId` differs from `researcherNodeId`: the mention an accepted link resolved, to its canonical researcher. The mention becomes an `AuthorMention` vertex (`resolution` = `resolved`) built from that row (`canonicalWorkId`, `authorPosition`, provenance). An unresolved mention is its own node and resolves to nothing, so it gets no edge. A mention that is not a core mention ID, or whose researcher node is itself a mention, is refused. |
| DERIVED_FROM | loaded (Extended) | Every `nodes-work` row carries `provenance.snapshotId`. Each distinct snapshot becomes one `DatasetSnapshot` context vertex (core `GraphNodeType.DatasetSnapshot`), and each work gets a DERIVED_FROM edge to it. The vertex holds only fields common to the snapshot (ID, run, transform version); two works that disagree on the run of one snapshot are refused as `DuplicateId`. |
| AFFILIATED_WITH | not loaded | No table links a researcher or mention to an institution. `nodes-institution` has `institutionId, name, countryCode, externalIdsJson, inputSource, inputSnapshotId, inputRows, provenance` and `nodes-researcher` has `nodeId, nodeKind, idRule, orcid, linkStatus, memberCount, inResearchersExport, displayName, mentionCount, provenance`; neither holds the other's ID. Spark's `Canonical.institutionNodes` reads only the Hadoop `institutions` export. The core `AuthorMention.affiliations` strings are not exported by any Spark table. Needs a Hadoop author-to-institution export and a Spark edge table (spark-engineer, hadoop-engineer). |
| HAS_TOPIC | not loaded | No table links a work to a topic. `nodes-work` has `canonicalWorkId, representativeWorkId, memberCount, memberWorkIds, title, year, yearFromDuplicateRecord, venueId, doi, source, sourceRecordId, provenance`, no topic column; `nodes-topic` has `topicId, name, parentTopicId, inputSource, inputSnapshotId, inputRows, provenance`. `parentTopicId` is a topic-to-topic hierarchy, not HAS_TOPIC. `modules/spark/DESIGN.md` states it: "the Hadoop exports hold no work to topic link". |
| SUPPORTED_BY | not loaded | The only support relation Spark exports is `edge-support`: a COAUTHORED_WITH edge to its supporting works. A property-graph edge cannot start at an edge, and no claim or text-chunk node exists (`TextChunk` has no source table: abstracts are not exported). The support is kept as `evidencePointer` and `representativeWorkIds` on the edge and is validated on load (count, years, endpoints, authorship). |

Node types: Researcher, AuthorMention, Work, Venue, Institution and Topic are loaded.
Institution and Topic vertices come from `nodes-institution` and `nodes-topic`; they
have no relationship because of the two rows above. Selected context nodes: only
`DatasetSnapshot` has data. `TextChunk` has none (see SUPPORTED_BY). Spark's
`semantic-summaries` (types `researcher-profile`, `community`, `collaboration-path`,
`work`; columns `summaryId, summaryType, window, windowStart, windowEnd, subjectId,
nodeKind, summaryText, evidenceWorkIds, evidenceMetrics, evidenceNodeIds,
provenance`) are not graph nodes: core `GraphNodeType` has no summary type, and the
text belongs in S3 and the vector index. `community-membership` and `components` are
window-local groupings with no README node type, and `path-evidence` and
`unresolved-mentions` are runtime lookups in DynamoDB, so none of them is projected.

Not run on real data: `GraphScope.Extended` and the new vector facets have never run on
real Parquet, because storage has no Parquet reader (D6); they ran only on fixtures. The
real-run vertex increase of about 8,800 `AuthorMention` vertices (21,268 AUTHORED rows
minus 12,453 unresolved mention nodes) is inferred from counts, not measured.

Risk: the vertex count grows by one per resolved mention under `Extended`. The row
bound (`LoadBounds`) applies to `edges-authored` as before. Counts on the module fixture
(`Fixtures.graphTables`): Base 7 vertices and 8 edges; Extended 12 vertices and 15
edges (27 inserted by the fake bulk loader).

## S3 Vectors query and filters (decision D13 ruling 1)

`VectorBucketPort.query(VectorQuery)` returns the nearest `topK` vectors that satisfy a
`VectorFilter`, nearest first, ties by ascending key. `topK` is 1 to 10,000 (QueryVectors
limit; the AWS adapter pages 100 per call). The fake ranks by the index's distance metric
(`cosine` = 1 minus cosine similarity; `euclidean` = L2).

Filter fields and the metadata key each reads:

| Filter | Metadata key | Spark column behind it |
|---|---|---|
| researcher | `subjectId` or `evidenceNodeIds` (first 5) | `semantic-summaries.subjectId`, `.evidenceNodeIds` |
| yearFrom / yearTo | `windowStart` and `windowEnd` (window must lie inside the range), else `year` | `semantic-summaries.windowStart/windowEnd`; `nodes-work.year` for a `work` summary |
| source | `source` (list) | `nodes-work.source` of the summary's `evidenceWorkIds` |
| topic | `topic` (list) | none: absent by design |
| institution | `institution` (list) | none: absent by design |
| community | `communityId` | `community` summary: `subjectId`; `researcher-profile`: `researcher-metrics.communityId` for (researcher, window) |
| documentType | `documentType` | `semantic-summaries.summaryType` |

`topic` and `institution` are allowed keys so a later loader can set them, but no
loader writes them: nothing in Spark backs them. A vector that lacks a key never matches
a filter on it. Metadata stays within 50 keys and 2 KB; list values hold at most five
references of at most 200 characters; no long text. A changed vector under an existing
key is still refused (D7). Unknown filter keys raise `UnknownFilterKey`; malformed
values raise `InvalidFilter`; a bad `topK` raises `InvalidQuery` or `BoundExceeded`.
