package scholarchain.core

import java.time.Instant
import upickle.default.{ReadWriter, readwriter}

object ContractSchema:
  opaque type Version = Int

  val currentVersionNumber = 1
  val currentVersion: Version = currentVersionNumber

  object Version:
    given ReadWriter[Version] = readwriter[ujson.Value].bimap[Version](
      version => ujson.Num(version),
      {
        case ujson.Num(number) if number == currentVersionNumber =>
          currentVersion
        case _ =>
          throw new IllegalArgumentException("Unsupported schema version")
      }
    )

  private[core] def requireSupported(version: Version): Unit =
    require(version == currentVersion, s"Unsupported schema version: $version")

private object ContractValidation:
  def present(value: String, field: String): Unit =
    require(value != null && value.trim.nonEmpty, s"$field must be nonblank")

  def record(version: ContractSchema.Version, provenance: Provenance): Unit =
    ContractSchema.requireSupported(version)
    require(provenance != null, "Provenance is required")

  def confidence(value: Double): Unit =
    require(
      value >= 0.0 && value <= 1.0,
      "Confidence must be finite and in [0, 1]"
    )

  def finiteNonnegative(value: Double, field: String): Unit =
    require(
      value.isFinite && value >= 0,
      s"$field must be finite and nonnegative"
    )

  def pair(left: String, right: String, kind: String): Unit =
    StableIds.requireKind(kind, left)
    StableIds.requireKind(kind, right)
    require(
      left < right,
      "Undirected endpoints must be distinct and in ascending ID order"
    )

import ContractValidation.*
import StableIds.requireKind

final case class YearWindow(startYear: Int, endYear: Int) derives ReadWriter:
  require(
    startYear <= endYear,
    "Time window start year must not exceed end year"
  )

  def contains(year: Int): Boolean = startYear <= year && year <= endYear

final case class Provenance(
    schemaVersion: ContractSchema.Version,
    source: SourceType,
    snapshotId: String,
    sourceRecordId: String,
    sourceUri: String,
    transformVersion: String,
    runId: String
) derives ReadWriter:
  ContractSchema.requireSupported(schemaVersion)
  require(source != null, "Provenance source is required")
  present(snapshotId, "Provenance snapshot ID")
  present(sourceRecordId, "Provenance source record ID")
  present(sourceUri, "Provenance source URI")
  present(transformVersion, "Provenance transform version")
  requireKind("run", runId)

final case class WorkRecord(
    schemaVersion: ContractSchema.Version,
    workId: String,
    source: SourceType,
    sourceRecordId: String,
    title: String,
    normalizedTitle: String,
    year: Option[Int],
    venueId: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    authors: Vector[AuthorMention],
    abstractTextRef: Option[String],
    electronicEditionUris: Vector[String],
    sourceUris: Vector[String],
    recordType: String,
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("work", workId)
  present(sourceRecordId, "Source record ID")
  present(title, "Title")
  present(normalizedTitle, "Normalized title")
  present(recordType, "Record type")
  // Venue IDs come from external vocabularies, so only nonblankness is
  // enforced here, matching Venue.venueId rather than the minted ID kinds.
  venueId.foreach(present(_, "Work venue ID"))
  require(
    source == provenance.source && sourceRecordId == provenance.sourceRecordId,
    "Work source and record ID must match provenance"
  )
  require(
    authors.zipWithIndex.forall { case (author, index) =>
      author.workId == workId && author.authorPosition == index + 1
    },
    "Authors must belong to this work and follow its one-based author sequence"
  )

final case class AuthorMention(
    schemaVersion: ContractSchema.Version,
    mentionId: String,
    workId: String,
    authorPosition: Int,
    name: String,
    normalizedName: String,
    externalIds: Map[String, String],
    affiliations: Vector[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("mention", mentionId)
  require(
    mentionId == StableIds.mention(workId, authorPosition),
    "Mention ID must match work and author position"
  )
  present(name, "Author name")
  present(normalizedName, "Normalized author name")

final case class CanonicalResearcher(
    schemaVersion: ContractSchema.Version,
    researcherId: String,
    displayName: String,
    externalIds: Map[String, String],
    aliases: Vector[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("researcher", researcherId)
  present(displayName, "Display name")

final case class AuthorshipRecord(
    schemaVersion: ContractSchema.Version,
    workId: String,
    mentionId: String,
    researcherId: Option[String],
    authorPosition: Int,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("mention", mentionId)
  require(
    mentionId == StableIds.mention(workId, authorPosition),
    "Mention ID must match work and author position"
  )
  researcherId.foreach(requireKind("researcher", _))

final case class PublicationTeam(
    schemaVersion: ContractSchema.Version,
    workId: String,
    year: Option[Int],
    orderedMentionIds: Vector[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("work", workId)
  require(
    orderedMentionIds.zipWithIndex.forall { case (id, index) =>
      id == StableIds.mention(workId, index + 1)
    },
    "Team members must belong to this work and follow its one-based author sequence"
  )

final case class CoauthorPairCandidate(
    schemaVersion: ContractSchema.Version,
    workId: String,
    mentionA: String,
    mentionB: String,
    authorPositionA: Int,
    authorPositionB: Int,
    year: Option[Int],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  pair(mentionA, mentionB, "mention")
  require(
    mentionA == StableIds.mention(workId, authorPositionA) &&
      mentionB == StableIds.mention(workId, authorPositionB),
    "Pair mentions must match their work and positions"
  )

final case class CoauthorEdge(
    schemaVersion: ContractSchema.Version,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    window: YearWindow,
    policyVersion: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    representativeWorkIds: Vector[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  pair(researcherA, researcherB, "researcher")
  require(
    edgeId == StableIds
      .coauthorEdge(researcherA, researcherB, window, policyVersion),
    "Coauthor edge ID does not match its key"
  )
  require(
    jointPublicationCount > 0,
    "A coauthor edge requires at least one joint publication"
  )
  require(
    firstCollaborationYear <= lastCollaborationYear &&
      window.contains(firstCollaborationYear) && window.contains(
        lastCollaborationYear
      ),
    "Collaboration years must be ordered and lie within the graph window"
  )
  require(
    representativeWorkIds.nonEmpty,
    "A coauthor edge requires supporting work IDs"
  )
  representativeWorkIds.foreach(requireKind("work", _))

final case class ResearcherMetrics(
    schemaVersion: ContractSchema.Version,
    researcherId: String,
    window: YearWindow,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Double,
    componentId: String,
    componentSize: Long,
    communityId: Option[String],
    bridgeScore: Option[Double],
    coreness: Option[Int],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("researcher", researcherId)
  require(window != null, "Metrics time window is required")
  require(
    Vector(
      publicationCount,
      distinctCoauthorCount,
      totalCoauthorOccurrences,
      maximumCoauthorsOnOnePaper
    ).forall(_ >= 0),
    "Collaboration counts must be nonnegative"
  )
  require(componentSize > 0, "Component size must be positive")
  present(componentId, "Component ID")
  finiteNonnegative(averageCoauthorsPerPaper, "Average coauthors per paper")
  finiteNonnegative(weightedDegree, "Weighted degree")
  bridgeScore.foreach(finiteNonnegative(_, "Bridge score"))
  require(coreness.forall(_ >= 0), "Coreness must be nonnegative")
  maximumTeamWorkIds.foreach(requireKind("work", _))

final case class Venue(
    schemaVersion: ContractSchema.Version,
    venueId: String,
    name: String,
    issns: Vector[String],
    externalIds: Map[String, String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  present(venueId, "Venue ID")
  present(name, "Venue name")

final case class Institution(
    schemaVersion: ContractSchema.Version,
    institutionId: String,
    name: String,
    countryCode: Option[String],
    externalIds: Map[String, String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  present(institutionId, "Institution ID")
  present(name, "Institution name")

final case class Topic(
    schemaVersion: ContractSchema.Version,
    topicId: String,
    name: String,
    parentTopicId: Option[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  present(topicId, "Topic ID")
  present(name, "Topic name")

final case class MatchDecision(
    schemaVersion: ContractSchema.Version,
    sourceId: String,
    targetId: String,
    features: Map[String, Double],
    rulesFired: Vector[String],
    decision: MatchOutcome,
    confidence: Double,
    reasonCodes: Vector[String],
    policyVersion: String,
    modelVersion: Option[String],
    promptVersion: Option[String],
    decidedAt: String,
    runId: String,
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  require(decision != null, "Match outcome is required")
  present(sourceId, "Match source ID")
  present(targetId, "Match target ID")
  present(policyVersion, "Policy version")
  ContractValidation.confidence(confidence)
  require(features.values.forall(_.isFinite), "Match features must be finite")
  require(reasonCodes.nonEmpty, "Match decisions require reason codes")
  Instant.parse(decidedAt)
  require(runId == provenance.runId, "Match run ID must match provenance")

final case class GraphNode(
    schemaVersion: ContractSchema.Version,
    nodeId: String,
    nodeType: GraphNodeType,
    properties: Map[String, String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  require(nodeType != null, "Graph node type is required")
  nodeType match
    case GraphNodeType.Researcher    => requireKind("researcher", nodeId)
    case GraphNodeType.AuthorMention => requireKind("mention", nodeId)
    case GraphNodeType.Work          => requireKind("work", nodeId)
    case GraphNodeType.TextChunk     => requireKind("chunk", nodeId)
    case _                           => present(nodeId, "Graph node ID")

final case class GraphEdge(
    schemaVersion: ContractSchema.Version,
    edgeId: String,
    fromId: String,
    edgeType: GraphEdgeType,
    toId: String,
    properties: Map[String, String],
    policyVersion: String,
    sourceEvidenceIds: Vector[String],
    coauthorship: Option[CoauthorEdge],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  require(edgeType != null, "Graph edge type is required")
  if edgeType == GraphEdgeType.CoauthoredWith then
    pair(fromId, toId, "researcher")
    require(
      coauthorship.exists(edge =>
        edge.researcherA == fromId && edge.researcherB == toId && edge.policyVersion == policyVersion
      ),
      "Coauthorship statistics must match the graph edge endpoints and policy"
    )
  else
    require(
      coauthorship.isEmpty,
      "Only coauthor graph edges carry coauthorship statistics"
    )
  require(
    edgeId == StableIds
      .graphEdge(edgeType, fromId, toId, policyVersion, sourceEvidenceIds),
    "Graph edge ID does not match its key"
  )

final case class EvidenceItem(
    schemaVersion: ContractSchema.Version,
    evidenceId: String,
    evidenceType: EvidenceType,
    workId: Option[String],
    sourceUri: String,
    chunkId: Option[String],
    graphPath: Option[Vector[String]],
    score: Double,
    confidence: Option[Double],
    text: Option[String],
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  requireKind("evidence", evidenceId)
  require(evidenceType != null, "Evidence type is required")
  workId.foreach(requireKind("work", _))
  chunkId.foreach(requireKind("chunk", _))
  present(sourceUri, "Evidence source URI")
  require(score.isFinite, "Evidence score must be finite")
  confidence.foreach(ContractValidation.confidence)

final case class EvidenceBundle(
    schemaVersion: ContractSchema.Version,
    bundleId: String,
    items: Vector[EvidenceItem],
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  present(bundleId, "Bundle ID")
  // An empty bundle is the honest representation of retrieval that found
  // nothing. The evidence obligation binds claims, not this container, and is
  // enforced by AnswerClaim and by AnswerResponse's resolution check.
  require(
    items.map(_.evidenceId).distinct.size == items.size,
    "Duplicate evidence ID in bundle"
  )

final case class QueryPlan(
    schemaVersion: ContractSchema.Version,
    queryId: String,
    operation: QueryOperation,
    researcherIds: Vector[String],
    window: Option[YearWindow],
    comparisonWindow: Option[YearWindow],
    metrics: Vector[CollaborationMetric],
    resultLimit: Int,
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  present(queryId, "Query ID")
  require(operation != null, "Query operation is required")
  researcherIds.foreach(requireKind("researcher", _))
  require(resultLimit > 0, "Query result limit must be positive")

final case class AnswerClaim(
    schemaVersion: ContractSchema.Version,
    claimId: String,
    text: String,
    evidenceIds: Vector[String],
    verificationStatus: VerificationStatus,
    uncertainty: Option[String],
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  require(verificationStatus != null, "Claim verification status is required")
  present(claimId, "Claim ID")
  present(text, "Claim text")
  evidenceIds.foreach(requireKind("evidence", _))
  require(
    verificationStatus != VerificationStatus.Verified || evidenceIds.nonEmpty,
    "Verified claims require evidence"
  )
  // An unsupported or uncertain claim may legitimately cite nothing, but it
  // must then state why, so no claim is ever both unevidenced and unexplained.
  require(
    evidenceIds.nonEmpty || uncertainty.exists(_.trim.nonEmpty),
    "A claim citing no evidence must record its uncertainty"
  )

final case class AnswerResponse(
    schemaVersion: ContractSchema.Version,
    queryId: String,
    answer: String,
    claims: Vector[AnswerClaim],
    evidence: EvidenceBundle,
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  present(queryId, "Query ID")
  present(answer, "Answer text")
  private val availableEvidence =
    evidence.items.iterator.map(_.evidenceId).toSet
  require(
    claims.forall(_.evidenceIds.forall(availableEvidence.contains)),
    "Claim evidence IDs must resolve within the answer evidence bundle"
  )

final case class RunManifest(
    schemaVersion: ContractSchema.Version,
    runId: String,
    datasetSnapshots: Map[String, String],
    configurationVersion: String,
    provenance: Provenance
) derives ReadWriter:
  record(schemaVersion, provenance)
  require(runId == provenance.runId, "Manifest run ID must match provenance")
  require(
    datasetSnapshots.nonEmpty,
    "Run manifest requires input dataset snapshots"
  )
  datasetSnapshots.foreach { case (dataset, snapshot) =>
    present(dataset, "Manifest dataset")
    present(snapshot, "Manifest dataset snapshot")
  }
  present(configurationVersion, "Configuration version")
