# WO-7d report, storage-engineer (received 2026-11-17; summary as received; author: storage-engineer subagent)

Status: COMPLETE. No production change: the storage readers already use the Spark TableSpec column names, ignore extra columns and treat missing and null alike. New `modules/storage/src/test/scala/scholarchain/storage/SparkExportShapeSuite.scala` (2 tests; orchestrator read its report XML: tests=2, failures=0, errors=0, 2026-11-17T01:44:24). `sbt quick`: `[success] Total time: 117 s`, groups 73/0/0 and 1041/0/0.

ExportTable `required` lists for spark-engineer (provenance hard everywhere; `windowStart`, `windowEnd` ignored):
- `path-evidence`: pairKey, window, sourceId, targetId, status, unreachableReason, hopLimit, pathLength, hopIndex, fromId, toId, edgeId, jointPublicationCount, firstCollaborationYear, lastCollaborationYear, supportingWorkIds, provenance.
- `components`: window, componentId, componentSize, isLargest, provenance.
- `component-membership`: window, researcherId, componentId, componentSize, provenance.
- `communities`: window, communityId, communitySize, internalEdges, crossEdges, internalWeight, crossWeight, provenance.
- `community-membership`: window, researcherId, communityId, communitySize, provenance.
- `community-cross-edges`: window, communityA, communityB, edgeCount, totalWeight, provenance.

Id-column caveat: path-evidence's TableSpec ids include `hopIndex`, null on status rows; `JsonRows.idKey` throws on a null id column, so use `idColumns = Vector("pairKey")` for path-evidence (rows sorted on the whole row keep hop order stable). Other five tables use the TableSpec ids. Note: a `--pairs` file listing one pair twice would break the one-row-type-per-pair rule (not checked).
