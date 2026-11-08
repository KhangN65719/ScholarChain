package scholarchain.er.pairs

import java.nio.file.Path
import scholarchain.core.ContractSchema
import scholarchain.er.matching.{EntityKind, Identifiers, SideRecord}
import upickle.default.{ReadWriter, read as readJson, write as writeJson}
import upickle.implicits.key

/** One identifier a record carries, normalized when it is a recognized
  * identifier. Unrecognized or invalid values keep the namespace `other:<key>`
  * so a reviewer still sees them.
  */
final case class IdentifierEvidence(namespace: String, value: String)
    derives ReadWriter

/** What a reviewer sees for one record. Field order is the display order,
  * strongest evidence first: identifiers, then names, coauthors, venue, year
  * and affiliations, then the title, then where the record came from.
  */
final case class PairSide(
    identifiers: Vector[IdentifierEvidence],
    name: String,
    coauthors: Vector[String],
    venue: Option[String],
    year: Option[Int],
    affiliations: Vector[String],
    title: String,
    recordId: String,
    source: String,
    sourceRecordId: String,
    sourceUri: String,
    snapshotId: String
) derives ReadWriter

object PairSide:
  def identifiersOf(side: SideRecord): Vector[IdentifierEvidence] =
    val recognized = side.identifiers.values.toVector
      .sortBy((namespace, _) => (namespace.tier.ordinal, namespace.wire))
      .flatMap((namespace, values) =>
        values.toVector.sorted.map(IdentifierEvidence(namespace.wire, _))
      )
    val understood = side.externalIds.collect {
      case (key, value)
          if Identifiers
            .namespaceFor(side.kind, key)
            .exists(Identifiers.normalize(_, value).isDefined) =>
        key
    }.toSet
    val other = side.externalIds.toVector
      .sortBy(_._1)
      .filterNot((key, _) => understood(key))
      .map((key, value) => IdentifierEvidence(s"other:$key", value))
    recognized ++ other

  def of(side: SideRecord): PairSide =
    PairSide(
      identifiers = identifiersOf(side),
      name = side.name,
      coauthors = side.coauthors,
      venue = side.venue,
      year = side.year,
      affiliations = side.affiliations,
      title = side.title,
      recordId = side.id,
      source = side.source.wire,
      sourceRecordId = side.sourceRecordId,
      sourceUri = side.sourceUri,
      snapshotId = side.snapshotId
    )

/** A pair for human labeling. It carries evidence and provenance only: no
  * stratum, score, decision or reason. `runId` is the run that produced the
  * candidate and `exportVersion` names the exporter, so the pair traces to its
  * source records, dataset snapshot and transformation.
  */
final case class PairItem(
    schemaVersion: ContractSchema.Version,
    @key("pair_id") pairId: String,
    kind: EntityKind,
    runId: String,
    exportVersion: String,
    left: PairSide,
    right: PairSide
) derives ReadWriter:
  require(pairId.trim.nonEmpty, "A pair needs an ID")
  require(
    left.recordId < right.recordId,
    "Pair endpoints must be in ascending record ID order"
  )

object PairItem:
  val exportVersion = "scholarchain.er.pairs-export/1.0.0"

  def idOf(kind: EntityKind, leftId: String, rightId: String): String =
    "pair:v1:" + Hashing.sha256Hex(s"${kind.wire}\n$leftId\n$rightId").take(32)

  def render(item: PairItem): String = writeJson(item)

object PairsFile:
  def parse(lines: Iterable[String]): Vector[PairItem] =
    val items = lines.iterator.zipWithIndex
      .filter((line, _) => line.trim.nonEmpty)
      .map { (line, number) =>
        try readJson[PairItem](line)
        catch
          case error: Exception =>
            throw new IllegalArgumentException(
              s"Invalid pair on line ${number + 1}: ${error.getMessage}",
              error
            )
      }
      .toVector
    val duplicated = items.groupBy(_.pairId).filter(_._2.size > 1).keys.toVector
    require(
      duplicated.isEmpty,
      s"Duplicate pair_id: ${duplicated.sorted.mkString(", ")}"
    )
    items

  def read(path: Path): Vector[PairItem] = parse(InputFiles.lines(path))

  def write(path: Path, items: Iterable[PairItem]): Unit =
    AtomicFile.writeLines(path, items.map(PairItem.render))
