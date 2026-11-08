# Work order 16: storage-engineer (D13 rulings 1 and 2)

Authority: modules/storage/** only. Read CLAUDE.md, docs/agents.md, your role file, `prompt.md` (README Phase 7 section, verbatim), `decisions.md` (RULINGS round 2, D13), `review.md` round 3, `service-limits.md`. Local only: no AWS call, no SDK, no credentials. Read-only git, no commit. Do not edit CLAUDE.md, docs/agents.md or .claude/**.

## Task 1: S3 Vectors query port with metadata filters (README lines for vector filters)
- Add a query port to `VectorBucketPort` (Vectors.scala) with a local-fake implementation: top-K by cosine/euclidean per index distance metric, bounded `topK` (cite the S3 Vectors QueryVectors limit from service-limits.md; capture it from current docs if missing and cite it), and metadata filters on: researcher, year window, source, topic, institution, community, document type. Respect the 2 KB filterable-metadata and 50-key limits.
- Add the missing compact metadata keys (`source`, `topic`, `institution`, `communityId` and any document-type/year keys the filters need) wherever Spark outputs have data for them; where Spark has no source data (topic and institution per D13), the key is optional and absent, never invented. Long text stays out of metadata (gate).
- Reject unknown filter keys and malformed filters with typed errors. Keep D7 behaviour (changed vector under an existing key refused).
- Tests in modules/storage tests: filter by each key, combined filters, window boundaries, topK bound, missing/optional keys, malformed filter, determinism of ordering on ties.

## Task 2: Neptune relationship types (README Neptune list)
- Load every README relationship type for which the Phase 6 Spark outputs have data. Spark exports tables: edges-authored, edges-published-in, edges-coauthored, edge-support, plus nodes-topic, nodes-institution, nodes-venue, semantic-summaries, community-membership, components, unresolved-mentions, path-evidence. Decide per type (AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO, DERIVED_FROM, SUPPORTED_BY, selected context nodes) whether an actual edge/column source exists. Implement those with data (IDs via core `StableIds.graphEdge`, `edge:v1:`, as D10), with validation and tests. Do not invent edges without a source column.
- For each type left unloaded, write the reason (which table lacks which column) in `modules/storage/DESIGN.md` (create it or extend the existing doc you own) and cite the Spark table schema.
- Keep the legacy-ID refusal, B1/B2 checks and `_SUCCESS` gate intact; weaken no test.

## Process
1. Before implementing, list in your report which specific test proves each item above.
2. Run `sbt quick` once (never two sbt at once). Report exact pass/fail counts, files changed and the metadata/relationship decisions.
3. Update `modules/storage/README.md` if the surface changes. Report `BLOCKED-ON` with evidence if a prerequisite is outside your territory (for example a Spark column) instead of working around it.
