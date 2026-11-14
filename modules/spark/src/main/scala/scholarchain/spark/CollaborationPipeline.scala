package scholarchain.spark

import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.StructType
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Facts about the code that produced a run. */
object CodeInfo:
  def gitHead(repository: Option[Path]): String =
    repository
      .flatMap { root =>
        val head = root.resolve(".git/HEAD")
        if !Files.isRegularFile(head) then None
        else
          val text = Files.readString(head, UTF_8).trim
          if !text.startsWith("ref: ") then Some(text)
          else
            val ref = root.resolve(".git").resolve(text.drop("ref: ".length))
            if Files.isRegularFile(ref) then
              Some(Files.readString(ref, UTF_8).trim)
            else None
      }
      .getOrElse("unavailable")

  /** Digest of the module's main sources, or "unavailable" outside a checkout.
    */
  def sourcesDigest(repository: Option[Path]): String =
    repository
      .map(_.resolve("modules/spark/src/main/scala"))
      .filter(Files.isDirectory(_))
      .map { root =>
        val stream = Files.walk(root)
        try
          val files = stream.iterator.asScala.toVector
            .filter(p =>
              Files.isRegularFile(p) && p.toString.endsWith(".scala")
            )
            .sortBy(p => root.relativize(p).toString)
          Digests.sha256Hex(
            files
              .map(p => s"${root.relativize(p)} ${Digests.sha256File(p)}")
              .mkString("\n")
          )
        finally stream.close()
      }
      .getOrElse("unavailable")

  val unavailable = "unavailable"

  /** The `code` entry of the run manifest. The digest is in the run-ID seed
    * only when it is available; otherwise the run ID does not change with the
    * code and the entry says so.
    */
  def manifestEntry(gitHead: String, sourcesDigest: String): ujson.Obj =
    val inSeed = sourcesDigest != unavailable
    ujson.Obj(
      "gitHead" -> gitHead,
      "sparkMainSourcesSha256" -> sourcesDigest,
      "inRunIdSeed" -> inSeed,
      "runIdTracksCode" -> (if inSeed then
                              "yes: the sources digest is in the run-ID seed"
                            else
                              "no: the sources digest is unavailable, so the run ID does not change with code; only transformVersion separates code versions")
    )

/** Accumulates invariant results. An error stops the run from reporting
  * success; a warning is recorded and reported without failing it.
  */
final class Invariants:
  val items = mutable.ArrayBuffer.empty[ujson.Obj]

  def record(
      name: String,
      value: Long,
      severity: String,
      detail: String
  ): Unit =
    val status =
      if value == 0L then "pass"
      else if severity == "error" then "fail"
      else "warn"
    items += ujson.Obj(
      "name" -> name,
      "value" -> ujson.Num(value.toDouble),
      "status" -> status,
      "detail" -> detail
    )

  def failures: Vector[String] =
    items.toVector.filter(_("status").str == "fail").map(_("name").str)

  def warnings: Vector[String] =
    items.toVector.filter(_("status").str == "warn").map(_("name").str)

/** The Spark collaboration-analysis pipeline (course jobs 1 to 11).
  *
  * Reads the accepted ER links and the Hadoop exports of one snapshot, builds
  * the canonical tables and the temporal weighted coauthorship graph, computes
  * metrics, components, communities, bridge measures, coreness, paths, temporal
  * change and summaries, and writes partitioned Parquet, load manifests, plans,
  * stage metrics and a run manifest under `<out-root>/run=<run id>/`.
  */
object CollaborationPipeline:
  val resultPrefix = "SPARK-PIPELINE RESULT: "
  private val depthCap = 64

  /** Accepted-link inputs: a conflicting key there is an error, not a warning.
    */
  val identityInputs: Set[String] =
    Set("researcherLinks", "workLinks", "canonicalResearchers")

  /** Removes the scratch directory of a run after success and keeps it after a
    * failure, for diagnosis.
    */
  def finishWork(runDir: Path, succeeded: Boolean): Unit =
    if succeeded then deleteTree(runDir.resolve("_work"))

  def main(args: Array[String]): Unit =
    val code = run(args.toVector, Console.out, None)
    System.exit(code)

  def run(
      args: Vector[String],
      out: PrintStream,
      provided: Option[SparkSession]
  ): Int =
    def fail(reason: String, code: Int): Int =
      val line = reason.linesIterator.nextOption().getOrElse(reason)
      out.println(s"${resultPrefix}FAILED ${line.slice(0, 600)}")
      code
    PipelineArgs.parse(args) match
      case Left(error) =>
        out.println(PipelineArgs.usage)
        fail(error, 2)
      case Right(config) =>
        val repository = OutputPolicy.repositoryRoot(Paths.get(""))
        val checked = OutputPolicy
          .check(config.outRoot, repository)
          .flatMap(_ =>
            config.eventLogDir
              .map(dir => OutputPolicy.check(dir, repository).map(_ => ()))
              .getOrElse(Right(()))
          )
        val layout = InputLayout(config.erRun, config.hadoopRoot)
        checked match
          case Left(error) => fail(error, 2)
          case Right(_)    =>
            layout.missing match
              case Some(error) => fail(error, 2)
              case None        =>
                try execute(config, layout, repository, out, provided)
                catch
                  case NonFatal(error) =>
                    fail(
                      s"${error.getClass.getSimpleName}: ${error.getMessage}",
                      1
                    )

  private def pairsFile(
      config: PipelineArgs,
      path: Path
  ): Either[String, Vector[(String, String, String)]] =
    val labels = config.windows.map(_.label).toSet
    val lines = Files
      .readAllLines(path, UTF_8)
      .asScala
      .toVector
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
    if lines.length > config.driverRowCap then
      Left(s"Path pairs file has more than ${config.driverRowCap} pairs")
    else
      val parsed = lines.map { line =>
        line.split(",", -1).toVector.map(_.trim) match
          case Vector(window, source, target)
              if labels.contains(
                window
              ) && source.nonEmpty && target.nonEmpty =>
            Right((window, source, target))
          case _ =>
            Left(s"Bad path pair '$line': expected <window>,<from>,<to>")
      }
      parsed.collectFirst { case Left(error) => error } match
        case Some(error) => Left(error)
        case None        => Right(parsed.collect { case Right(pair) => pair })

  private def hadoopRunId(root: Path): String =
    val name = Option(root.toAbsolutePath.normalize.getParent)
      .flatMap(p => Option(p.getFileName))
      .map(_.toString)
      .getOrElse("")
    if name.startsWith("run=run-v1-") then
      "run:v1:" + name.drop("run=run-v1-".length)
    else "unknown"

  private def newSession(
      config: PipelineArgs,
      runDir: Path,
      provided: Option[SparkSession]
  ): SparkSession =
    val work = runDir.resolve("_work")
    config.eventLogDir.foreach(dir => Files.createDirectories(dir))
    val session = provided.getOrElse {
      val builder = SparkMaster(
        SparkSession.builder().appName("scholarchain-spark-analytics"),
        config.explicitMaster
      )
        .config("spark.ui.enabled", "false")
        .config("spark.driver.host", "127.0.0.1")
        .config("spark.driver.bindAddress", "127.0.0.1")
        .config(
          "spark.sql.warehouse.dir",
          work.resolve("warehouse").toUri.toString
        )
        .config("spark.local.dir", work.resolve("local").toString)
        .config("spark.buffer.pageSize", "1m")
      config.eventLogDir.foreach { dir =>
        builder
          .config("spark.eventLog.enabled", "true")
          .config("spark.eventLog.dir", dir.toUri.toString)
      }
      builder.getOrCreate()
    }
    session.conf.set("spark.sql.shuffle.partitions", config.partitions.toString)
    session.conf.set("spark.sql.adaptive.enabled", "false")
    session.conf.set("spark.sql.autoBroadcastJoinThreshold", "-1")
    session.conf.set("spark.sql.session.timeZone", "UTC")
    session.conf.set(
      "spark.sql.sources.partitionColumnTypeInference.enabled",
      "false"
    )
    session

  private def scalar(
      algo: Algo,
      query: String,
      inputs: (String, DataFrame)*
  ): Long =
    algo.rows(algo.sql(query, inputs*), "scalar").head.getLong(0)

  private def deleteTree(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try
        stream.iterator.asScala.toVector
          .sortBy(_.toString)(Ordering[String].reverse)
          .foreach(Files.delete(_))
      finally stream.close()

  private def execute(
      config: PipelineArgs,
      layout: InputLayout,
      repository: Option[Path],
      out: PrintStream,
      provided: Option[SparkSession]
  ): Int =
    val started = System.nanoTime()
    val pairsFromFile = config.pathPairs match
      case Some(path) => pairsFile(config, path).map(Some(_))
      case None       => Right(None)
    if pairsFromFile.isLeft then
      out.println(s"${resultPrefix}FAILED ${pairsFromFile.left.toOption.get}")
      return 2
    val erManifest = ujson.read(Files.readString(layout.erManifest, UTF_8))
    val erRunId = erManifest("runId").str
    val snapshot = erManifest("snapshot").str
    val datasets =
      layout.datasets.map((name, path) => (name, path, Digests.directory(path)))
    val erManifestDigest = Digests.sha256File(layout.erManifest)
    val configText = ujson.write(configJson(config))
    val sourcesDigest = CodeInfo.sourcesDigest(repository)
    val seed = RunContext.seed(
      datasets.map(d => (d._1, d._3)),
      erManifestDigest,
      configText,
      sourcesDigest
    )
    val runId = RunContext.runIdFor(seed)
    val ctx = RunContext(
      runId,
      s"hadoop-snapshot:$snapshot",
      erRunId,
      hadoopRunId(config.hadoopRoot),
      RunContext.transformVersion
    )
    ctx.validated("run-manifest")
    val runDir = config.outRoot.toAbsolutePath.normalize
      .resolve(RunContext.directoryName(runId))
    if Files.exists(runDir) then
      if config.overwrite then deleteTree(runDir)
      else
        out.println(
          s"${resultPrefix}FAILED Output exists: $runDir (use --overwrite)"
        )
        return 2
    Files.createDirectories(runDir)
    val session = newSession(config, runDir, provided)
    val listener = new StageMetricsListener()
    session.sparkContext.addSparkListener(listener)
    var succeeded = false
    try
      val summary = mutable.ArrayBuffer.empty[String]
      def say(line: String): Unit =
        summary += line
        out.println(line)
      say(s"run-id: $runId")
      say(s"output: $runDir")
      say(s"er-run: $erRunId snapshot=$snapshot")
      say(s"hadoop-run: ${ctx.hadoopRunId}")
      val telemetry = new Telemetry()
      val algo = new Algo(
        session,
        telemetry,
        config.maxIterations,
        config.seed,
        config.driverRowCap,
        runDir.resolve("_checkpoints")
      )
      val windowsFrame = Windowing.frame(session, config.windows).persist()
      val invariants = new Invariants()
      val counts = mutable.LinkedHashMap.empty[String, ujson.Value]
      def count(name: String, value: Long): Unit =
        counts(name) = ujson.Num(value.toDouble)
        say(s"count $name=$value")

      val raw = Inputs.load(session, layout)
      val canon = Stages.run(session, "canonical-builder") {
        val built = Canonical.build(session, raw, ctx)
        built.works.count()
        built.authored.count()
        built.researchers.count()
        built
      }
      count("input.researcherLinks", DriverGuard.count(raw.links))
      count("input.workLinks", DriverGuard.count(raw.workLinks))
      count("input.canonicalResearchers", DriverGuard.count(raw.canonical))
      count("input.works", DriverGuard.count(raw.works))
      count("input.authorPapers", DriverGuard.count(raw.authorPapers))
      count("input.pairCandidates", DriverGuard.count(raw.pairs))
      count("input.teams", DriverGuard.count(raw.teams))
      Canonical.duplicateSpecs(raw).foreach { (name, key, frame) =>
        val stats =
          algo.rows(Canonical.duplicateStats(session, frame, key), name).head
        count(s"input.duplicateKeyRows.$name", stats.getLong(0))
        count(s"input.conflictingDuplicateKeys.$name", stats.getLong(1))
      }
      count("canonical.works", DriverGuard.count(canon.works))
      count("canonical.researchers", DriverGuard.count(canon.researchers))
      count("canonical.authored", DriverGuard.count(canon.authored))
      count(
        "canonical.worksWithoutYear",
        scalar(
          algo,
          "SELECT count(*) FROM {w} WHERE year IS NULL",
          "w" -> canon.works
        )
      )
      count(
        "canonical.mergedWorks",
        scalar(
          algo,
          "SELECT count(*) FROM {w} WHERE memberCount > 1",
          "w" -> canon.works
        )
      )
      count(
        "canonical.unresolvedMentionNodes",
        scalar(
          algo,
          "SELECT count(*) FROM {u} WHERE isNode",
          "u" -> canon.unresolved
        )
      )
      count(
        "canonical.unresolvedMentionsInDuplicateRecords",
        scalar(
          algo,
          "SELECT count(*) FROM {u} WHERE NOT isNode",
          "u" -> canon.unresolved
        )
      )
      count(
        "canonical.authoredNotCounted",
        scalar(
          algo,
          "SELECT count(*) FROM {a} WHERE NOT countedInMetrics",
          "a" -> canon.authored
        )
      )

      val coauthor = Stages.run(session, "coauthorship-graph") {
        val built = Coauthorship.build(
          session,
          raw,
          canon,
          windowsFrame,
          ctx,
          config.representativeWorkLimit
        )
        built.edges.count()
        built.metrics.count()
        built
      }
      val pairRows = algo.rows(coauthor.pairCounts, "pair dispositions")
      val pairCounts = pairRows.map(r => r.getString(0) -> r.getLong(1)).toMap
      val distinctPairCounts =
        pairRows.map(r => r.getString(0) -> r.getLong(2)).toMap
      Vector(
        "mapped",
        "self-pair",
        "duplicate-record",
        "endpoint-missing",
        "work-mismatch"
      ).foreach { k =>
        count(s"pairs.$k", pairCounts.getOrElse(k, 0L))
        count(s"pairs.$k.distinct", distinctPairCounts.getOrElse(k, 0L))
      }
      count("edges.coauthored", DriverGuard.count(coauthor.edges))
      count("metrics.rows", DriverGuard.count(coauthor.metrics))

      val nodes = algo.sql(
        "SELECT window, researcherId AS nodeId FROM {m}",
        "m" -> coauthor.metrics
      )
      val comps = Stages.run(session, "connected-components") {
        Components.compute(algo, nodes, coauthor.adjacency)
      }
      val (membership, components, componentSummary) =
        Components.tables(algo, comps.frame, coauthor.edges)
      val membershipP = membership.persist()
      val componentsP = components.persist()
      val communities = Stages.run(session, "communities") {
        Communities.compute(algo, nodes, coauthor.adjacency)
      }
      val communityLabels = communities.labels.persist()
      val (communityTable, communityCross, edgeCommunities) =
        Communities.tables(algo, communityLabels, coauthor.edges)
      val bridge = Stages.run(session, "bridge") {
        val measured =
          Bridge.measures(algo, coauthor.adjacency, communityLabels).persist()
        measured.count()
        measured
      }
      val coreness = Stages.run(session, "coreness") {
        Coreness.compute(algo, nodes, coauthor.adjacency).persist()
      }
      coreness.count()

      val finalMetrics = Stages.run(session, "researcher-metrics") {
        val joined = algo.sql(
          s"""SELECT m.window, m.windowStart, m.windowEnd, m.researcherId,
             |  m.nodeKind, m.publicationCount, m.distinctCoauthorCount,
             |  m.totalCoauthorOccurrences, m.averageCoauthorsPerPaper,
             |  m.maximumCoauthorsOnOnePaper, m.maximumTeamWorkIds,
             |  m.weightedDegree, m.unresolvedCoauthorOccurrences,
             |  m.distinctUnresolvedCoauthorCount,
             |  c.componentId, c.componentSize,
             |  b.communityId, cm.communitySize,
             |  b.crossCommunityDegree, b.crossCommunityStrength,
             |  b.distinctCommunitiesConnected, b.distinctNeighborCommunities,
             |  b.participationCoefficient, b.bridgeScore, k.coreness,
             |  ${ctx.provenance(
              "researcher-metrics",
              "concat(m.window, '|', m.researcherId)"
            )}
             |FROM {metrics} m
             |JOIN {membership} c
             |  ON m.window = c.window AND m.researcherId = c.nodeId
             |JOIN {bridge} b
             |  ON m.window = b.window AND m.researcherId = b.researcherId
             |JOIN {communities} cm
             |  ON b.window = cm.window AND b.communityId = cm.communityId
             |JOIN {coreness} k
             |  ON m.window = k.window AND m.researcherId = k.nodeId""".stripMargin,
          "metrics" -> coauthor.metrics,
          "membership" -> membershipP,
          "bridge" -> bridge,
          "communities" -> communityTable,
          "coreness" -> coreness
        )
        telemetry.plan("researcher-metrics", joined)
        val kept = joined.persist()
        kept.count()
        kept
      }
      count("nodes.windowed", DriverGuard.count(finalMetrics))

      val pairs = pathPairs(
        session,
        config,
        finalMetrics,
        pairsFromFile.toOption.flatten,
        algo
      )
      val paths = Stages.run(session, "path-evidence") {
        val evidence = PathSearch
          .evidence(
            algo,
            pairs,
            nodes,
            coauthor.adjacency,
            coauthor.edges,
            comps.frame,
            config.hopLimit
          )
          .persist()
        evidence.count()
        evidence
      }
      val estimates = Stages.run(session, "path-length-estimates") {
        PathSearch
          .estimates(
            algo,
            comps.frame,
            componentsP,
            coauthor.adjacency,
            config.sampleSize,
            depthCap
          )
          .persist()
      }
      estimates.count()
      val worksWithoutYear = counts("canonical.worksWithoutYear").num.toLong

      val temporalPairs = Temporal.windowPairs(session, config.windows)
      val comparison = Stages.run(session, "temporal-comparison") {
        Temporal.compare(algo, finalMetrics, temporalPairs, ctx).persist()
      }
      comparison.count()
      val temporalSummary =
        Temporal.summary(algo, comparison, windowsFrame, worksWithoutYear, ctx)

      val summaries = Stages.run(session, "semantic-summaries") {
        Summaries
          .build(
            algo,
            finalMetrics,
            canon.researchers,
            communityTable,
            edgeCommunities,
            paths,
            canon.works,
            coauthor.workTeam,
            windowsFrame,
            config.paperSummaryLimit,
            ctx
          )
          .persist()
      }
      count("summaries", DriverGuard.count(summaries))

      val violations = algo
        .sql(
          s"""SELECT v.*, ${ctx.provenance(
              "integrity-violations",
              "concat(v.entityType, '|', v.entityId, '|', v.reason)"
            )}
           |FROM (
           |  SELECT ${Canonical.violationColumns} FROM {canon}
           |  UNION ALL
           |  SELECT ${Canonical.violationColumns} FROM {pair}) v""".stripMargin,
          "canon" -> canon.violations,
          "pair" -> coauthor.pairViolations
        )
        .persist()

      checkInvariants(
        algo,
        canon,
        coauthor,
        finalMetrics,
        membershipP,
        communityLabels,
        coreness,
        violations,
        raw,
        config,
        invariants,
        counts
      )
      reportDistributions(
        algo,
        finalMetrics,
        componentSummary,
        communityTable,
        coreness,
        say
      )
      val statusFrame = PathReport.statusCounts(algo, paths)
      val statusRows = algo.rows(statusFrame, "path status counts")
      say(
        s"path-status-columns ${statusFrame.columns.mkString("[", ", ", "]")}"
      )
      statusRows.foreach(r => say(s"path-status ${PathReport.line(r)}"))
      val estimateFrame =
        PathReport.lengthEstimates(algo, estimates, windowsFrame)
      val estimateRows = algo.rows(estimateFrame, "path length estimates")
      say(
        s"path-length-estimates-columns ${estimateFrame.columns.mkString("[", ", ", "]")}"
      )
      estimateRows.foreach(r =>
        say(s"path-length-estimates ${PathReport.line(r)}")
      )
      val pathResults = ujson.Obj(
        "statusCounts" -> PathReport.json(statusFrame.columns, statusRows),
        "lengthEstimates" -> PathReport.json(
          estimateFrame.columns,
          estimateRows
        ),
        "note" -> ("statusCounts is pairs per window, status and unreachableReason " +
          "(null unless unreachable-within-bound); lengthEstimates is the " +
          "path-length-estimates table without provenance, plus window bounds")
      )
      invariants.items.foreach(i =>
        say(
          s"invariant ${i("name").str}=${i("value").num.toLong} ${i("status").str}"
        )
      )

      def decorate(frame: DataFrame, table: String, key: String): DataFrame =
        algo.sql(
          s"""SELECT t.*, w.windowStart, w.windowEnd,
             |  ${ctx.provenance(table, key)}
             |FROM {t} t JOIN {w} w ON t.window = w.window""".stripMargin,
          "t" -> frame,
          "w" -> windowsFrame
        )

      val specs = Vector(
        TableSpec(
          "nodes-researcher",
          canon.researchers,
          false,
          Vector("nodeId")
        ),
        TableSpec("nodes-work", canon.works, false, Vector("canonicalWorkId")),
        TableSpec("nodes-venue", canon.venues, false, Vector("venueId")),
        TableSpec("nodes-topic", canon.topics, false, Vector("topicId")),
        TableSpec(
          "nodes-institution",
          canon.institutions,
          false,
          Vector("institutionId")
        ),
        TableSpec("edges-authored", canon.authored, false, Vector("mentionId")),
        TableSpec(
          "edges-published-in",
          canon.publishedIn,
          false,
          Vector("fromId", "toId")
        ),
        TableSpec(
          "edges-coauthored",
          coauthor.edges,
          true,
          Vector("window", "researcherA", "researcherB")
        ),
        TableSpec(
          "edge-support",
          coauthor.edgeSupport,
          true,
          Vector("window", "edgeId", "canonicalWorkId")
        ),
        TableSpec(
          "researcher-metrics",
          finalMetrics,
          true,
          Vector("window", "researcherId")
        ),
        TableSpec(
          "component-membership",
          decorate(
            algo.sql(
              "SELECT window, nodeId AS researcherId, componentId, componentSize FROM {m}",
              "m" -> membershipP
            ),
            "component-membership",
            "concat(t.window, '|', t.researcherId)"
          ),
          true,
          Vector("window", "researcherId")
        ),
        TableSpec(
          "components",
          decorate(
            componentsP,
            "components",
            "concat(t.window, '|', t.componentId)"
          ),
          true,
          Vector("window", "componentId")
        ),
        TableSpec(
          "component-summary",
          decorate(componentSummary, "component-summary", "t.window"),
          true,
          Vector("window")
        ),
        TableSpec(
          "community-membership",
          decorate(
            algo.sql(
              """SELECT c.window, c.nodeId AS researcherId, c.label AS communityId,
                |  z.communitySize
                |FROM {l} c JOIN {z} z
                |  ON c.window = z.window AND c.label = z.communityId""".stripMargin,
              "l" -> communityLabels,
              "z" -> communityTable
            ),
            "community-membership",
            "concat(t.window, '|', t.researcherId)"
          ),
          true,
          Vector("window", "researcherId")
        ),
        TableSpec(
          "communities",
          decorate(
            communityTable,
            "communities",
            "concat(t.window, '|', t.communityId)"
          ),
          true,
          Vector("window", "communityId")
        ),
        TableSpec(
          "community-cross-edges",
          decorate(
            communityCross,
            "community-cross-edges",
            "concat(t.window, '|', t.communityA, '|', t.communityB)"
          ),
          true,
          Vector("window", "communityA", "communityB")
        ),
        TableSpec(
          "bridge",
          decorate(bridge, "bridge", "concat(t.window, '|', t.researcherId)"),
          true,
          Vector("window", "researcherId")
        ),
        TableSpec(
          "coreness",
          decorate(
            algo.sql(
              "SELECT window, nodeId AS researcherId, coreness FROM {k}",
              "k" -> coreness
            ),
            "coreness",
            "concat(t.window, '|', t.researcherId)"
          ),
          true,
          Vector("window", "researcherId")
        ),
        TableSpec(
          "path-evidence",
          decorate(
            paths,
            "path-evidence",
            "concat_ws('|', t.pairKey, CAST(t.hopIndex AS STRING))"
          ),
          true,
          Vector("window", "pairKey", "hopIndex")
        ),
        TableSpec(
          "path-length-estimates",
          decorate(estimates, "path-length-estimates", "t.window"),
          true,
          Vector("window")
        ),
        TableSpec(
          "temporal-comparison",
          comparison,
          false,
          Vector("fromWindow", "toWindow", "researcherId")
        ),
        TableSpec(
          "temporal-window-summary",
          temporalSummary,
          false,
          Vector("fromWindow", "toWindow")
        ),
        TableSpec(
          "semantic-summaries",
          summaries,
          true,
          Vector("window", "summaryId")
        ),
        TableSpec(
          "unresolved-mentions",
          canon.unresolved,
          false,
          Vector("mentionId")
        ),
        TableSpec(
          "integrity-violations",
          violations,
          false,
          Vector("entityType", "entityId", "reason")
        )
      )
      val tables =
        specs.map(spec => Export.write(session, telemetry, runDir, spec))
      tables.foreach(t =>
        say(s"table ${t.name} rows=${t.rows} files=${t.files.size}")
      )

      val manifests = LoadManifests.write(runDir, ctx, tables)
      val plansDir = runDir.resolve("plans")
      Files.createDirectories(plansDir)
      telemetry.plans.foreach { (label, text) =>
        Files.writeString(plansDir.resolve(s"$label.txt"), text, UTF_8)
      }
      listener.settle()
      val stageMetrics = listener.snapshot()
      Export.writeJson(runDir.resolve("stage-metrics.json"), stageMetrics)

      val failures = invariants.failures
      val manifest = runManifest(
        config,
        layout,
        ctx,
        datasets,
        erManifestDigest,
        erManifest("policyVersion").str,
        repository,
        session,
        counts,
        invariants,
        comps,
        communities,
        telemetry,
        pathResults,
        tables,
        manifests,
        runDir
      )
      Export.writeJson(runDir.resolve("run-manifest.json"), manifest)
      Export.writeJson(
        runDir.resolve("run-metrics.json"),
        ujson.Obj(
          "elapsedMillis" -> ujson.Num(
            (System.nanoTime() - started) / 1000000.0
          ),
          "note" -> "Wall time is not part of the logical output."
        )
      )
      deleteTree(runDir.resolve("_checkpoints"))
      say(s"tables=${tables.size} checkpoints=${telemetry.checkpoints.get()}")
      say(
        s"stages=${stageMetrics("totals")("stages").num.toLong} tasks=${stageMetrics("totals")("tasks").num.toLong}"
      )
      Files.writeString(
        runDir.resolve("summary.txt"),
        summary.mkString("\n") + "\n",
        UTF_8
      )
      if failures.isEmpty then
        Files.writeString(runDir.resolve("_SUCCESS"), "", UTF_8)
        succeeded = true
        out.println(s"${resultPrefix}SUCCESS")
        0
      else
        out.println(
          s"${resultPrefix}FAILED invariants ${failures.mkString(", ")}"
        )
        1
    finally
      session.sparkContext.removeSparkListener(listener)
      session.catalog.clearCache()
      // A session this run created holds files in `_work`: stop it first.
      if provided.isEmpty then session.stop()
      try finishWork(runDir, succeeded)
      catch
        case NonFatal(error) =>
          out.println(s"warning: could not remove _work: ${error.getMessage}")

  /** Selected pairs: the configured file, else the top researchers by weight.
    */
  private def pathPairs(
      session: SparkSession,
      config: PipelineArgs,
      metrics: DataFrame,
      fromFile: Option[Vector[(String, String, String)]],
      algo: Algo
  ): DataFrame =
    val schema = StructType.fromDDL(
      "window STRING, source STRING, target STRING, selection STRING"
    )
    fromFile match
      case Some(pairs) =>
        val rows = pairs.map((w, s, t) => Row(w, s, t, "config-file"))
        session.createDataFrame(rows.asJava, schema)
      case None =>
        algo.sql(
          s"""SELECT x.window, x.nodeId AS source, y.nodeId AS target,
             |  'auto-top-weighted-degree' AS selection
             |FROM (SELECT window, researcherId AS nodeId,
             |    row_number() OVER (PARTITION BY window
             |      ORDER BY weightedDegree DESC, researcherId ASC) AS rnk
             |  FROM {m} WHERE weightedDegree > 0) x
             |JOIN (SELECT window, researcherId AS nodeId,
             |    row_number() OVER (PARTITION BY window
             |      ORDER BY weightedDegree DESC, researcherId ASC) AS rnk
             |  FROM {m} WHERE weightedDegree > 0) y
             |  ON x.window = y.window AND x.nodeId < y.nodeId
             |WHERE x.rnk <= ${config.autoPairTop}
             |  AND y.rnk <= ${config.autoPairTop}""".stripMargin,
          "m" -> metrics
        )

  private[spark] def configJson(config: PipelineArgs): ujson.Obj =
    ujson.Obj(
      "windows" -> ujson.Arr.from(config.windows.map { w =>
        ujson.Obj("label" -> w.label, "start" -> w.start, "end" -> w.end)
      }),
      "pathPairs" -> config.pathPairs.map(_.toString).getOrElse("auto"),
      "sampleSize" -> config.sampleSize,
      "partitions" -> config.partitions,
      "maxIntegrityViolations" -> ujson.Num(
        config.maxIntegrityViolations.toDouble
      ),
      "hopLimit" -> config.hopLimit,
      "autoPairTop" -> config.autoPairTop,
      "seed" -> ujson.Num(config.seed.toDouble),
      "maxIterations" -> config.maxIterations,
      "representativeWorks" -> config.representativeWorkLimit,
      "paperSummaries" -> config.paperSummaryLimit
    )

  private def checkInvariants(
      algo: Algo,
      canon: CanonicalTables,
      coauthor: CoauthorTables,
      metrics: DataFrame,
      membership: DataFrame,
      communities: DataFrame,
      coreness: DataFrame,
      violations: DataFrame,
      raw: RawInputs,
      config: PipelineArgs,
      invariants: Invariants,
      counts: mutable.LinkedHashMap[String, ujson.Value]
  ): Unit =
    val total = DriverGuard.count(violations)
    counts("integrityViolations") = ujson.Num(total.toDouble)
    val byReason = algo.rows(
      algo.sql(
        "SELECT reason, action, count(*) AS n FROM {v} GROUP BY reason, action ORDER BY reason, action",
        "v" -> violations
      ),
      "violations by reason"
    )
    counts("integrityViolationsByReason") = ujson.Obj.from(
      byReason.map(r =>
        s"${r.getString(0)}|${r.getString(1)}" -> ujson.Num(
          r.getLong(2).toDouble
        )
      )
    )
    invariants.record(
      "integrityViolationsAboveCeiling",
      Math.max(0L, total - config.maxIntegrityViolations),
      "error",
      s"ceiling ${config.maxIntegrityViolations}, found $total"
    )
    invariants.record(
      "authoredEdgesWithoutResearcherNode",
      scalar(
        algo,
        """SELECT count(*) FROM {a} a LEFT JOIN {r} r ON a.researcherNodeId = r.nodeId
          |WHERE r.nodeId IS NULL""".stripMargin,
        "a" -> canon.authored,
        "r" -> canon.researchers
      ),
      "error",
      "every AUTHORED edge points to an existing researcher node"
    )
    invariants.record(
      "authoredEdgesWithoutWorkNode",
      scalar(
        algo,
        """SELECT count(*) FROM {a} a LEFT JOIN {w} w ON a.canonicalWorkId = w.canonicalWorkId
          |WHERE w.canonicalWorkId IS NULL""".stripMargin,
        "a" -> canon.authored,
        "w" -> canon.works
      ),
      "error",
      "every AUTHORED edge points to an existing work node"
    )
    invariants.record(
      "edgesNotInCanonicalOrder",
      scalar(
        algo,
        "SELECT count(*) FROM {e} WHERE researcherA >= researcherB",
        "e" -> coauthor.edges
      ),
      "error",
      "researcherA < researcherB for every edge"
    )
    invariants.record(
      "edgeEndpointsOutsideWindowNodes",
      scalar(
        algo,
        """SELECT count(*) FROM {e} e
          |LEFT JOIN {m} a ON e.window = a.window AND e.researcherA = a.researcherId
          |LEFT JOIN {m} b ON e.window = b.window AND e.researcherB = b.researcherId
          |WHERE a.researcherId IS NULL OR b.researcherId IS NULL""".stripMargin,
        "e" -> coauthor.edges,
        "m" -> metrics
      ),
      "error",
      "every edge endpoint is a node of its window"
    )
    invariants.record(
      "edgesOutsideTheirWindow",
      scalar(
        algo,
        """SELECT count(*) FROM {e}
          |WHERE firstCollaborationYear < windowStart
          |  OR lastCollaborationYear > windowEnd
          |  OR firstCollaborationYear > lastCollaborationYear""".stripMargin,
        "e" -> coauthor.edges
      ),
      "error",
      "collaboration years lie inside the window and are ordered"
    )
    invariants.record(
      "nodesWithoutComponent",
      DriverGuard.count(metrics) - DriverGuard.count(membership),
      "error",
      "components partition the window nodes"
    )
    invariants.record(
      "nodesWithoutCommunity",
      DriverGuard.count(metrics) - DriverGuard.count(communities),
      "error",
      "communities partition the window nodes"
    )
    invariants.record(
      "nodesWithoutCoreness",
      DriverGuard.count(metrics) - DriverGuard.count(coreness),
      "error",
      "every window node has a coreness"
    )
    invariants.record(
      "weightedDegreeNotEqualToCoauthorOccurrences",
      scalar(
        algo,
        "SELECT count(*) FROM {m} WHERE weightedDegree <> totalCoauthorOccurrences",
        "m" -> metrics
      ),
      "warn",
      "W_r equals T_r when the pair candidates cover every pair of every team"
    )
    val prefix = "input.conflictingDuplicateKeys."
    def conflictsIn(identity: Boolean): Vector[(String, Long)] =
      counts.toVector.collect {
        case (name, value)
            if name.startsWith(prefix) &&
              identityInputs.contains(name.stripPrefix(prefix)) == identity =>
          name.stripPrefix(prefix) -> value.num.toLong
      }
    def detailOf(found: Vector[(String, Long)]): String =
      val named = found.collect { case (name, n) if n > 0 => s"$name=$n" }
      if named.isEmpty then "" else named.mkString(" (", ", ", ")")
    val identityConflicts = conflictsIn(identity = true)
    val otherConflicts = conflictsIn(identity = false)
    invariants.record(
      "conflictingIdentityInputKeys",
      identityConflicts.map(_._2).sum,
      "error",
      "a key repeated in an accepted-link input with different rows; entity " +
        "resolution owns identity, so Spark does not choose one" +
        detailOf(identityConflicts)
    )
    invariants.record(
      "conflictingDuplicateInputKeys",
      otherConflicts.map(_._2).sum,
      "warn",
      "keys repeated in a Hadoop input with different row contents; the " +
        "smallest row by struct order was kept" + detailOf(otherConflicts)
    )
    val teamWorks = scalar(
      algo,
      "SELECT count(DISTINCT workId) FROM {t}",
      "t" -> raw.teams
    )
    val authorPaperWorks = scalar(
      algo,
      "SELECT count(DISTINCT workId) FROM {a}",
      "a" -> raw.authorPapers
    )
    invariants.record(
      "teamsAndAuthorPapersCoverDifferentWorks",
      Math.abs(teamWorks - authorPaperWorks),
      "warn",
      s"teams cover $teamWorks works, author-papers cover $authorPaperWorks"
    )
    val expectedPairs = scalar(
      algo,
      """SELECT CAST(COALESCE(sum(teamSize * (teamSize - 1) DIV 2), 0) AS BIGINT)
        |FROM {t}""".stripMargin,
      "t" -> coauthor.workTeam
    )
    counts("pairs.expectedFromTeams") = ujson.Num(expectedPairs.toDouble)

  private def reportDistributions(
      algo: Algo,
      metrics: DataFrame,
      componentSummary: DataFrame,
      communities: DataFrame,
      coreness: DataFrame,
      say: String => Unit
  ): Unit =
    // A `<label>-columns` line names the columns of the `<label>` lines that
    // follow it, so summary.txt can be read without the source.
    def report(label: String, frame: DataFrame, what: String): Unit =
      val data = algo.rows(frame, what)
      say(s"$label-columns ${frame.columns.mkString("[", ", ", "]")}")
      data.foreach(r => say(s"$label ${r.mkString("[", ", ", "]")}"))
    report(
      "metrics",
      algo.sql(
        """SELECT window, count(*) AS researchers,
          |  max(publicationCount) AS maxP,
          |  max(distinctCoauthorCount) AS maxC,
          |  max(totalCoauthorOccurrences) AS maxT,
          |  round(avg(averageCoauthorsPerPaper), 3) AS meanA,
          |  max(maximumCoauthorsOnOnePaper) AS maxM,
          |  max(weightedDegree) AS maxW,
          |  percentile_approx(distinctCoauthorCount, 0.5) AS medianC,
          |  percentile_approx(distinctCoauthorCount, 0.9) AS p90C
          |FROM {m} GROUP BY window ORDER BY window""".stripMargin,
        "m" -> metrics
      ),
      "metric distributions"
    )
    report(
      "components",
      algo.sql(
        "SELECT * FROM {c} ORDER BY window",
        "c" -> componentSummary
      ),
      "component summary"
    )
    report(
      "communities",
      algo.sql(
        """SELECT window, count(*) AS communities, max(communitySize) AS largest,
          |  round(avg(communitySize), 3) AS meanSize,
          |  sum(CASE WHEN communitySize = 1 THEN 1 ELSE 0 END) AS singletons
          |FROM {c} GROUP BY window ORDER BY window""".stripMargin,
        "c" -> communities
      ),
      "community distribution"
    )
    report(
      "coreness",
      algo.sql(
        """SELECT window, max(coreness) AS maxCoreness,
          |  sum(CASE WHEN coreness = 0 THEN 1 ELSE 0 END) AS zeroCoreness
          |FROM {k} GROUP BY window ORDER BY window""".stripMargin,
        "k" -> coreness
      ),
      "coreness distribution"
    )

  private def runManifest(
      config: PipelineArgs,
      layout: InputLayout,
      ctx: RunContext,
      datasets: Vector[(String, Path, String)],
      erManifestDigest: String,
      erPolicy: String,
      repository: Option[Path],
      session: SparkSession,
      counts: mutable.LinkedHashMap[String, ujson.Value],
      invariants: Invariants,
      comps: IterationResult,
      communities: CommunityResult,
      telemetry: Telemetry,
      pathResults: ujson.Obj,
      tables: Vector[TableInfo],
      manifests: Vector[(String, Path)],
      runDir: Path
  ): ujson.Obj =
    ujson.Obj(
      "schemaVersion" -> 1,
      "manifestType" -> "scholarchain.spark.run-manifest/1",
      "runId" -> ctx.runId,
      "transformVersion" -> ctx.transformVersion,
      "inputs" -> ujson.Obj(
        "erRunDirectory" -> layout.erRun.toString,
        "hadoopRoot" -> layout.hadoopRoot.toString,
        "erRunId" -> ctx.erRunId,
        "hadoopRunId" -> ctx.hadoopRunId,
        "snapshot" -> ctx.snapshotId,
        "erPolicyVersion" -> erPolicy,
        "erManifestSha256" -> erManifestDigest,
        "datasets" -> ujson.Arr.from(datasets.map { (name, path, digest) =>
          ujson.Obj("name" -> name, "path" -> path.toString, "sha256" -> digest)
        })
      ),
      "config" -> configJson(config),
      "algorithms" -> ujson.Obj(
        "connectedComponents" -> ujson.Obj(
          "method" -> "distributed min-label propagation, checkpointed every pass",
          "passes" -> comps.iterations,
          "converged" -> comps.converged
        ),
        "communities" -> ujson.Obj(
          "method" -> "deterministic weighted label propagation, min-label tie break, own label kept on a tie, hash(seed, pass) parity update schedule",
          "seed" -> ujson.Num(config.seed.toDouble),
          "maxIterations" -> config.maxIterations,
          "passes" -> communities.iterations,
          "allWindowsConverged" -> communities.converged,
          "perWindow" -> ujson.Obj.from(
            communities.perWindow.toVector.sortBy(_._1).map { (window, state) =>
              window -> ujson.Obj(
                "passesToFixpoint" -> state._1,
                "converged" -> state._2
              )
            }
          )
        ),
        "bridge" -> ujson.Obj(
          "definitions" -> ujson.Obj.from(
            Bridge.definitions.map((name, text) => name -> ujson.Str(text))
          ),
          "limitations" -> Bridge.limitations
        ),
        "coreness" -> "distributed iterative peeling, checkpointed every pass",
        "paths" -> ujson.Obj(
          "method" -> "multi-source breadth-first search by joins, smallest predecessor tie break",
          "hopLimit" -> config.hopLimit,
          "sampleSize" -> config.sampleSize,
          "seed" -> ujson.Num(config.seed.toDouble),
          "depthCap" -> depthCap,
          "limitations" -> PathSearch.limitations
        ),
        "temporal" -> ujson.Obj(
          "inclusionRule" -> Temporal.inclusionRule,
          "limitations" -> Temporal.limitations
        )
      ),
      "partitioning" -> ujson.Obj(
        "shufflePartitions" -> config.partitions,
        "adaptiveQueryExecution" -> false,
        "master" -> session.sparkContext.master,
        "deployMode" -> session.sparkContext.deployMode
      ),
      "checkpointing" -> ujson.Obj(
        "directory" -> runDir.resolve("_checkpoints").toString,
        "checkpointsTaken" -> ujson.Num(telemetry.checkpoints.get().toDouble),
        "method" -> "Parquet materialization under the directory, read back",
        "removedAfterRun" -> true
      ),
      "counts" -> ujson.Obj.from(counts),
      "pathResults" -> pathResults,
      "invariants" -> ujson.Arr.from(invariants.items),
      "versions" -> ujson.Obj(
        "spark" -> session.version,
        "scalaLibrary" -> scala.util.Properties.versionNumberString,
        "java" -> System.getProperty("java.version")
      ),
      "code" -> CodeInfo.manifestEntry(
        CodeInfo.gitHead(repository),
        CodeInfo.sourcesDigest(repository)
      ),
      "outputs" -> ujson.Arr.from(tables.map { t =>
        ujson.Obj(
          "table" -> t.name,
          "rows" -> ujson.Num(t.rows.toDouble),
          "windowed" -> t.windowed,
          "partitioned" -> t.partitioned,
          "partitionsAtWrite" -> t.partitionsAtWrite,
          "files" -> t.files.size
        )
      }),
      "loadManifests" -> ujson.Arr.from(manifests.map { (name, path) =>
        ujson.Obj(
          "target" -> name,
          "path" -> runDir.relativize(path).toString,
          "sha256" -> Digests.sha256File(path)
        )
      })
    )
