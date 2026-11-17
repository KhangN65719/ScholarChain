package scholarchain.storage

import scholarchain.core.StableIds

/** The six tables Phase 15 adds to the Spark JSON-lines export, written with
  * the columns the Spark `TableSpec`s produce (extra columns `windowStart`,
  * `windowEnd`, `selection`; explicit nulls; `found` on hop rows; component and
  * community IDs that are node IDs) and read back through the real export
  * source and the storage readers.
  */
class SparkExportShapeSuite extends munit.FunSuite:
  import Fixtures.*

  private val label = "2000-2009"
  private val (lo, hi) = StableIds.orderedPair(r1, r2)

  private def sparkRow(
      table: String,
      record: String,
      fields: (String, ujson.Value)*
  ): Row =
    (fields.toMap ++ Map(
      "windowStart" -> ujson.Num(2000),
      "windowEnd" -> ujson.Num(2009),
      "provenance" -> provenance(table, record)
    )): Row

  private val key = s"$label|$lo|$hi"

  private val tables: Map[String, Vector[Row]] = Map(
    "path-evidence" -> Vector(
      sparkRow(
        "path-evidence",
        s"$key|1",
        "pairKey" -> key,
        "window" -> label,
        "sourceId" -> lo,
        "targetId" -> hi,
        "selection" -> "auto-top-weighted-degree",
        "status" -> "found",
        "unreachableReason" -> ujson.Null,
        "hopLimit" -> 6,
        "pathLength" -> 1,
        "hopIndex" -> 1,
        "fromId" -> lo,
        "toId" -> hi,
        "edgeId" -> edgeId(lo, hi),
        "jointPublicationCount" -> 2,
        "firstCollaborationYear" -> 2001,
        "lastCollaborationYear" -> 2005,
        "supportingWorkIds" -> ujson.Arr(w1, w2)
      ),
      sparkRow(
        "path-evidence",
        s"$label|$lo|$lo",
        "pairKey" -> s"$label|$lo|$lo",
        "window" -> label,
        "sourceId" -> lo,
        "targetId" -> lo,
        "selection" -> "config-file",
        "status" -> "trivial",
        "unreachableReason" -> ujson.Null,
        "hopLimit" -> 6,
        "pathLength" -> 0,
        "hopIndex" -> ujson.Null,
        "fromId" -> ujson.Null,
        "toId" -> ujson.Null,
        "edgeId" -> ujson.Null,
        "jointPublicationCount" -> ujson.Null,
        "firstCollaborationYear" -> ujson.Null,
        "lastCollaborationYear" -> ujson.Null,
        "supportingWorkIds" -> ujson.Null
      ),
      sparkRow(
        "path-evidence",
        s"$label|$lo|$m1",
        "pairKey" -> s"$label|$lo|$m1",
        "window" -> label,
        "sourceId" -> lo,
        "targetId" -> m1,
        "selection" -> "config-file",
        "status" -> "unreachable-within-bound",
        "unreachableReason" -> "different-component",
        "hopLimit" -> 6,
        "pathLength" -> ujson.Null,
        "hopIndex" -> ujson.Null,
        "fromId" -> ujson.Null,
        "toId" -> ujson.Null,
        "edgeId" -> ujson.Null,
        "jointPublicationCount" -> ujson.Null,
        "firstCollaborationYear" -> ujson.Null,
        "lastCollaborationYear" -> ujson.Null,
        "supportingWorkIds" -> ujson.Null
      )
    ),
    "components" -> Vector(
      sparkRow(
        "components",
        s"$label|$lo",
        "window" -> label,
        "componentId" -> lo,
        "componentSize" -> 2,
        "isLargest" -> true
      )
    ),
    "component-membership" -> Vector(lo, hi).map { id =>
      sparkRow(
        "component-membership",
        s"$label|$id",
        "window" -> label,
        "researcherId" -> id,
        "componentId" -> lo,
        "componentSize" -> 2
      )
    },
    "communities" -> Vector(lo, hi).map { id =>
      sparkRow(
        "communities",
        s"$label|$id",
        "window" -> label,
        "communityId" -> id,
        "communitySize" -> 1,
        "internalEdges" -> 0,
        "crossEdges" -> 1,
        "internalWeight" -> 0,
        "crossWeight" -> 2
      )
    },
    "community-membership" -> Vector(lo, hi).map { id =>
      sparkRow(
        "community-membership",
        s"$label|$id",
        "window" -> label,
        "researcherId" -> id,
        "communityId" -> id,
        "communitySize" -> 1
      )
    },
    "community-cross-edges" -> Vector(
      sparkRow(
        "community-cross-edges",
        s"$label|$lo|$hi",
        "window" -> label,
        "communityA" -> lo,
        "communityB" -> hi,
        "edgeCount" -> 1,
        "totalWeight" -> 2
      )
    )
  )

  private def source =
    JsonlExportSource.open(
      ExportFixtures.write(ExportFixtures.newDir(), tables, runId = runId)
    )

  test("path-evidence rows in the Spark shape are read and answered") {
    val port = InMemoryPathEvidence.from(source, LoadBounds.Default)
    port.shortestPath(PathQuery(label, lo, hi, 6, 6, 1000L)) match
      case PathLookup.Found(_, _, _, hops) =>
        assertEquals(hops.map(_.edgeId), Vector(edgeId(lo, hi)))
        assertEquals(hops.head.supportingWorkIds, Vector(w1, w2))
      case other => fail(s"expected Found, got $other")
    val trivial = port.shortestPath(PathQuery(label, lo, lo, 6, 6, 1000L))
    trivial match
      case PathLookup.NonPath(_, _, _, status, _, kind, _) =>
        assertEquals(status, "trivial")
        assertEquals(kind, NonPathKind.Trivial)
      case other => fail(s"expected NonPath, got $other")
    assert(
      port
        .shortestPath(PathQuery(label, lo, m1, 6, 6, 1000L))
        .noPathExists
    )
  }

  test("component and community rows in the Spark shape are read") {
    val src = source
    val components = InMemoryComponents.from(src, LoadBounds.Default)
    assertEquals(components.componentOf(label, hi).map(_.componentId), Some(lo))
    assertEquals(components.component(label, lo).map(_.isLargest), Some(true))
    val communities = InMemoryCommunities.from(src, LoadBounds.Default)
    assertEquals(
      communities.communityOf(label, hi).map(_.communityId),
      Some(hi)
    )
    assertEquals(communities.crossCommunityEdgeCount(label, lo), Some(1L))
    assertEquals(
      communities.neighbors(label, lo, 10).map(_.neighbors.map(_.neighborId)),
      Some(Vector(hi))
    )
  }
