package scholarchain.er

import java.lang.management.{ManagementFactory, MemoryType}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.core.{
  AuthorMention,
  CanonicalResearcher,
  ContractSchema,
  Provenance,
  SourceType,
  WorkRecord
}
import scholarchain.core.pairing.MatchCandidate
import scholarchain.er.llm.{
  AdjudicationPrompt,
  InMemoryAdjudicationCache,
  LlmAdjudicator,
  OfflineStubModel
}
import scholarchain.er.matching.{
  EntityKind,
  IdComparison,
  Matcher,
  Policy,
  RecordIndex,
  ResolutionRecord,
  RunInfo,
  SideRecord
}
import scholarchain.er.pairs.{AtomicFile, Cli, Hashing, InputFiles, Locations}
import upickle.default.{read as readJson, write as writeJson}

/** The input paths of one Hadoop run, derived from the run root alone. The
  * batch accepts no other input path, so nothing outside these four stage
  * outputs can be read as a record, a candidate or a link.
  */
final case class BatchInputs(runRoot: Path):
  val candidates: Path =
    runRoot.resolve("stage=candidate-features/match-candidates/v1")
  val works: Path = runRoot.resolve("stage=graph-export/works/v1")
  val authorMentions: Path =
    runRoot.resolve("stage=graph-export/author-mentions/v1")
  val researchers: Path = runRoot.resolve("stage=graph-export/researchers/v1")

  def all: Vector[(String, Path)] =
    Vector(
      "match-candidates" -> candidates,
      "works" -> works,
      "author-mentions" -> authorMentions,
      "researchers" -> researchers
    )

  def snapshot: Either[String, String] =
    val name = runRoot.toAbsolutePath.normalize.getFileName.toString
    Either.cond(
      name.startsWith("snapshot=") && name.length > "snapshot=".length,
      name.drop("snapshot=".length),
      s"The run root must be a snapshot=<id> directory, not $name"
    )

final case class BatchConfig(
    runRoot: Path,
    outRoot: Path,
    policy: Policy = Policy.v1,
    decisionParts: Int = 4,
    linkParts: Int = 4
):
  require(decisionParts > 0 && linkParts > 0, "Part counts must be positive")

final case class OutputFile(path: String, lines: Long, sha256: String)

final case class BatchResult(
    runId: String,
    runDir: Path,
    manifest: ujson.Obj,
    summary: Vector[String],
    metrics: ujson.Obj,
    integrityFailures: Int
)

/** Runs the Phase 5 matcher over every Hadoop match candidate and writes the
  * decisions, the accepted-link artifact, a manifest and a summary.
  *
  * Output is deterministic: the run ID derives from the input digests and the
  * policy version, the decision time is the fixed epoch (a substitute for the
  * wall clock, which would break reproducibility), every list is sorted and
  * every map is written in key order. Wall-clock time, memory and the start
  * time are written only to run-metrics.json, which is not part of the logical
  * output.
  *
  * Memory is bounded by the distinct pairs, not by the decision payload: a
  * candidate is slimmed as it is read, a decision is written as it is made, and
  * only each pair's two IDs and outcome are kept for clustering.
  */
object ErBatch:
  val batchVersion = "entity-resolution-batch/1.0.0"
  val manifestType = "scholarchain.er.batch-manifest/1"
  val stubModel = "offline-stub/abstain-1"
  val decidedAt: Instant = Instant.EPOCH
  private val usage =
    "Usage: ErBatch --run-root <snapshot=... directory> --out-root <dir>"

  /** A run ID of the shape the contracts require, derived from a seed. */
  def runIdFor(seed: String): String =
    val h = Hashing.sha256Hex(seed)
    val head = s"${h.take(8)}-${h.slice(8, 12)}"
    val tail = s"8${h.slice(17, 20)}-${h.slice(20, 32)}"
    s"run:v1:$head-4${h.slice(13, 16)}-$tail"

  def parseArgs(args: Seq[String]): Either[String, BatchConfig] =
    for
      options <- Cli.parse(args)
      _ <- Cli
        .unknown(options, Set("--run-root", "--out-root"))
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      runRoot <- options.get("--run-root").toRight("--run-root missing")
      outRoot <- options.get("--out-root").toRight("--out-root missing")
    yield BatchConfig(Paths.get(runRoot), Paths.get(outRoot))

  private def decoded[A: upickle.default.Reader](
      lines: Iterator[(String, Path)],
      what: String
  ): Iterator[A] =
    lines.zipWithIndex
      .filter { case ((line, _), _) => line.trim.nonEmpty }
      .map { case ((line, file), number) =>
        try readJson[A](line)
        catch
          case error: Exception =>
            throw new IllegalArgumentException(
              s"Invalid $what on line ${number + 1}: ${error.getMessage} " +
                s"(in ${file.getFileName})",
              error
            )
      }

  private def stream[A: upickle.default.Reader, B](path: Path, what: String)(
      use: Iterator[A] => B
  ): B =
    InputFiles.withLines(path)(lines => use(decoded[A](lines, what)))

  private final class Tally:
    val kindOutcome = mutable.TreeMap.empty[String, Long]
    val paths = mutable.TreeMap.empty[String, Long]
    val pathOutcome = mutable.TreeMap.empty[String, Long]
    val reasons = mutable.TreeMap.empty[String, mutable.TreeMap[String, Long]]
    val outcomes = mutable.TreeMap.empty[String, Long]

    private def bump[K](map: mutable.Map[K, Long], key: K): Unit =
      map(key) = map.getOrElse(key, 0L) + 1L

    def add(record: ResolutionRecord): Unit =
      val outcome = record.decision.decision.wire
      bump(outcomes, outcome)
      bump(kindOutcome, s"${record.kind.wire}|$outcome")
      bump(paths, record.path.wire)
      bump(pathOutcome, s"${record.path.wire}|$outcome")
      val byReason = reasons.getOrElseUpdate(outcome, mutable.TreeMap.empty)
      record.decision.reasonCodes.distinct.foreach(bump(byReason, _))

  private def counts(map: collection.Map[String, Long]): ujson.Obj =
    ujson.Obj.from(
      map.toVector.sortBy(_._1).map((key, n) => key -> ujson.Num(n.toDouble))
    )

  private def num(n: Long): ujson.Value = ujson.Num(n.toDouble)
  private def str(value: String): ujson.Value = ujson.Str(value)
  private def optional(value: Option[String]): ujson.Value =
    value.fold(ujson.Null: ujson.Value)(ujson.Str(_))
  private def strings(values: Iterable[String]): ujson.Value =
    ujson.Arr.from(values.map(str))

  private def provenanceJson(
      policy: Policy,
      runId: String,
      snapshot: String,
      sourceRecordId: String,
      artifact: String
  ): ujson.Value =
    ujson.read(
      writeJson(
        Provenance(
          ContractSchema.currentVersion,
          SourceType.Derived,
          s"hadoop-snapshot:$snapshot",
          sourceRecordId,
          s"scholarchain:er-batch/${policy.version}/$artifact",
          batchVersion,
          runId
        )
      )
    )

  /** Writes `parts` files of consecutive lines. The iterator is consumed once,
    * lazily, so a part holds only the line being written.
    */
  private def writeParts(
      directory: Path,
      parts: Int,
      total: Int,
      lines: Iterator[String],
      root: Path
  ): Vector[OutputFile] =
    val chunk = math.max(1, (total + parts - 1) / parts)
    (0 until parts).toVector.map { number =>
      val file = directory.resolve(f"part-$number%05d")
      var written = 0L
      val mine = lines.take(chunk).map { line =>
        written += 1
        line
      }
      AtomicFile.writeLines(file, new Iterable[String] { def iterator = mine })
      OutputFile(
        root.relativize(file).toString,
        written,
        Hashing.sha256File(file)
      )
    }

  private def histogram(sizes: Vector[Int]): ujson.Obj =
    ujson.Obj.from(
      sizes
        .groupBy(identity)
        .toVector
        .sortBy(_._1)
        .map((size, group) => size.toString -> num(group.size.toLong))
    )

  private def clusterStats(
      all: Vector[Cluster],
      result: ClusterResult
  ): ujson.Obj =
    val merged = all.filter(_.status == LinkStatus.Merged)
    val failed = all.filter(_.failed)
    val largest = all
      .filter(_.size > 1)
      .sortBy(c => (-c.size, c.anchor))
      .take(10)
      .map(c =>
        ujson.Obj(
          "anchor" -> c.anchor,
          "size" -> c.size,
          "status" -> c.status.wire
        )
      )
    ujson.Obj(
      "clusters" -> num(all.size.toLong),
      "mergedClusters" -> num(merged.size.toLong),
      "membersInMergedClusters" -> num(merged.map(_.size.toLong).sum),
      "mergedSizeHistogram" -> histogram(merged.map(_.size)),
      "externalIdSingletons" ->
        num(all.count(_.status == LinkStatus.ExternalId).toLong),
      "unlinked" -> num(all.count(_.status == LinkStatus.Unlinked).toLong),
      "integrityFailedClusters" -> num(failed.size.toLong),
      "membersInFailedClusters" -> num(failed.map(_.size.toLong).sum),
      "failedSizeHistogram" -> histogram(failed.map(_.size)),
      "failuresByType" -> counts(
        failed
          .flatMap(_.failures)
          .groupBy(_.failureType.wire)
          .map((name, group) => name -> group.size.toLong)
      ),
      "largestClusters" -> ujson.Arr.from(largest),
      "selfLinksIgnored" -> num(result.selfLinksIgnored.toLong),
      "unknownEndpointsIgnored" -> num(result.unknownEndpoints.toLong),
      "rejectedInsideClusters" -> num(result.rejectedInside.toLong),
      "unresolvedInsideClusters" -> num(result.unresolvedInside.toLong),
      "conflictInsideClusters" -> num(result.conflictInside.toLong)
    )

  private def memberOf(side: SideRecord): Member =
    Member(
      side.id,
      side.workId,
      side.identifiers.values
        .filter((namespace, _) => IdComparison.isAuthoritative(namespace))
        .map((namespace, values) => namespace.wire -> values)
    )

  private def gitHead(): Option[String] =
    try
      Locations.repoRoot(Paths.get("")).flatMap { root =>
        val git = root.resolve(".git")
        val head = git.resolve("HEAD")
        if !Files.isRegularFile(head) then None
        else
          val text = Files.readString(head, UTF_8).trim
          if !text.startsWith("ref: ") then Some(text)
          else
            val ref = text.drop("ref: ".length)
            val loose = git.resolve(ref)
            if Files.isRegularFile(loose) then
              Some(Files.readString(loose, UTF_8).trim)
            else
              val packed = git.resolve("packed-refs")
              if !Files.isRegularFile(packed) then None
              else
                Files
                  .readAllLines(packed, UTF_8)
                  .asScala
                  .find(_.endsWith(s" $ref"))
                  .map(_.takeWhile(_ != ' '))
      }
    catch case NonFatal(_) => None

  private def sourcesDigest(): Option[String] =
    try
      Locations
        .repoRoot(Paths.get(""))
        .map(_.resolve("modules/entity-resolution/src/main"))
        .filter(Files.isDirectory(_))
        .map(InputFiles.sha256)
    catch case NonFatal(_) => None

  private def peakHeapBytes(): Long =
    ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == MemoryType.HEAP)
      .map(_.getPeakUsage.getUsed)
      .sum

  def run(config: BatchConfig, wallClock: () => Instant): BatchResult =
    val started = System.nanoTime()
    val startedAt = wallClock()
    val phases = mutable.LinkedHashMap.empty[String, Long]
    def timed[A](name: String)(body: => A): A =
      val begin = System.nanoTime()
      try body
      finally phases(name) = (System.nanoTime() - begin) / 1000000L
    val policy = config.policy
    val inputs = BatchInputs(config.runRoot)
    val snapshot =
      inputs.snapshot.fold(m => throw new IllegalArgumentException(m), identity)
    val digests = timed("input-digests") {
      inputs.all.map((name, path) => name -> InputFiles.sha256(path)).toMap
    }
    val runId = runIdFor(
      s"$batchVersion|${policy.version}|" +
        inputs.all.map((name, _) => s"$name:${digests(name)}").mkString("|")
    )
    val runDir = config.outRoot
      .resolve(s"run=${runId.replace(':', '-')}")
      .resolve(s"snapshot=$snapshot")
    Locations.check(runDir, inputs = Vector(config.runRoot)).left.foreach { m =>
      throw new IllegalArgumentException(s"Refusing to write there: $m")
    }
    Files.createDirectories(runDir)
    Files.deleteIfExists(runDir.resolve("_SUCCESS"))
    val runInfo = RunInfo(runId, decidedAt)

    // Records: every work and, inside each, its mentions.
    var duplicateRecordIds = 0L
    var worksRead = 0L
    val snapshots = mutable.TreeSet.empty[String]
    val sides = mutable.HashMap.empty[String, SideRecord]
    timed("read-works") {
      stream[WorkRecord, Unit](inputs.works, "work record") { works =>
        works.foreach { work =>
          worksRead += 1
          snapshots += work.provenance.snapshotId
          val entries = Iterator(work.workId -> SideRecord.fromWork(work)) ++
            work.authors.iterator.map(mention =>
              mention.mentionId -> SideRecord.fromMention(mention, work)
            )
          entries.foreach { (id, side) =>
            if sides.contains(id) then duplicateRecordIds += 1
            sides(id) = side
          }
        }
      }
    }
    val index = RecordIndex(sides.toMap)

    // Candidates: slimmed as read, one entry per distinct pair, smallest block
    // key kept, as Matcher.resolve does.
    var candidatesRead = 0L
    val candidateRunIds = mutable.TreeSet.empty[String]
    val interned = mutable.HashMap.empty[Provenance, Provenance]
    val distinct = mutable.HashMap.empty[(String, String), MatchCandidate]
    timed("read-candidates") {
      stream[MatchCandidate, Unit](inputs.candidates, "candidate") { found =>
        found.foreach { candidate =>
          candidatesRead += 1
          candidateRunIds += candidate.provenance.runId
          val key = (candidate.leftId, candidate.rightId)
          val keep = distinct.get(key).forall(_.blockKey > candidate.blockKey)
          if keep then
            distinct(key) = candidate.copy(
              exactIdentifierAgreements = Vector.empty,
              exactIdentifierConflicts = Vector.empty,
              features = Map.empty,
              provenance = interned.getOrElseUpdate(
                candidate.provenance,
                candidate.provenance
              )
            )
        }
      }
    }
    if distinct.isEmpty then
      throw new IllegalStateException(
        s"No match candidates under ${inputs.candidates}"
      )
    val ordered = distinct.values.toVector.sortBy(c => (c.leftId, c.rightId))
    distinct.clear()
    interned.clear()

    // Decisions: written as they are made.
    val matcher = Matcher(policy, runInfo)
    val judge = LlmAdjudicator(
      OfflineStubModel.abstaining(stubModel),
      InMemoryAdjudicationCache(),
      policy,
      runInfo
    )
    val tally = Tally()
    val researcherEdges = mutable.ArrayBuffer.empty[DecisionEdge]
    val workEdges = mutable.ArrayBuffer.empty[DecisionEdge]
    var eligibleForModel = 0L
    var sideMissing = 0L
    var invalidCandidates = 0L
    def decide(candidate: MatchCandidate): String =
      val first = matcher.resolve(Vector(candidate), index).head
      if first.decision.reasonCodes.contains(Matcher.SideMissing) then
        sideMissing += 1
      if first.decision.reasonCodes.contains(Matcher.InvalidCandidate) then
        invalidCandidates += 1
      val record =
        (index.get(candidate.leftId), index.get(candidate.rightId)) match
          case (Some(left), Some(right)) =>
            if judge.eligible(first) then eligibleForModel += 1
            judge.refine(first, left, right)
          case _ => first
      tally.add(record)
      val edge = DecisionEdge(
        record.leftId,
        record.rightId,
        record.decision.decision
      )
      if record.kind == EntityKind.Researcher then researcherEdges += edge
      else workEdges += edge
      writeJson(record)
    val decisionFiles = timed("decide-and-write") {
      writeParts(
        runDir.resolve("decisions/v1"),
        config.decisionParts,
        ordered.size,
        ordered.iterator.map(decide),
        runDir
      )
    }

    // Records the Hadoop export also lists, for cross-checks only.
    var mentionRows = 0L
    var mentionsNotInWorks = 0L
    timed("cross-check-exports") {
      stream[AuthorMention, Unit](inputs.authorMentions, "author mention") {
        rows =>
          rows.foreach { row =>
            mentionRows += 1
            if !index.get(row.mentionId).exists(_.kind == EntityKind.Researcher)
            then mentionsNotInWorks += 1
          }
      }
    }
    val exportedResearchers = mutable.HashSet.empty[String]
    stream[CanonicalResearcher, Unit](inputs.researchers, "researcher") {
      rows =>
        rows.foreach(row => exportedResearchers += row.researcherId)
    }

    // Clusters and the accepted-link artifact.
    val researcherSides =
      sides.values.filter(_.kind == EntityKind.Researcher).toVector.sortBy(_.id)
    val workSides =
      sides.values.filter(_.kind == EntityKind.Work).toVector.sortBy(_.id)
    val researcherResult = timed("cluster-researchers") {
      Clusters.build(
        EntityKind.Researcher,
        researcherSides.map(memberOf),
        researcherEdges.toVector
      )
    }
    val workResult = timed("cluster-works") {
      Clusters.build(
        EntityKind.Work,
        workSides.map(memberOf),
        workEdges.toVector
      )
    }
    val researcherClusterOf =
      researcherResult.clusters.flatMap(c => c.members.map(_ -> c)).toMap
    val workClusterOf =
      workResult.clusters.flatMap(c => c.members.map(_ -> c)).toMap
    def prov(id: String, artifact: String): ujson.Value =
      provenanceJson(policy, runId, snapshot, id, artifact)

    val linkRoot = runDir.resolve("accepted-links/v1")
    val linkFiles = timed("write-links") {
      val researcherLinks = writeParts(
        linkRoot.resolve("researcher-links"),
        config.linkParts,
        researcherSides.size,
        researcherSides.iterator.map { side =>
          val cluster = researcherClusterOf(side.id)
          ujson.write(
            ujson.Obj(
              "schemaVersion" -> num(1),
              "mentionId" -> side.id,
              "workId" -> side.workId,
              "canonicalResearcherId" -> optional(cluster.canonicalId),
              "status" -> cluster.status.wire,
              "idRule" -> optional(cluster.idRule),
              "clusterAnchor" -> cluster.anchor,
              "clusterSize" -> num(cluster.size.toLong),
              "provenance" -> prov(side.id, "researcher-links")
            )
          )
        },
        runDir
      )
      val workLinks = writeParts(
        linkRoot.resolve("work-links"),
        config.linkParts,
        workSides.size,
        workSides.iterator.map { side =>
          val cluster = workClusterOf(side.id)
          ujson.write(
            ujson.Obj(
              "schemaVersion" -> num(1),
              "workId" -> side.id,
              "canonicalWorkId" ->
                (if cluster.failed then side.id
                 else cluster.canonicalId.getOrElse(side.id)),
              "status" -> cluster.status.wire,
              "clusterAnchor" -> cluster.anchor,
              "clusterSize" -> num(cluster.size.toLong),
              "provenance" -> prov(side.id, "work-links")
            )
          )
        },
        runDir
      )
      val canonical = researcherResult.clusters
        .filter(_.canonicalId.isDefined)
        .sortBy(_.canonicalId.get)
      val canonicalResearchers = writeParts(
        linkRoot.resolve("canonical-researchers"),
        1,
        canonical.size,
        canonical.iterator.map { cluster =>
          ujson.write(
            ujson.Obj(
              "schemaVersion" -> num(1),
              "canonicalResearcherId" -> cluster.canonicalId.get,
              "idRule" -> optional(cluster.idRule),
              "orcid" -> optional(cluster.orcid),
              "status" -> cluster.status.wire,
              "memberCount" -> num(cluster.size.toLong),
              "memberMentionIds" -> strings(cluster.members),
              "inResearchersExport" ->
                ujson.Bool(exportedResearchers(cluster.canonicalId.get)),
              "provenance" -> prov(cluster.anchor, "canonical-researchers")
            )
          )
        },
        runDir
      )
      researcherLinks ++ workLinks ++ canonicalResearchers
    }
    val failedClusters =
      (researcherResult.clusters ++ workResult.clusters).filter(_.failed)
    val failureRows = failedClusters.flatMap(cluster =>
      cluster.failures.map(failure => cluster -> failure)
    )
    val failureFiles = timed("write-integrity-failures") {
      writeParts(
        runDir.resolve("integrity-failures/v1"),
        1,
        failureRows.size,
        failureRows.iterator.map { (cluster, failure) =>
          ujson.write(
            ujson.Obj(
              "schemaVersion" -> num(1),
              "kind" -> cluster.kind.wire,
              "failureType" -> failure.failureType.wire,
              "clusterAnchor" -> cluster.anchor,
              "clusterSize" -> num(cluster.size.toLong),
              "memberIds" -> strings(cluster.members),
              "detail" -> strings(failure.detail),
              "action" -> "not merged: no canonical researcher ID, or each work maps to itself",
              "provenance" -> prov(cluster.anchor, "integrity-failures")
            )
          )
        },
        runDir
      )
    }

    val canonicalRows =
      researcherResult.clusters.filter(_.canonicalId.isDefined)
    val orcidCanonical =
      canonicalRows.filter(_.idRule.contains(Clusters.orcidRule))
    val sources = sourcesDigest()
    val manifest = ujson.Obj(
      "schemaVersion" -> num(1),
      "manifestType" -> manifestType,
      "batchVersion" -> batchVersion,
      "runId" -> runId,
      "snapshot" -> snapshot,
      "policyVersion" -> policy.version,
      "thresholds" -> ujson.Obj(
        "accept" -> policy.accept,
        "reject" -> policy.reject,
        "llmBandLow" -> policy.llmBandLow,
        "llmMinConfidence" -> policy.llmMinConfidence
      ),
      "llm" -> ujson.Obj(
        "model" -> stubModel,
        "promptVersion" -> AdjudicationPrompt.version,
        "networkCalls" -> num(0),
        "eligibleForModel" -> num(eligibleForModel),
        "modelCalls" -> num(judge.modelCalls.toLong)
      ),
      "decidedAt" ->
        s"${decidedAt.toString} (fixed epoch: a deterministic substitute for the wall clock)",
      "canonicalResearcherIdRule" -> ujson.Obj(
        "orcid" -> "exactly one validated ORCID iD in the cluster: StableIds.researcherFromExternal(orcid, iD)",
        "identity" -> s"merged cluster with no ORCID: StableIds.researcherFromIdentity(${Clusters.identityVersion}, smallest member mention ID)",
        "none" -> "a lone mention with no ORCID, and every member of a failed cluster"
      ),
      "inputs" -> ujson.Obj(
        "runRoot" -> config.runRoot.toString,
        "paths" -> ujson.Obj.from(
          inputs.all.map((name, path) =>
            name -> ujson.Obj(
              "path" -> path.toString,
              "sha256" -> digests(name)
            )
          )
        ),
        "hadoopRunIds" -> strings(candidateRunIds),
        "datasetSnapshots" -> strings(snapshots)
      ),
      "counts" -> ujson.Obj(
        "candidatesRead" -> num(candidatesRead),
        "distinctPairs" -> num(ordered.size.toLong),
        "duplicateCandidatesDropped" -> num(candidatesRead - ordered.size),
        "worksRead" -> num(worksRead),
        "recordsIndexed" -> num(index.size.toLong),
        "duplicateRecordIds" -> num(duplicateRecordIds),
        "researcherMentionRecords" -> num(researcherSides.size.toLong),
        "workRecords" -> num(workSides.size.toLong),
        "authorMentionRows" -> num(mentionRows),
        "authorMentionRowsNotInWorks" -> num(mentionsNotInWorks),
        "researcherRows" -> num(exportedResearchers.size.toLong),
        "pairsWithMissingRecords" -> num(sideMissing),
        "invalidCandidates" -> num(invalidCandidates)
      ),
      "decisions" -> ujson.Obj(
        "total" -> num(tally.outcomes.values.sum),
        "byOutcome" -> counts(tally.outcomes),
        "byKindAndOutcome" -> counts(tally.kindOutcome),
        "byPath" -> counts(tally.paths),
        "byPathAndOutcome" -> counts(tally.pathOutcome),
        "reasonCodesByOutcome" -> ujson.Obj.from(
          tally.reasons.toVector.map((outcome, map) => outcome -> counts(map))
        )
      ),
      "clusters" -> ujson.Obj(
        "researcher" -> clusterStats(
          researcherResult.clusters,
          researcherResult
        ),
        "work" -> clusterStats(workResult.clusters, workResult),
        "canonicalResearchers" -> ujson.Obj(
          "total" -> num(canonicalRows.size.toLong),
          "orcidRule" -> num(orcidCanonical.size.toLong),
          "orcidRuleInResearchersExport" ->
            num(
              orcidCanonical
                .count(c => exportedResearchers(c.canonicalId.get))
                .toLong
            ),
          "identityRule" ->
            num(
              canonicalRows
                .count(_.idRule.contains(Clusters.identityRule))
                .toLong
            )
        )
      ),
      "integrityFailures" -> ujson.Obj(
        "clusters" -> num(failedClusters.size.toLong),
        "records" -> num(failureRows.size.toLong)
      ),
      "code" -> ujson.Obj(
        "gitHead" -> optional(gitHead()),
        "entityResolutionMainSourcesSha256" -> optional(sources),
        "note" -> "gitHead is the checked-out commit; the sources digest covers the working tree"
      ),
      "outputs" -> ujson.Arr.from(
        (decisionFiles ++ linkFiles ++ failureFiles).map(f =>
          ujson.Obj(
            "path" -> f.path,
            "lines" -> num(f.lines),
            "sha256" -> f.sha256
          )
        )
      )
    )
    val summary = Vector(
      s"ER-BATCH run-id: $runId",
      s"ER-BATCH policy: ${policy.version}",
      s"ER-BATCH candidates read: $candidatesRead; distinct pairs: ${ordered.size}; duplicates dropped: ${candidatesRead - ordered.size}",
      s"ER-BATCH records indexed: ${index.size} (works $worksRead; researcher mentions ${researcherSides.size}; duplicate ids $duplicateRecordIds)",
      s"ER-BATCH decisions: ACCEPTED ${tally.outcomes.getOrElse("ACCEPTED", 0L)} REJECTED ${tally.outcomes
          .getOrElse("REJECTED", 0L)} UNRESOLVED ${tally.outcomes
          .getOrElse("UNRESOLVED", 0L)} CONFLICT ${tally.outcomes.getOrElse("CONFLICT", 0L)}",
      s"ER-BATCH paths: exact-id ${tally.paths.getOrElse("exact-id", 0L)} deterministic-bibliographic ${tally.paths
          .getOrElse("deterministic-bibliographic", 0L)} probabilistic ${tally.paths
          .getOrElse("probabilistic", 0L)} llm-adjudicated ${tally.paths.getOrElse("llm-adjudicated", 0L)}",
      s"ER-BATCH llm: eligible $eligibleForModel, model calls ${judge.modelCalls}, network calls 0",
      s"ER-BATCH researcher clusters: merged ${researcherResult.clusters.count(_.status == LinkStatus.Merged)}, external-id singletons ${researcherResult.clusters
          .count(_.status == LinkStatus.ExternalId)}, unlinked ${researcherResult.clusters
          .count(_.status == LinkStatus.Unlinked)}, integrity-failed ${researcherResult.clusters.count(_.failed)}",
      s"ER-BATCH work clusters: merged ${workResult.clusters.count(_.status == LinkStatus.Merged)}, unlinked ${workResult.clusters
          .count(_.status == LinkStatus.Unlinked)}, integrity-failed ${workResult.clusters.count(_.failed)}",
      s"ER-BATCH integrity failures: clusters ${failedClusters.size}, records ${failureRows.size}"
    )
    val metrics = ujson.Obj(
      "startedAt" -> startedAt.toString,
      "elapsedMillis" -> num((System.nanoTime() - started) / 1000000L),
      "phaseMillis" -> ujson.Obj.from(
        phases.toVector.map((name, ms) => name -> num(ms))
      ),
      "peakHeapBytesSumOfPools" -> num(peakHeapBytes()),
      "maxHeapBytes" -> num(Runtime.getRuntime.maxMemory()),
      "availableProcessors" -> num(
        Runtime.getRuntime.availableProcessors().toLong
      ),
      "javaVersion" -> System.getProperty("java.version"),
      "note" -> "peak heap is the JVM's, which under sbt includes sbt itself"
    )
    AtomicFile.writeLines(
      runDir.resolve("manifest.json"),
      Vector(ujson.write(manifest, indent = 2))
    )
    AtomicFile.writeLines(runDir.resolve("summary.txt"), summary)
    AtomicFile.writeLines(
      runDir.resolve("run-metrics.json"),
      Vector(ujson.write(metrics, indent = 2))
    )
    AtomicFile.writeLines(
      runDir.resolve("_SUCCESS"),
      Vector(Hashing.sha256File(runDir.resolve("manifest.json")))
    )
    BatchResult(
      runId,
      runDir,
      manifest,
      summary,
      metrics,
      failedClusters.size
    )

  def main(args: Array[String]): Unit =
    parseArgs(args.toSeq) match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        println(s"ER-BATCH RESULT: FAILED $message")
        sys.exit(2)
      case Right(config) =>
        Locations.check(
          config.outRoot,
          inputs = Vector(config.runRoot)
        ) match
          case Left(message) =>
            println(
              s"ER-BATCH RESULT: FAILED refusing to write there: $message"
            )
            sys.exit(2)
          case Right(_) =>
            try
              val result = run(config, () => Instant.now())
              println(s"ER-BATCH output: ${result.runDir}")
              result.summary.foreach(println)
              println(
                s"ER-BATCH elapsed ms: ${result.metrics("elapsedMillis").num.toLong}; peak heap bytes (sum of pools): ${result.metrics("peakHeapBytesSumOfPools").num.toLong}; max heap bytes: ${result.metrics("maxHeapBytes").num.toLong}"
              )
              println("ER-BATCH RESULT: SUCCESS")
            catch
              case NonFatal(error) =>
                println(
                  s"ER-BATCH RESULT: FAILED ${error.getClass.getSimpleName}: ${error.getMessage}"
                )
                sys.exit(1)
