package scholarchain.core.pairing

import scholarchain.core.{ContractSchema, Provenance, SourceType}
import upickle.default.{ReadWriter, readwriter}

private object PairingWire:
  def codec[A](
      values: Array[A],
      wire: A => String,
      label: String
  ): ReadWriter[A] =
    val lookup = values.iterator.map(value => wire(value) -> value).toMap
    require(lookup.size == values.length, "Enum wire values must be unique")
    readwriter[String].bimap[A](
      wire,
      key =>
        lookup.getOrElse(
          key,
          throw new IllegalArgumentException(s"Unknown $label: $key")
        )
    )

/** The entity being blocked. Researcher and work blocks share one key space
  * with an explicit discriminator.
  */
enum BlockKind(val wire: String):
  case Researcher extends BlockKind("researcher")
  case Work extends BlockKind("work")

object BlockKind:
  given ReadWriter[BlockKind] =
    PairingWire.codec(values, _.wire, "block kind")

/** Which signal produced a blocking key. */
enum BlockStrategy(val wire: String, val selective: Boolean):
  case NameYear extends BlockStrategy("name-year", true)
  case NameVenue extends BlockStrategy("name-venue", true)
  case NameCoauthor extends BlockStrategy("name-coauthor", true)
  case NameAffiliation extends BlockStrategy("name-affiliation", true)
  case NameInitials extends BlockStrategy("name-initials", false)
  case TitleYear extends BlockStrategy("title-year", true)
  case TitlePrefix extends BlockStrategy("title-prefix", false)

object BlockStrategy:
  given ReadWriter[BlockStrategy] =
    PairingWire.codec(values, _.wire, "strategy")

/** A candidate pair of records to be adjudicated, with its deterministic
  * features.
  *
  * The record carries no decision and no score. Accept and reject policy, and
  * the resulting `MatchDecision`, belong to entity resolution.
  *
  * The wire format is the Phase 4 Hadoop artifact unchanged: schema version 1,
  * the field names below in this order, enums as lowercase strings and no
  * optional fields. Readers ignore unknown fields.
  */
final case class MatchCandidate(
    schemaVersion: ContractSchema.Version,
    recordType: String,
    kind: BlockKind,
    leftId: String,
    rightId: String,
    leftSource: SourceType,
    rightSource: SourceType,
    blockKey: String,
    blockStrategy: BlockStrategy,
    exactIdentifierAgreements: Vector[String],
    exactIdentifierConflicts: Vector[String],
    features: Map[String, Double],
    provenance: Provenance
) derives ReadWriter:
  require(
    schemaVersion == ContractSchema.currentVersion,
    "Unsupported match candidate schema version"
  )
  require(
    leftId < rightId,
    "A candidate's endpoints must be distinct and in ascending ID order"
  )
  require(features.values.forall(_.isFinite), "Features must be finite")
  require(provenance != null, "A candidate must carry provenance")

object MatchCandidate:
  /** The record type Hadoop already writes. It is a frozen wire value. */
  val recordTypeName = "scholarchain.hadoop.MatchCandidate/v1"
