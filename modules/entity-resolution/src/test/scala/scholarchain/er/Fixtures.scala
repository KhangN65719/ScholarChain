package scholarchain.er

import java.time.Instant
import scholarchain.core.{
  AuthorMention,
  ContractSchema,
  Provenance,
  SourceType,
  StableIds,
  WorkRecord
}
import scholarchain.er.matching.{EntityKind, RunInfo, SideRecord}

object Fixtures:
  val runId: String = StableIds.newRun()
  val runInfo: RunInfo = RunInfo(runId, Instant.parse("2026-11-07T00:00:00Z"))

  /** Titles with no vocabulary in common, so two default mentions are never
    * mistaken for the same paper.
    */
  private val unrelatedTitles = Vector(
    "Quantum lattice chromodynamics methods",
    "Medieval pottery trade networks",
    "Protein folding energy landscapes",
    "Urban traffic flow forecasting"
  )

  val validOrcidA = "0000-0002-1825-0097"
  val validOrcidB = "0000-0001-5109-3700"

  def provenance(
      source: SourceType,
      recordId: String,
      snapshot: String = "snap-1"
  ): Provenance =
    Provenance(
      ContractSchema.currentVersion,
      source,
      snapshot,
      recordId,
      s"https://example.org/$recordId",
      "test-1",
      runId
    )

  def mention(
      id: String,
      name: String,
      coauthors: Vector[String] = Vector.empty,
      affiliations: Vector[String] = Vector.empty,
      externalIds: Map[String, String] = Map.empty,
      title: String = "",
      year: Option[Int] = Some(2020),
      venue: Option[String] = Some("venue-a"),
      source: SourceType = SourceType.Dblp,
      workId: Option[String] = None,
      position: Int = 1
  ): SideRecord =
    val authors = coauthors.patch(position - 1, Vector(name), 0)
    val paperTitle =
      if title.nonEmpty then title
      else unrelatedTitles(Math.floorMod(id.hashCode, unrelatedTitles.size))
    SideRecord(
      id = id,
      kind = EntityKind.Researcher,
      source = source,
      sourceRecordId = s"rec-$id",
      sourceUri = s"https://example.org/$id",
      snapshotId = "snap-1",
      workId = workId.getOrElse(s"work-of-$id"),
      name = name,
      title = paperTitle,
      year = year,
      venue = venue,
      affiliations = affiliations,
      authorNames = authors,
      authorPosition = Some(position),
      externalIds = externalIds,
      links = Vector.empty,
      citationNeighbors = Vector.empty
    )

  def work(
      id: String,
      title: String,
      authors: Vector[String] = Vector.empty,
      year: Option[Int] = Some(2020),
      venue: Option[String] = Some("venue-a"),
      externalIds: Map[String, String] = Map.empty,
      links: Vector[String] = Vector.empty,
      source: SourceType = SourceType.Dblp
  ): SideRecord =
    SideRecord(
      id = id,
      kind = EntityKind.Work,
      source = source,
      sourceRecordId = s"rec-$id",
      sourceUri = s"https://example.org/$id",
      snapshotId = "snap-1",
      workId = id,
      name = "",
      title = title,
      year = year,
      venue = venue,
      affiliations = Vector.empty,
      authorNames = authors,
      authorPosition = None,
      externalIds = externalIds,
      links = links,
      citationNeighbors = Vector.empty
    )

  /** A core work with named authors, for the index and wire tests. */
  def coreWork(
      namespace: String,
      sourceId: String,
      title: String,
      names: Vector[String],
      doi: Option[String],
      source: SourceType = SourceType.Dblp
  ): WorkRecord =
    val workId = StableIds.work(namespace, sourceId)
    val prov = provenance(source, sourceId)
    val authors = names.zipWithIndex.map { (name, index) =>
      AuthorMention(
        ContractSchema.currentVersion,
        StableIds.mention(workId, index + 1),
        workId,
        index + 1,
        name,
        name.toLowerCase,
        Map.empty,
        Vector.empty,
        prov
      )
    }
    WorkRecord(
      ContractSchema.currentVersion,
      workId,
      source,
      sourceId,
      title,
      title.toLowerCase,
      Some(2020),
      Some("venue-a"),
      doi,
      None,
      authors,
      None,
      Vector.empty,
      Vector.empty,
      "article",
      prov
    )
