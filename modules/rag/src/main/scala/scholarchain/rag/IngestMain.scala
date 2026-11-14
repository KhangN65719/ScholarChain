package scholarchain.rag

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scholarchain.storage.*

/** The composition point of the ingest: a Spark JSON-lines export through the
  * rag templates and an embedder into a vector bucket, an object store and a
  * RunLedger table, all named by a `Backend`. The default backend (`main`) is
  * LOCAL ONLY: `hash-embed-v1`, vectors and ledger in memory, ordinary S3 as a
  * directory under the output directory, no AWS client. `AwsIngestMain` passes
  * the AWS backend (Titan-256, S3 Vectors, S3, DynamoDB) through the same
  * `execute`.
  *
  * Arguments: `<exportDir> <outDir> [maxSummaryRows]`.
  *
  * The run refuses an export that is missing or incomplete (no `_SUCCESS`, a
  * file that differs from `export-manifest.json`), an output directory that
  * overlaps the export, one that holds something else, and one that holds an
  * ingest of a different export or bound. It writes `ingest-report.json`
  * (deterministic: identical on a rerun) and `ingest-run.json` (timings and the
  * created-or-unchanged split of the documents, which differ on a rerun).
  */
object IngestMain:
  val ResultPrefix = "PHASE8-INGEST RESULT: "
  val ReportName = "ingest-report.json"
  val RunName = "ingest-run.json"
  val ObjectsDir = "objects"
  val ReportVersion = "scholarchain.rag.ingest-report/1"
  val WideTopK = 5
  val DisplayWords = 12

  /** Hits requested per sample query, so the top-score group can be measured.
    */
  val MaxHits: Int = SemanticContext.MaxItems
  val LocalCost =
    "local cost: $0 (in-process fakes); AWS cost deferred to Phase 12"

  final case class Args(exportDir: Path, outDir: Path, bound: Option[Int])

  final case class Outcome(success: Boolean, reason: String)

  /** The ports one run composes. The local run and the AWS run
    * (`AwsIngestMain`) share every line of `execute`; only the backend differs.
    *
    *   - `model` is the registry entry the embedder and the index specs are
    *     checked against before any work (`EmbeddingModels`).
    *   - `graph` is the Neptune step, which `execute` does not run: the caller
    *     runs it after a successful ingest (`GraphStep`).
    *   - `aws` switches the labels and numbers that are local-only: an AWS run
    *     never prints or writes `LocalCost`, `LocalTimings.Label` or a `LOCAL`
    *     tag, counts the vectors in an index as at least the planned ones (the
    *     index may hold earlier runs) and skips the whole-store listing.
    *   - `embedCalls` reports the embedder calls made so far, when known.
    *   - `executionLabel` names the ledger execution (`embed:<run>:<label>`).
    *     The local default is `1`. A finished execution cannot be started again
    *     with a different start time (the ledger's run item is written once),
    *     so the AWS loader gives each execution its own label and relies on the
    *     unchanged-text skip for a rerun.
    */
  final case class Backend(
      embedder: Embedder,
      model: EmbeddingModelSpec,
      store: ObjectStore,
      bucket: VectorBucketPort,
      ledger: DynamoTable,
      graph: Option[GraphLoad] = None,
      aws: Boolean = false,
      label: String = LocalTimings.Label,
      sleeper: Sleeper = NoSleep,
      embedCalls: () => Option[Long] = () => None,
      executionLabel: String = "1"
  ):
    def tag: String = if aws then "AWS" else "LOCAL"

  val AwsLabel = "AWS: Bedrock Titan, S3 Vectors, S3 and DynamoDB"
  val AwsCost =
    "cost: not estimated here; AWS Billing is the source of cost"

  /** The local backend: `hash-embed-v1`, a directory under the output directory
    * for ordinary S3, and in-memory vectors and ledger.
    */
  def localBackend(args: Args): Backend =
    val embedder = new HashEmbedder
    Backend(
      embedder,
      EmbeddingModels.HashEmbedV1,
      new LocalFsObjectStore(args.outDir.resolve(ObjectsDir)),
      new InMemoryVectorBucket,
      new InMemoryDynamoTable(RunLedger.spec(TableNames("local")))
    )

  /** The `summary-rows` of the export, optionally limited to the first `bound`
    * in export order (summary ID order), read once and kept.
    */
  private final class BoundedSource(inner: TableSource, bound: Option[Int])
      extends TableSource:
    private lazy val summaries: Vector[Row] =
      val all = inner.rows("semantic-summaries")
      bound.fold(all)(all.take)
    val total: Int = inner.rows("semantic-summaries").size
    def tableNames: Set[String] = inner.tableNames
    def rows(table: String): Vector[Row] =
      if table == "semantic-summaries" then summaries else inner.rows(table)
    def selected: Vector[Row] = summaries

  def parseArgs(args: Array[String]): Args =
    Fail.unless(
      args.length == 2 || args.length == 3,
      ErrorCode.InvalidQuery,
      "usage: IngestMain <exportDir> <outDir> [maxSummaryRows]"
    )
    val bound = args.lift(2).map { text =>
      text.toIntOption
        .filter(_ >= 1)
        .getOrElse(
          Fail(
            ErrorCode.InvalidQuery,
            s"The bound must be a whole number of at least 1, got '$text'"
          )
        )
    }
    Args(Paths.get(args(0)), Paths.get(args(1)), bound)

  private def atomicWrite(target: Path, text: String): Unit =
    Files.createDirectories(target.getParent)
    val pending = Files.createTempFile(target.getParent, ".ingest-", ".tmp")
    try
      Files.write(pending, text.getBytes(UTF_8))
      Files.move(
        pending,
        target,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    finally Files.deleteIfExists(pending)

  /** Refuses an output directory that is not safe to write to. */
  private def checkOutDir(
      exportDir: Path,
      outDir: Path,
      bound: Option[Int],
      exportSha: String
  ): Unit =
    val source = exportDir.toAbsolutePath.normalize()
    val out = outDir.toAbsolutePath.normalize()
    Fail.unless(
      !out.startsWith(source) && !source.startsWith(out),
      ErrorCode.InvalidKey,
      s"The output directory $out overlaps the export directory $source"
    )
    if Files.exists(out) then
      Fail.unless(
        Files.isDirectory(out),
        ErrorCode.InvalidKey,
        s"$out is not a directory"
      )
      val entries = Files.list(out)
      val names =
        try entries.iterator.asScala.map(_.getFileName.toString).toVector
        finally entries.close()
      if names.nonEmpty then
        val report = out.resolve(ReportName)
        Fail.unless(
          Files.isRegularFile(report),
          ErrorCode.InvalidKey,
          s"$out is not empty and holds no $ReportName; refusing to write " +
            "into it"
        )
        val previous = ujson.read(Files.readAllBytes(report))
        val same =
          previous.obj
            .get("exportManifestSha256")
            .flatMap(_.strOpt)
            .contains(
              exportSha
            ) && previous.obj.get("bound").exists {
            case ujson.Null   => bound.isEmpty
            case ujson.Num(v) => bound.contains(v.toInt)
            case _            => false
          }
        Fail.unless(
          same,
          ErrorCode.InvalidKey,
          s"$out holds the ingest of a different export or bound; use a new " +
            "output directory"
        )

  private def perIndexJson(report: IngestReport): ujson.Value =
    ujson.Obj.from(report.perIndex.map { i =>
      i.index -> ujson.Obj(
        "planned" -> i.planned,
        "written" -> i.written,
        "skippedUnchanged" -> i.skippedUnchanged,
        "skippedResumed" -> i.skippedResumed,
        "rejected" -> i.rejected,
        "duplicates" -> i.duplicates,
        "retries" -> i.retries,
        "batches" -> i.batches,
        "failedBatches" -> i.failedBatches,
        "vectorsInIndex" -> i.vectorsInIndex
      )
    })

  private def objectGroups(
      store: ObjectStore,
      listAll: Boolean
  ): Vector[(String, Int, Long)] =
    val groups = Vector(
      "documents" -> SemanticDocuments.prefix,
      "embedding-work" -> s"${S3Prefix.SemanticContext.dir}/embedding-work/",
      "vector-rejects" -> s"${S3Prefix.Logs.dir}/vector-rejects/"
    )
    if listAll then
      val all = store.list("")
      val listed = groups.map { case (name, prefix) =>
        val inGroup = all.filter(_.key.startsWith(prefix))
        (name, inGroup.size, inGroup.map(_.bytes).sum)
      }
      listed :+ (("all-objects", all.size, all.map(_.bytes).sum))
    else
      // A real bucket holds other runs' objects and a listing costs one HEAD
      // per object, so only the three prefixes this run owns are listed.
      groups.map { case (name, prefix) =>
        val inGroup = store.list(prefix)
        (name, inGroup.size, inGroup.map(_.bytes).sum)
      }

  private def words(text: String, count: Int): String =
    text.split("\\s+").filter(_.nonEmpty).take(count).mkString(" ")

  /** Where the source row stands among the hits of one sample query. Hits come
    * nearest first, so the top-score group (the hits at exactly the best
    * distance) is a prefix of the list.
    */
  final case class Standing(
      hits: Int,
      tieSize: Int,
      sourceRank: Int,
      nearest: Boolean
  )

  /** `nearest` is true only when the source row is in the top-score group. A
    * source row ranked below a group of equal-score hits, or absent, is not.
    */
  def standing(hits: Vector[RetrievedHit], summaryId: String): Standing =
    val best = hits.headOption.map(_.hit.distance)
    val tie = hits.takeWhile(h => best.contains(h.hit.distance)).size
    val rank = hits.indexWhere(_.hit.key == summaryId) + 1
    Standing(hits.size, tie, rank, rank > 0 && rank <= tie)

  /** The text a sample query is built from: the full rendered text of the
    * source row, the very text that was embedded.
    */
  def queryText(row: Row): String = RagTemplates.render(row).text

  /** The short, labelled form of the query that the console prints. */
  def displayPrefix(query: String): String =
    val shown = words(query, DisplayWords)
    s"display prefix (first $DisplayWords words of the " +
      s"${query.length}-character rendered text actually used): $shown"

  private def standingJson(s: Standing): ujson.Value =
    ujson.Obj(
      "hits" -> s.hits,
      "tieSize" -> s.tieSize,
      "sourceRank" -> s.sourceRank,
      "nearest" -> s.nearest
    )

  private def metaStrings(h: RetrievedHit, key: String): ujson.Value =
    h.hit.metadata.get(key) match
      case Some(MetaValue.Strs(values)) =>
        ujson.Arr.from(values.map(ujson.Str(_)))
      case _ => ujson.Arr()

  /** The evidence a hit points at, as the vector metadata names it: the subject
    * and the work and node IDs behind the summary.
    */
  private def hitsEvidenceJson(hits: Vector[RetrievedHit]): ujson.Value =
    ujson.Arr.from(hits.map { h =>
      ujson.Obj(
        "key" -> h.hit.key,
        "distance" -> h.hit.distance,
        "subjectId" -> (h.hit.metadata.get("subjectId") match
          case Some(MetaValue.Str(v)) => ujson.Str(v)
          case _                      => ujson.Null),
        "evidenceWorkIds" -> metaStrings(h, "evidenceWorkIds"),
        "evidenceNodeIds" -> metaStrings(h, "evidenceNodeIds"),
        "textRef" -> h.text.key
      )
    })

  /** Two sample queries per summary type, built from the first row of that type
    * in export order. Each query is the FULL rendered text of that row (the
    * text that was embedded), so the check is an identity check: the source row
    * must retrieve itself at distance about 0. A 12-word prefix of the summary
    * is not enough on real data, because templated summaries of one type share
    * their opening words and differ only later (finding N6).
    *
    *   - `SAMPLE`: filtered by document type, window years and subject.
    *   - `WIDE-SAMPLE`: filtered by document type only; other candidates must
    *     exist when the type has more than one row.
    *
    * Both pass only when the source row is in the top-score group: nearest, or
    * one of several hits at exactly the same distance (identical rendered
    * text). The size of that group is printed and written to the report. A tie
    * that excludes the source row, or a source row ranked below an equal-score
    * group, fails.
    *
    * Known limitation: hash-embed-v1 is lexical, so identical or near-identical
    * rendered text gives identical or near-identical vectors. Ties and prefix
    * collisions are expected for templated summaries and the embedder cannot
    * separate them; a semantic embedder is a Phase 12 or later item.
    */
  private def sampleQueries(
      rows: Vector[Row],
      specs: Vector[VectorIndexSpec],
      retrieval: SemanticRetrieval,
      tag: String,
      evidence: Boolean
  ): (Vector[String], Boolean, ujson.Value) =
    val lines = Vector.newBuilder[String]
    val json = Vector.newBuilder[ujson.Value]
    var ok = true
    def fmt(d: Double): String =
      "%.4f".formatLocal(java.util.Locale.ROOT, d)
    specs.flatMap(s => s.summaryType.map(_ -> s.name)).foreach {
      case (kind, index) =>
        rows.find(r => Rows.optStr(r, "summaryType").contains(kind)) match
          case None =>
            lines += s"SAMPLE $index: no $kind row in this run, no query"
          case Some(row) =>
            val summaryId = Rows.str(row, "summaryId")
            val subject = Rows.str(row, "subjectId")
            val windowStart = Rows.optLong(row, "windowStart").map(_.toInt)
            val windowEnd = Rows.optLong(row, "windowEnd").map(_.toInt)
            val filter = VectorFilter(
              researcher = Option.when(kind == "researcher-profile")(subject),
              community = Option.when(kind == "community")(subject),
              yearFrom = windowStart,
              yearTo = windowEnd,
              documentType = Some(kind)
            )
            val sameKind =
              rows.count(r => Rows.optStr(r, "summaryType").contains(kind))
            val entry = ujson.Obj(
              "index" -> index,
              "summaryType" -> kind,
              "sourceKey" -> summaryId,
              "typeRows" -> sameKind
            )
            try
              val query = queryText(row)
              entry("queryChars") = query.length
              entry("queryKind") = "full-rendered-text"
              val began = System.nanoTime()
              val outcome =
                try Right(retrieval.search(index, query, MaxHits, filter))
                catch case NonFatal(error) => Left(error.getMessage)
              val millis = (System.nanoTime() - began) / 1000000L
              outcome match
                case Left(message) =>
                  ok = false
                  entry("narrow") = ujson.Obj("error" -> message)
                  lines += s"SAMPLE $index: FAILED $message"
                case Right(hits) =>
                  val st = standing(hits, summaryId)
                  entry("narrow") = standingJson(st)
                  if evidence then
                    entry("narrowHits") = hitsEvidenceJson(hits.take(3))
                  lines += s"SAMPLE $index: $tag latency $millis ms, " +
                    s"${hits.size} hits, nearest is the source row: " +
                    s"${st.nearest}, top-score tie size ${st.tieSize}, " +
                    s"source row rank ${st.sourceRank}"
                  lines += s"  query: ${displayPrefix(query)}"
                  lines += s"  filter: documentType=$kind" +
                    s" years=${windowStart.fold("none")(_.toString)}.." +
                    windowEnd.fold("none")(_.toString) +
                    filter.researcher.fold("")(r => s" researcher=$r") +
                    filter.community.fold("")(c => s" community=$c")
                  if !st.nearest then ok = false
                  hits.take(3).foreach { h =>
                    val hitSubject = h.hit.metadata.get("subjectId") match
                      case Some(MetaValue.Str(v)) => v
                      case _                      => "none"
                    lines += s"  hit ...${h.hit.key.takeRight(12)} " +
                      s"distance ${fmt(h.hit.distance)} subject $hitSubject"
                  }
                  hits.headOption.foreach { h =>
                    lines += s"  fetchText: textHash verified, " +
                      s"${h.text.text.length} characters from ${h.text.key}, " +
                      s"template ${h.text.templateVersion}"
                  }
              val wideBegan = System.nanoTime()
              val wide =
                try
                  Right(
                    retrieval.search(
                      index,
                      query,
                      MaxHits,
                      VectorFilter(documentType = Some(kind))
                    )
                  )
                catch case NonFatal(error) => Left(error.getMessage)
              val wideMillis = (System.nanoTime() - wideBegan) / 1000000L
              wide match
                case Left(message) =>
                  ok = false
                  entry("wide") = ujson.Obj("error" -> message)
                  lines += s"WIDE-SAMPLE $index: FAILED $message"
                case Right(found) =>
                  val st = standing(found, summaryId)
                  entry("wide") = standingJson(st)
                  val rankText =
                    if st.sourceRank > 0 then
                      s"${st.sourceRank} of ${found.size}"
                    else "absent"
                  val others = found.size > 1 || sameKind <= 1
                  if evidence then
                    entry("wideHits") = hitsEvidenceJson(found.take(WideTopK))
                  lines += s"WIDE-SAMPLE $index: $tag latency $wideMillis ms, " +
                    s"${found.size} hits among $sameKind rows of this type, " +
                    s"source row rank: $rankText, other candidates: $others, " +
                    s"top-score tie size ${st.tieSize}, source row in the " +
                    s"top-score group: ${st.nearest}"
                  lines += s"  wide filter: documentType=$kind only (no " +
                    "subject, no window)"
                  found.take(WideTopK).zipWithIndex.foreach { case (h, i) =>
                    lines += s"  wide hit ${i + 1} ${h.hit.key} " +
                      s"distance ${fmt(h.hit.distance)}"
                  }
                  if !st.nearest || !others then ok = false
            catch
              case NonFatal(error) =>
                ok = false
                entry("error") = String.valueOf(error.getMessage)
                lines += s"SAMPLE $index: FAILED ${error.getMessage}"
            json += entry
    }
    (lines.result(), ok, ujson.Arr.from(json.result()))

  /** Runs the ingest. Output goes to `out`, line by line. Never throws for a
    * refusal or a failed ingest: it returns the outcome.
    */
  def execute(
      args: Args,
      out: String => Unit,
      sampleBucket: VectorBucketPort => VectorBucketPort = identity,
      backendFor: Args => Backend = localBackend
  ): Outcome =
    // `sampleBucket` is a test seam: it wraps only the bucket the sample
    // queries read, so a test can present a corrupted index to them.
    try
      val source = JsonlExportSource.open(args.exportDir)
      checkOutDir(
        args.exportDir,
        args.outDir,
        args.bound,
        source.manifestSha256
      )
      Files.createDirectories(args.outDir)
      val tables = new BoundedSource(source, args.bound)
      val backend = backendFor(args)
      val embedder = backend.embedder
      val store = backend.store
      EmbeddingModels.requireMatches(embedder, backend.model)
      val specs =
        VectorIndexes.all(embedder.dimension, "cosine")
      EmbeddingModels.requireIndexSpecs(backend.model, specs)
      val ingest =
        new EmbeddingIngest(
          embedder,
          RagTemplates,
          backend.bucket,
          store,
          backend.ledger,
          backend.sleeper
        )
      val report = ingest.run(
        tables,
        source.sourceRun,
        IngestConfig(
          startedAt = Instant.now().toString,
          executionLabel = backend.executionLabel
        )
      )
      val retrieval =
        new SemanticRetrieval(
          embedder,
          sampleBucket(backend.bucket),
          store,
          backend.model
        )
      val (queryLines, queriesOk, sampleJson) =
        sampleQueries(
          tables.selected,
          specs,
          retrieval,
          backend.tag,
          backend.aws
        )
      val groups = objectGroups(store, listAll = !backend.aws)
      val accounted = report.perIndex.forall { i =>
        i.written + i.skippedUnchanged + i.skippedResumed == i.planned &&
        (if backend.aws then i.vectorsInIndex >= i.planned
         else i.vectorsInIndex == i.planned)
      }
      val clean = report.failedBatches.isEmpty && report.rejected.isEmpty &&
        report.unmapped.isEmpty && accounted
      val success = clean && queriesOk
      val runId = source.sourceRun.runId
      val stable = ujson.Obj(
        "reportVersion" -> ReportVersion,
        "label" -> backend.label,
        "sourceRunId" -> runId,
        "sparkTransformVersion" -> source.sourceRun.transformVersion,
        "exportVersion" -> source.exportVersion,
        "exportManifestSha256" -> source.manifestSha256,
        "bound" -> args.bound.fold[ujson.Value](ujson.Null)(b => ujson.Num(b)),
        "summaryRowsInExport" -> tables.total,
        "summaryRowsIngested" -> tables.selected.size,
        "embeddingModel" -> embedder.modelVersion,
        "dimension" -> embedder.dimension,
        "templateVersions" -> ujson.Obj.from(
          RagTemplates.versions.toVector.sortBy(_._1).map { case (k, v) =>
            k -> ujson.Str(v)
          }
        ),
        "workManifestKey" -> report.manifestKey,
        "workManifestSha256" -> report.manifestSha256,
        "manifestItems" -> report.manifestItems,
        "perIndex" -> perIndexJson(report),
        "vectorsTotal" -> report.perIndex.map(_.vectorsInIndex).sum,
        "written" -> report.written,
        "skipped" -> report.skipped,
        "duplicates" -> report.duplicates,
        "retries" -> report.retries,
        "rejected" -> report.rejected.size,
        "unmapped" -> report.unmapped.size,
        "failedBatches" -> report.failedBatches.size,
        "rejectedRecordKeys" -> ujson.Arr.from(
          report.rejectedKeys.map(ujson.Str(_))
        ),
        "objects" -> ujson.Obj.from(groups.map { case (name, count, bytes) =>
          name -> ujson
            .Obj("count" -> count, "bytes" -> ujson.Num(bytes.toDouble))
        }),
        "samples" -> sampleJson,
        (if backend.aws then "cost" else "localCost") ->
          (if backend.aws then AwsCost else LocalCost),
        "result" -> (if success then "SUCCESS" else "FAILED")
      )
      atomicWrite(
        args.outDir.resolve(ReportName),
        ujson.write(stable, indent = 2) + "\n"
      )
      val run = ujson.Obj(
        "label" -> backend.label,
        "totalMillis" -> ujson.Num(report.timings.totalMillis.toDouble),
        "vectorsPerSecond" -> report.timings.vectorsPerSecond(report.written),
        "documentsCreated" -> report.documentsCreated,
        "documentsUnchanged" -> report.documentsUnchanged,
        "batchMillis" -> ujson.Arr.from(report.timings.batches.map { b =>
          ujson.Obj(
            "index" -> b.index,
            "ordinal" -> b.ordinal,
            "items" -> b.items,
            "millis" -> ujson.Num(b.millis.toDouble)
          )
        })
      )
      if backend.aws then
        run("embeddingModel") = embedder.modelVersion
        run("dimension") = embedder.dimension
        run("embeddingCalls") = backend
          .embedCalls()
          .fold[ujson.Value](ujson.Null)(n => ujson.Num(n.toDouble))
      atomicWrite(
        args.outDir.resolve(RunName),
        ujson.write(run, indent = 2) + "\n"
      )
      out(
        s"PHASE8-INGEST: export=${args.exportDir} run=$runId " +
          s"transformVersion=${source.sourceRun.transformVersion}"
      )
      out(
        s"summary rows: ${tables.selected.size} of ${tables.total}" +
          args.bound.fold(" (no bound)")(b => s" (bound $b)")
      )
      out(
        s"embedding model ${embedder.modelVersion}, dimension " +
          s"${embedder.dimension}; ${backend.label}"
      )
      report.perIndex.foreach { i =>
        out(
          s"index ${i.index}: planned=${i.planned} written=${i.written} " +
            s"skipped=${i.skippedUnchanged + i.skippedResumed} " +
            s"rejected=${i.rejected} duplicates=${i.duplicates} " +
            s"vectors=${i.vectorsInIndex}"
        )
      }
      groups.foreach { case (name, count, bytes) =>
        out(s"objects $name: count=$count bytes=$bytes")
      }
      out(if backend.aws then AwsCost else LocalCost)
      out(
        s"rejected=${report.rejected.size} unmapped=${report.unmapped.size} " +
          s"failedBatches=${report.failedBatches.size} " +
          s"retries=${report.retries} duplicates=${report.duplicates}"
      )
      report.rejected.take(5).foreach { r =>
        out(s"  reject ${r.index} ${r.key}: ${r.code} ${r.detail.take(160)}")
      }
      report.failedBatches.take(5).foreach { f =>
        out(s"  failed batch ${f.index} #${f.ordinal}: ${f.reason.take(160)}")
      }
      val rate = "%.1f".formatLocal(
        java.util.Locale.ROOT,
        report.timings.vectorsPerSecond(report.written)
      )
      out(
        if backend.aws then
          s"AWS timing: total ${report.timings.totalMillis} ms, $rate " +
            "vectors per second"
        else
          s"LOCAL timing: total ${report.timings.totalMillis} ms, $rate " +
            "vectors per second (in-process fakes, not AWS)"
      )
      out(s"report: ${args.outDir.resolve(ReportName)}")
      out(
        ResultPrefix + (if success then "SUCCESS"
                        else
                          "FAILED " +
                            (if !clean then
                               "rejects, failed batches or a count mismatch"
                             else "a sample query failed"))
      )
      queryLines.foreach(out)
      Outcome(success, if success then "" else "see the lines above")
    catch
      case NonFatal(error) =>
        val reason = s"${error.getClass.getSimpleName}: ${error.getMessage}"
        out(ResultPrefix + s"FAILED $reason")
        Outcome(success = false, reason)

  def main(args: Array[String]): Unit =
    val outcome =
      try execute(parseArgs(args), println)
      catch
        case NonFatal(error) =>
          val reason = s"${error.getClass.getSimpleName}: ${error.getMessage}"
          println(ResultPrefix + s"FAILED $reason")
          Outcome(success = false, reason)
    if !outcome.success then
      throw new IllegalStateException(s"ingest failed: ${outcome.reason}")
