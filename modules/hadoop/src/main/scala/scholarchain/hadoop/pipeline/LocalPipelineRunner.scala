package scholarchain.hadoop.pipeline

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scholarchain.core.{
  CanonicalResearcher,
  CoauthorPairCandidate,
  ContractWire,
  Institution,
  Topic,
  Venue,
  WorkRecord
}

/** The result of one logical stage: what it emitted, what it rejected, and what
  * its counters read.
  */
final case class StageOutcome(
    stage: String,
    emitted: Map[String, Vector[(String, String)]],
    rejects: Vector[RejectRecord],
    counters: Vector[(String, Long)]
):
  def records(label: String): Vector[String] =
    emitted.getOrElse(label, Vector.empty).map(_._2)

  def keys(label: String): Vector[String] =
    emitted.getOrElse(label, Vector.empty).map(_._1)

/** The observable facts the work order asks to be reported. */
final case class PipelineObservations(
    stages: Vector[StageOutcome],
    partitionCounts: Map[String, Map[Int, Int]],
    maximumAuthorTeamSize: Int,
    maximumAuthorTeamWorkId: String,
    largestPairExpansion: Long,
    largestBlockingGroup: Int,
    largestBlockingGroupKey: String
):
  def stage(name: String): StageOutcome =
    stages
      .find(_.stage == name)
      .getOrElse(throw new IllegalArgumentException(s"No such stage: $name"))

  def counter(name: String): Long =
    stages.iterator
      .flatMap(_.counters)
      .collect {
        case (actual, value) if actual == name => value
      }
      .sum

  def totalRejects: Int = stages.map(_.rejects.size).sum

/** Executes the pipeline's transformations locally over bounded inputs.
  *
  * **What this is and is not.** This runner drives exactly the same
  * transformation objects the Hadoop mappers and reducers call, through an
  * explicit shuffle that groups by the same logical key and partitions with the
  * same function. It is a faithful harness for the pipeline's *logic* and for
  * its counters, partition assignment, bounds and determinism.
  *
  * It is **not** a Hadoop job execution. It runs no `JobTracker`, no task JVM,
  * no `OutputCommitter` and no real split assignment. Evidence that a job
  * executes under Hadoop has to come from a `LocalJobRunner` or mini-cluster
  * run, which needs Hadoop's runtime on the test classpath.
  */
object LocalPipelineRunner:
  /** The shuffle: group by `(label, logical key)` and sort, exactly as a
    * reducer receives its group. Values are sorted so the local run cannot
    * accidentally depend on an emission order that Hadoop would not preserve.
    */
  def shuffle(
      emitted: Vector[(String, String, String)]
  ): Vector[(String, String, Vector[String])] =
    emitted
      .groupBy { case (label, key, _) => (label, key) }
      .toVector
      .sortBy(_._1)
      .map { case ((label, key), group) =>
        (label, key, group.map(_._3).sorted)
      }

  /** Partition assignment, delegated to the single definition the Hadoop
    * partitioner also uses, so the local run reports the same assignment a
    * cluster run would produce.
    */
  def partitionOf(logicalKey: String, partitions: Int): Int =
    PartitionKey.partitionOf(logicalKey, partitions)

  private def outcome(
      stage: String,
      sink: RecordingSink
  ): StageOutcome =
    StageOutcome(
      stage = stage,
      emitted = sink.records
        .groupBy(_._1)
        .map { case (label, group) =>
          label -> group.map { case (_, key, value) => key -> value }
        },
      rejects = sink.rejects,
      counters = sink.counterReport
    )

  /** Job 1, locally. */
  def extractDblp(xml: String, run: RunContext): StageOutcome =
    val sink = new RecordingSink
    XmlRecordStream.records(xml, XmlRecordStream.allTags).foreach { record =>
      sink.count(PipelineCounter.InputRecords)
      val tag = XmlRecordStream.rootTag(record).getOrElse("")
      if XmlRecordStream.identityTags.contains(tag) then
        DblpParser.parseIdentity(record, run) match
          case Right(researcher) =>
            sink.count(PipelineCounter.AcceptedRecords)
            sink.emitRecord(
              TaskSink.researchers,
              researcher.researcherId,
              researcher
            )
          case Left((reason, detail)) =>
            rejectDblp(sink, run, reason, detail, record)
      else
        DblpParser.parse(record, run) match
          case Right(publication) =>
            sink.count(PipelineCounter.AcceptedRecords)
            sink.emitRecord(
              TaskSink.works,
              publication.work.workId,
              publication.work
            )
            publication.venue.foreach(venue =>
              sink.emitRecord(TaskSink.venues, venue.venueId, venue)
            )
            publication.editorResearchers.foreach(researcher =>
              sink.emitRecord(
                TaskSink.researchers,
                researcher.researcherId,
                researcher
              )
            )
          case Left((reason, detail)) =>
            rejectDblp(sink, run, reason, detail, record)
    }
    outcome(Stage.dblpExtraction, sink)

  private def rejectDblp(
      sink: RecordingSink,
      run: RunContext,
      reason: RejectReason,
      detail: String,
      record: String
  ): Unit =
    if reason == RejectReason.MalformedXml ||
      reason == RejectReason.SchemaViolation
    then sink.count(PipelineCounter.SchemaFailureRecords)
    sink.reject(
      RejectRecord(
        stage = Stage.dblpExtraction,
        reason = reason,
        detail = detail,
        sourceRecordId =
          XmlRecordStream.rootTag(record).getOrElse("unknown-record"),
        payload = record,
        provenance = run.provenance(
          scholarchain.core.SourceType.Dblp,
          "dblp:unparsed-record",
          "https://dblp.org/xml/",
          "dblp"
        )
      )
    )

  /** Job 2, locally, for one dataset. */
  def normalize(
      dataset: String,
      lines: Vector[String],
      run: RunContext
  ): StageOutcome =
    val sink = new RecordingSink
    val normalizer = SourceNormalizers.byDataset.get(dataset)
    lines.filter(_.trim.nonEmpty).foreach { line =>
      sink.count(PipelineCounter.InputRecords)
      normalizer match
        case None =>
          sink.count(PipelineCounter.SchemaFailureRecords)
          sink.reject(
            RejectRecord(
              stage = Stage.sourceNormalization,
              reason = RejectReason.UnsupportedRecordType,
              detail = s"No normalizer registered for dataset '$dataset'",
              sourceRecordId = dataset,
              payload = line,
              provenance = run.provenance(
                scholarchain.core.SourceType.Derived,
                s"$dataset:unsupported",
                s"scholarchain:$dataset",
                run.datasetSnapshots.keys.toVector.sorted.head
              )
            )
          )
        case Some(normalizeOne) =>
          normalizeOne(line.trim, run) match
            case Right(record) =>
              sink.count(PipelineCounter.AcceptedRecords)
              record.work.foreach(work =>
                sink.emitRecord(TaskSink.works, work.workId, work)
              )
              record.researchers.foreach(researcher =>
                sink.emitRecord(
                  TaskSink.researchers,
                  researcher.researcherId,
                  researcher
                )
              )
              record.topics.foreach(topic =>
                sink.emitRecord(TaskSink.topics, topic.topicId, topic)
              )
              record.institutions.foreach(institution =>
                sink.emitRecord(
                  TaskSink.institutions,
                  institution.institutionId,
                  institution
                )
              )
              record.venues.foreach(venue =>
                sink.emitRecord(TaskSink.venues, venue.venueId, venue)
              )
              if record.identifiers.isEmpty then
                sink.count(PipelineCounter.MissingKeyRecords)
            case Left((reason, detail)) =>
              if reason == RejectReason.MalformedJson then
                sink.count(PipelineCounter.SchemaFailureRecords)
              if reason == RejectReason.MissingRequiredField then
                sink.count(PipelineCounter.MissingKeyRecords)
              sink.reject(
                RejectRecord(
                  stage = Stage.sourceNormalization,
                  reason = reason,
                  detail = detail,
                  sourceRecordId = dataset,
                  payload = line,
                  provenance = run.provenance(
                    scholarchain.core.SourceType.values
                      .find(_.wire == dataset)
                      .getOrElse(scholarchain.core.SourceType.Derived),
                    s"$dataset:rejected",
                    s"scholarchain:$dataset",
                    if run.datasetSnapshots.contains(dataset) then dataset
                    else run.datasetSnapshots.keys.toVector.sorted.head
                  )
                )
              )
    }
    outcome(s"${Stage.sourceNormalization}/$dataset", sink)

  /** Job 3, locally: map and then reduce through the shuffle. */
  def joinIdentifiers(
      works: Vector[WorkRecord],
      run: RunContext
  ): StageOutcome =
    val mapSink = new RecordingSink
    works.foreach { work =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      IdentifierJoin.emit(work, Vector.empty, mapSink)
    }
    val reduceSink = new RecordingSink
    mapSink.counterValues.foreach { case (counter, value) =>
      if counter != PipelineCounter.RejectedRecords then
        reduceSink.count(counter, value)
    }
    mapSink.rejects.foreach(reduceSink.reject)
    shuffle(mapSink.records)
      .filter(_._1 == TaskSink.identityLinkInputs)
      .foreach { case (_, key, values) =>
        val claimed = values.map(ContractWire.decode[IdentifierClaim])
        IdentifierJoin.resolve(key, claimed, run) match
          case Some((record, duplicates, conflicts)) =>
            if duplicates > 0 then
              reduceSink.count(PipelineCounter.DuplicateRecords, duplicates)
            if conflicts > 0 then
              reduceSink.count(PipelineCounter.ConflictRecords, conflicts)
              reduceSink.reject(
                IdentifierJoin
                  .conflictReject(Stage.identifierJoin, key, claimed, record)
              )
            reduceSink.emit(
              TaskSink.identifierKeys,
              key,
              ContractWire.encode(record)
            )
          case None => reduceSink.count(PipelineCounter.MissingKeyRecords)
      }
    outcome(Stage.identifierJoin, reduceSink)

  /** Job 4, locally. */
  def block(works: Vector[WorkRecord], run: RunContext): StageOutcome =
    val mapSink = new RecordingSink
    works.foreach { work =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      Blocking.emit(work, mapSink, run)
    }
    val reduceSink = new RecordingSink
    mapSink.counterValues.foreach { case (counter, value) =>
      if counter != PipelineCounter.RejectedRecords then
        reduceSink.count(counter, value)
    }
    mapSink.rejects.foreach(reduceSink.reject)
    shuffle(mapSink.records).filter(_._1 == TaskSink.blockingKeys).foreach {
      case (_, blockKey, values) =>
        val selector = new BoundedBlockSelector(run.maxBlockSize)
        values.foreach(value =>
          selector.add(ContractWire.decode[BlockMember](value))
        )
        // Same call the Hadoop reducer makes, so the local run reports the
        // identical `observedSize` and `truncated` definition.
        val (group, members) = Blocking.boundSelected(blockKey, selector, run)
        val observed = selector.observed
        if observed > run.maxBlockSize then
          reduceSink.count(PipelineCounter.OversizeBlocks)
          reduceSink.reject(
            RejectRecord(
              stage = Stage.blocking,
              reason = RejectReason.OversizeBlock,
              detail =
                s"Block $blockKey holds $observed records, above the bound of ${run.maxBlockSize}; retained ${members.size} distinct members deterministically",
              sourceRecordId = blockKey,
              payload = members.map(_.memberId).mkString(","),
              provenance = run.provenance(
                scholarchain.core.SourceType.Derived,
                blockKey,
                s"scholarchain:block/$blockKey",
                run.datasetSnapshots.keys.toVector.sorted.head
              )
            )
          )
        if members.size > 1 then
          reduceSink.emit(
            TaskSink.blockingGroups,
            blockKey,
            ContractWire.encode(group)
          )
          members.foreach(member =>
            reduceSink.emit(
              TaskSink.blockingMembers,
              blockKey,
              ContractWire.encode(BlockEnvelope(blockKey, member))
            )
          )
    }
    outcome(Stage.blocking, reduceSink)

  /** Job 5, locally. */
  def buildCandidates(
      envelopes: Vector[BlockEnvelope],
      run: RunContext
  ): StageOutcome =
    val sink = new RecordingSink
    envelopes
      .groupBy(_.blockKey)
      .toVector
      .sortBy(_._1)
      .foreach { case (blockKey, group) =>
        sink.count(PipelineCounter.InputRecords, group.size.toLong)
        sink.count(PipelineCounter.AcceptedRecords, group.size.toLong)
        sink.count(PipelineCounter.EmittedRecords, group.size.toLong)
        val selector = new BoundedBlockSelector(run.maxBlockSize)
        group.foreach(envelope => selector.add(envelope.member))
        if selector.observed > run.maxBlockSize then
          sink.count(PipelineCounter.OversizeBlocks)
        MatchFeatures
          .candidates(blockKey, selector.retained, run)
          .foreach { candidate =>
            if candidate.exactIdentifierConflicts.nonEmpty then
              sink.count(
                PipelineCounter.ConflictRecords,
                candidate.exactIdentifierConflicts.size.toLong
              )
            sink.emit(
              TaskSink.matchCandidates,
              s"${candidate.leftId}|${candidate.rightId}",
              ContractWire.encode(candidate)
            )
          }
      }
    outcome(Stage.candidateFeatures, sink)

  /** Job 6, locally. */
  def expandTeams(works: Vector[WorkRecord], run: RunContext): StageOutcome =
    val mapSink = new RecordingSink
    works.foreach { work =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      TeamExpansion.emit(work, mapSink, run)
    }
    val reduceSink = new RecordingSink
    // Forward rejects through the sink once; their counter is recomputed there.
    mapSink.counterValues.foreach { case (counter, value) =>
      if counter != PipelineCounter.RejectedRecords then
        reduceSink.count(counter, value)
    }
    mapSink.rejects.foreach(reduceSink.reject)
    shuffle(mapSink.records).foreach { case (label, key, values) =>
      label match
        case TaskSink.pairs =>
          val decoded = values.map(ContractWire.decode[CoauthorPairCandidate])
          val (retained, duplicates) = TeamExpansion.consolidate(key, decoded)
          if duplicates > 0 then
            reduceSink.count(PipelineCounter.DuplicateRecords, duplicates)
          retained.foreach { pair =>
            reduceSink.emit(TaskSink.pairs, key, ContractWire.encode(pair))
            reduceSink.count(PipelineCounter.EmittedPairs)
          }
        case TaskSink.teams | TaskSink.authorPapers =>
          val distinct = values.distinct
          if distinct.size < values.size then
            reduceSink.count(
              PipelineCounter.DuplicateRecords,
              (values.size - distinct.size).toLong
            )
          reduceSink.emit(label, key, distinct.min)
        case _ => ()
    }
    outcome(Stage.teamExpansion, reduceSink)

  /** Job 7, locally. */
  def exportGraph(
      works: Vector[WorkRecord],
      researchers: Vector[CanonicalResearcher],
      topics: Vector[Topic],
      institutions: Vector[Institution],
      venues: Vector[Venue],
      run: RunContext
  ): StageOutcome =
    val mapSink = new RecordingSink
    works.foreach { work =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      mapSink.emitRecord(TaskSink.works, work.workId, work)
      work.authors.foreach(mention =>
        mapSink.emitRecord(TaskSink.mentions, mention.mentionId, mention)
      )
    }
    researchers.foreach { researcher =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      mapSink.emitRecord(
        TaskSink.researchers,
        researcher.researcherId,
        researcher
      )
    }
    topics.foreach { topic =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      mapSink.emitRecord(TaskSink.topics, topic.topicId, topic)
    }
    institutions.foreach { institution =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      mapSink.emitRecord(
        TaskSink.institutions,
        institution.institutionId,
        institution
      )
    }
    venues.foreach { venue =>
      mapSink.count(PipelineCounter.InputRecords)
      mapSink.count(PipelineCounter.AcceptedRecords)
      mapSink.emitRecord(TaskSink.venues, venue.venueId, venue)
    }
    val reduceSink = new RecordingSink
    mapSink.counterValues.foreach { case (counter, value) =>
      if counter != PipelineCounter.RejectedRecords then
        reduceSink.count(counter, value)
    }
    mapSink.rejects.foreach(reduceSink.reject)
    shuffle(mapSink.records).foreach { case (label, key, values) =>
      val distinct = values.distinct
      if distinct.size < values.size then
        reduceSink.count(
          PipelineCounter.DuplicateRecords,
          (values.size - distinct.size).toLong
        )
      if distinct.size > 1 then
        reduceSink.count(PipelineCounter.ConflictRecords)
      reduceSink.emit(label, key, distinct.min)
    }
    outcome(Stage.graphExport, reduceSink)

  /** The whole chain over bounded local inputs. */
  def runAll(
      dblpXml: String,
      external: Map[String, Vector[String]],
      run: RunContext
  ): PipelineObservations =
    val dblp = extractDblp(dblpXml, run)
    val normalized = external.keys.toVector.sorted.map(dataset =>
      normalize(dataset, external(dataset), run)
    )
    val ingestion = dblp +: normalized
    val works = ingestion
      .flatMap(_.records(TaskSink.works))
      .map(ContractWire.decode[WorkRecord])
    val researchers = ingestion
      .flatMap(_.records(TaskSink.researchers))
      .map(ContractWire.decode[CanonicalResearcher])
    val topics = ingestion
      .flatMap(_.records(TaskSink.topics))
      .map(ContractWire.decode[Topic])
    val institutions = ingestion
      .flatMap(_.records(TaskSink.institutions))
      .map(ContractWire.decode[Institution])
    val venues = ingestion
      .flatMap(_.records(TaskSink.venues))
      .map(ContractWire.decode[Venue])

    val join = joinIdentifiers(works, run)
    val blocked = block(works, run)
    val envelopes = blocked
      .records(TaskSink.blockingMembers)
      .map(ContractWire.decode[BlockEnvelope])
    val candidates = buildCandidates(envelopes, run)
    val teams = expandTeams(works, run)
    val exported =
      exportGraph(works, researchers, topics, institutions, venues, run)

    val stages =
      ingestion ++ Vector(join, blocked, candidates, teams, exported)

    val groups = blocked
      .records(TaskSink.blockingGroups)
      .map(ContractWire.decode[BlockingGroup])
    val largestGroup = groups.maxByOption(_.observedSize)
    val largestTeam = works.maxByOption(_.authors.size)

    PipelineObservations(
      stages = stages,
      // Keyed by stage and label, because the same label is written by more
      // than one stage and a label-only key would silently drop all but one.
      partitionCounts = stages.iterator
        .flatMap(stageOutcome =>
          stageOutcome.emitted.iterator.map { case (label, entries) =>
            s"${stageOutcome.stage}/$label" -> entries
              .map(entry => partitionOf(entry._1, run.partitionCount))
              .groupBy(identity)
              .map { case (partition, hits) => partition -> hits.size }
          }
        )
        .toMap,
      maximumAuthorTeamSize = largestTeam.fold(0)(_.authors.size),
      maximumAuthorTeamWorkId = largestTeam.fold("")(_.workId),
      largestPairExpansion = works
        .map(work => TeamExpansion.expectedPairCount(work.authors.size))
        .maxOption
        .getOrElse(0L),
      largestBlockingGroup = largestGroup.fold(0)(_.observedSize),
      largestBlockingGroupKey = largestGroup.fold("")(_.blockKey)
    )

  /** Reads the bounded development inputs. Local paths only; nothing here
    * reaches a network or a cloud object store.
    */
  def readLocal(
      samplesDirectory: Path
  ): (String, Map[String, Vector[String]]) =
    val xml = Files.readString(samplesDirectory.resolve("dblp.xml"), UTF_8)
    val datasets = Vector("openalex", "orcid", "crossref").map { dataset =>
      val path = samplesDirectory.resolve(s"$dataset.jsonl")
      dataset -> Files.readAllLines(path, UTF_8).asScala.toVector
    }.toMap
    (xml, datasets)
