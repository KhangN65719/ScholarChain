package scholarchain.storage

import scala.collection.mutable
import scholarchain.core.{
  ContractSchema,
  EvidenceBundle,
  ResearcherMetrics,
  YearWindow
}

type Item = Map[String, ujson.Value]

final case class IndexSpec(name: String, pk: String, sk: String)

/** One stated access pattern and the key condition that serves it. */
final case class AccessPattern(
    id: String,
    description: String,
    index: Option[String],
    keyCondition: String
)

enum TtlPolicy:
  /** The table holds records that must not expire. A `ttl` attribute is
    * rejected.
    */
  case Forbidden

  /** The table holds disposable data. Every item must carry a `ttl`. */
  case Required

final case class DynamoTableSpec(
    name: String,
    pk: String,
    sk: String,
    ttl: TtlPolicy,
    indexes: Vector[IndexSpec],
    patterns: Vector[AccessPattern]
):
  Fail.unless(
    name.length >= Limits.DynamoDb.MinTableNameChars &&
      name.length <= Limits.DynamoDb.MaxTableNameChars &&
      name.matches(Limits.DynamoDb.TableNamePattern),
    ErrorCode.InvalidItem,
    s"Invalid DynamoDB table name: $name"
  )

object DynamoTableSpec:
  val TtlAttribute = "ttl"

/** Size and key validation shared by every table implementation. */
object ItemRules:
  private def valueBytes(value: ujson.Value): Long = value match
    case ujson.Str(v)  => Text.utf8Size(v).toLong
    case ujson.Num(v)  => v.toString.length.toLong + 1
    case ujson.Bool(_) => 1L
    case ujson.Null    => 1L
    case ujson.Arr(vs) => 3L + vs.map(valueBytes).sum + vs.size
    case ujson.Obj(kv) =>
      3L + kv.map { case (k, v) => Text.utf8Size(k) + valueBytes(v) + 1 }.sum

  /** UTF-8 size of attribute names plus values, the DynamoDB item-size rule.
    * Numbers are approximated by their text length.
    */
  def sizeBytes(item: Item): Long =
    item.map { case (k, v) => Text.utf8Size(k) + valueBytes(v) }.sum

  def keyOf(spec: DynamoTableSpec, item: Item): (String, String) =
    def key(attr: String, limit: Int): String =
      item.get(attr) match
        case Some(ujson.Str(v)) if v.nonEmpty =>
          Fail.unless(
            Text.utf8Size(v) <= limit,
            ErrorCode.InvalidItem,
            s"Key attribute $attr exceeds $limit bytes"
          )
          v
        case _ =>
          Fail(ErrorCode.InvalidItem, s"Key attribute $attr must be a string")
    (
      key(spec.pk, Limits.DynamoDb.MaxPartitionKeyBytes),
      key(spec.sk, Limits.DynamoDb.MaxSortKeyBytes)
    )

  def validate(spec: DynamoTableSpec, item: Item): (String, String) =
    val keys = keyOf(spec, item)
    val size = sizeBytes(item)
    Fail.unless(
      size <= Limits.DynamoDb.MaxItemBytes,
      ErrorCode.ItemTooLarge,
      s"Item is $size bytes, limit is ${Limits.DynamoDb.MaxItemBytes}; " +
        "keep large payloads in S3 and store a reference"
    )
    val ttl = item.get(DynamoTableSpec.TtlAttribute)
    spec.ttl match
      case TtlPolicy.Forbidden =>
        Fail.unless(
          ttl.isEmpty,
          ErrorCode.TtlNotAllowed,
          s"Table ${spec.name} holds records that must not expire"
        )
      case TtlPolicy.Required =>
        Fail.unless(
          ttl.exists(_.numOpt.exists(_.isWhole)),
          ErrorCode.TtlRequired,
          s"Table ${spec.name} requires an epoch-seconds ttl"
        )
    item.foreach { case (name, value) =>
      value match
        case ujson.Str(v) => Text.requireNoNul(v, s"Attribute $name")
        case _            => ()
    }
    keys

/** Port for one DynamoDB table. The two write forms are the conditional ones;
  * `BatchWriteItem` supports no condition and is not part of the port. The
  * AWS-backed adapter is DEFERRED.
  */
trait DynamoTable:
  def spec: DynamoTableSpec

  /** `attribute_not_exists(pk)`: fails with ConditionalCheckFailed. */
  def putIfAbsent(item: Item): Unit

  /** `version = :expected`: fails with ConditionalCheckFailed. */
  def putIfVersion(item: Item, expectedVersion: Long): Unit
  def get(pk: String, sk: String): Option[Item]
  def query(pk: String, skPrefix: Option[String], limit: Int): Vector[Item]
  def queryIndex(
      index: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int
  ): Vector[Item]

/** Local fake with DynamoDB's conditional-write semantics. */
final class InMemoryDynamoTable(val spec: DynamoTableSpec) extends DynamoTable:
  private val items = mutable.LinkedHashMap.empty[(String, String), Item]

  private def version(item: Item): Long =
    item.get("version").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)

  def putIfAbsent(item: Item): Unit = synchronized {
    val key = ItemRules.validate(spec, item)
    if items.contains(key) then
      Fail(
        ErrorCode.ConditionalCheckFailed,
        s"${spec.name}: item ${key._1} / ${key._2} already exists"
      )
    items.update(key, item)
  }

  def putIfVersion(item: Item, expectedVersion: Long): Unit = synchronized {
    val key = ItemRules.validate(spec, item)
    val current = items.get(key)
    Fail.unless(
      current.exists(version(_) == expectedVersion),
      ErrorCode.ConditionalCheckFailed,
      s"${spec.name}: version of ${key._1} / ${key._2} is not $expectedVersion"
    )
    items.update(key, item)
  }

  def get(pk: String, sk: String): Option[Item] = synchronized {
    items.get((pk, sk))
  }

  private def select(
      pkAttr: String,
      skAttr: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int
  ): Vector[Item] =
    def attr(item: Item, name: String): Option[String] =
      item.get(name).flatMap(_.strOpt)
    val hits = items.values.toVector.filter { item =>
      attr(item, pkAttr).contains(pk) &&
      skPrefix.forall(prefix => attr(item, skAttr).exists(_.startsWith(prefix)))
    }
    hits.sortBy(item => attr(item, skAttr).getOrElse("")).take(limit)

  def query(pk: String, skPrefix: Option[String], limit: Int): Vector[Item] =
    synchronized { select(spec.pk, spec.sk, pk, skPrefix, limit) }

  def queryIndex(
      index: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int
  ): Vector[Item] = synchronized {
    val found = spec.indexes
      .find(_.name == index)
      .getOrElse(Fail(ErrorCode.InvalidItem, s"No index $index"))
    select(found.pk, found.sk, pk, skPrefix, limit)
  }

enum WriteOutcome:
  case Created
  case Unchanged

object Idempotent:
  /** Writes with `attribute_not_exists`. If the item exists with identical
    * content the write is a no-op; if it differs the write fails, so a reload
    * can neither duplicate nor silently change an item.
    */
  def put(table: DynamoTable, item: Item): WriteOutcome =
    try
      table.putIfAbsent(item)
      WriteOutcome.Created
    catch
      case error: StorageException
          if error.code == ErrorCode.ConditionalCheckFailed =>
        val (pk, sk) = ItemRules.keyOf(table.spec, item)
        if table.get(pk, sk).contains(item) then WriteOutcome.Unchanged
        else throw error

object Attr:
  def s(value: String): ujson.Value = ujson.Str(value)
  def n(value: Long): ujson.Value = ujson.Num(value.toDouble)

  /** Source and transformation provenance of a written item. The storage
    * transform version is the loader's; the Spark transform version (when
    * known) is kept apart under its own attribute.
    */
  def provenance(
      sourceRef: String,
      sparkTransformVersion: Option[String]
  ): Map[String, ujson.Value] =
    val base = Map(
      "storageTransformVersion" -> s(StorageProvenance.TransformVersion),
      "sourceRef" -> s(sourceRef)
    )
    sparkTransformVersion.fold(base)(v =>
      base + ("sparkTransformVersion" -> s(v))
    )

  def optS(item: Item, name: String): Option[String] =
    item.get(name).flatMap(_.strOpt)

/** Table names: one prefix chosen by the deployment, never hardcoded. */
final case class TableNames(prefix: String):
  def of(suffix: String): String = s"$prefix-$suffix"

/** RunLedger. Access patterns: A1 get a run by ID; A2 list the stages of a run;
  * A3 list runs by status ordered by start time (index `status-index`).
  */
object RunLedger:
  def spec(names: TableNames): DynamoTableSpec =
    DynamoTableSpec(
      names.of("run-ledger"),
      "pk",
      "sk",
      TtlPolicy.Forbidden,
      Vector(IndexSpec("status-index", "gsi1pk", "gsi1sk")),
      Vector(
        AccessPattern("A1", "get a run", None, "pk = RUN#<runId>, sk = META"),
        AccessPattern(
          "A2",
          "list the stages of a run",
          None,
          "pk = RUN#<runId>, sk begins_with STAGE#"
        ),
        AccessPattern(
          "A3",
          "runs by status, oldest first",
          Some("status-index"),
          "gsi1pk = STATUS#<status>"
        )
      )
    )

  def runItem(
      runId: String,
      kind: String,
      status: String,
      transformVersion: String,
      startedAt: String,
      version: Long
  ): Item =
    Map(
      "pk" -> Attr.s(s"RUN#$runId"),
      "sk" -> Attr.s("META"),
      "entityType" -> Attr.s("RUN"),
      "runId" -> Attr.s(runId),
      "kind" -> Attr.s(kind),
      "status" -> Attr.s(status),
      "transformVersion" -> Attr.s(transformVersion),
      "startedAt" -> Attr.s(startedAt),
      "gsi1pk" -> Attr.s(s"STATUS#$status"),
      "gsi1sk" -> Attr.s(s"$startedAt#$runId"),
      "version" -> Attr.n(version)
    ) ++ Attr.provenance(s"spark-run:$runId", Some(transformVersion))

  def start(
      table: DynamoTable,
      runId: String,
      kind: String,
      transformVersion: String,
      startedAt: String,
      extra: Item = Map.empty
  ): WriteOutcome =
    val item =
      runItem(runId, kind, "STARTED", transformVersion, startedAt, 1L) ++ extra
    try
      table.putIfAbsent(item)
      WriteOutcome.Created
    catch
      case error: StorageException
          if error.code == ErrorCode.ConditionalCheckFailed =>
        // A later status is progress, not a different run: compare identity.
        val existing = table.get(s"RUN#$runId", "META")
        val identity = Vector("kind", "transformVersion", "startedAt")
        val same = existing.exists { found =>
          identity.forall(name => found.get(name) == item.get(name))
        }
        if same then WriteOutcome.Unchanged else throw error

  /** Moves a run from one status to another only if it is still in `from`. */
  def transition(
      table: DynamoTable,
      runId: String,
      from: String,
      to: String
  ): Unit =
    val current = table
      .get(s"RUN#$runId", "META")
      .getOrElse(
        Fail(ErrorCode.ConditionalCheckFailed, s"Unknown run $runId")
      )
    Fail.unless(
      Attr.optS(current, "status").contains(from),
      ErrorCode.ConditionalCheckFailed,
      s"Run $runId is not in status $from"
    )
    val version = current("version").num.toLong
    val startedAt = Attr.optS(current, "startedAt").getOrElse("")
    val updated = current ++ Map(
      "status" -> Attr.s(to),
      "gsi1pk" -> Attr.s(s"STATUS#$to"),
      "gsi1sk" -> Attr.s(s"$startedAt#$runId"),
      "version" -> Attr.n(version + 1)
    )
    table.putIfVersion(updated, version)

  def recordStage(
      table: DynamoTable,
      runId: String,
      stage: String,
      status: String
  ): WriteOutcome =
    Idempotent.put(
      table,
      Map(
        "pk" -> Attr.s(s"RUN#$runId"),
        "sk" -> Attr.s(s"STAGE#$stage"),
        "entityType" -> Attr.s("STAGE"),
        "runId" -> Attr.s(runId),
        "stage" -> Attr.s(stage),
        "status" -> Attr.s(status),
        "version" -> Attr.n(1L)
      ) ++ Attr.provenance(s"spark-run:$runId", None)
    )

/** EntityLinkCache (disposable, TTL). Access pattern B1: the cached decision
  * for a source key under a policy version.
  */
object EntityLinkCache:
  def spec(names: TableNames): DynamoTableSpec =
    DynamoTableSpec(
      names.of("entity-link-cache"),
      "pk",
      "sk",
      TtlPolicy.Required,
      Vector.empty,
      Vector(
        AccessPattern(
          "B1",
          "cached link decision for a source key",
          None,
          "pk = LINK#<sourceKey>, sk = POLICY#<policyVersion>"
        )
      )
    )

  def put(
      table: DynamoTable,
      sourceKey: String,
      policyVersion: String,
      canonicalId: Option[String],
      decision: String,
      ttlEpochSeconds: Long
  ): WriteOutcome =
    val base: Item = Map(
      "pk" -> Attr.s(s"LINK#$sourceKey"),
      "sk" -> Attr.s(s"POLICY#$policyVersion"),
      "sourceKey" -> Attr.s(sourceKey),
      "decision" -> Attr.s(decision),
      "linkPolicyVersion" -> Attr.s(policyVersion),
      "ttl" -> Attr.n(ttlEpochSeconds)
    ) ++ Attr.provenance(s"entity-link:$sourceKey", None)
    val item =
      canonicalId.fold(base)(id => base + ("canonicalId" -> Attr.s(id)))
    Idempotent.put(table, item)

/** QuerySession (disposable, TTL). Access patterns C1: the turns of a session
  * in order; C2: one turn.
  */
object QuerySession:
  def spec(names: TableNames): DynamoTableSpec =
    DynamoTableSpec(
      names.of("query-session"),
      "pk",
      "sk",
      TtlPolicy.Required,
      Vector.empty,
      Vector(
        AccessPattern(
          "C1",
          "turns of a session in order",
          None,
          "pk = SESSION#<sessionId>, sk begins_with TURN#"
        ),
        AccessPattern(
          "C2",
          "one turn",
          None,
          "pk = SESSION#<sessionId>, sk = TURN#<ordinal>"
        )
      )
    )

  def appendTurn(
      table: DynamoTable,
      sessionId: String,
      ordinal: Int,
      payloadJson: String,
      ttlEpochSeconds: Long
  ): WriteOutcome =
    Idempotent.put(
      table,
      Map(
        "pk" -> Attr.s(s"SESSION#$sessionId"),
        "sk" -> Attr.s(f"TURN#$ordinal%06d"),
        "payloadJson" -> Attr.s(payloadJson),
        "ttl" -> Attr.n(ttlEpochSeconds)
      ) ++ Attr.provenance(s"query-session:$sessionId", None)
    )

/** EvidenceBundle (never expires: answers must stay reproducible). Access
  * patterns D1: a bundle by ID; D2: the bundles of a query (index
  * `query-index`).
  */
object EvidenceBundleTable:
  def spec(names: TableNames): DynamoTableSpec =
    DynamoTableSpec(
      names.of("evidence-bundle"),
      "pk",
      "sk",
      TtlPolicy.Forbidden,
      Vector(IndexSpec("query-index", "gsi1pk", "gsi1sk")),
      Vector(
        AccessPattern(
          "D1",
          "bundle by ID",
          None,
          "pk = BUNDLE#<bundleId>, sk = v1"
        ),
        AccessPattern(
          "D2",
          "bundles of a query",
          Some("query-index"),
          "gsi1pk = QUERY#<queryId>"
        )
      )
    )

  def store(
      table: DynamoTable,
      bundle: EvidenceBundle,
      queryId: String,
      runId: String
  ): WriteOutcome =
    val json = upickle.default.write(bundle)
    Idempotent.put(
      table,
      Map(
        "pk" -> Attr.s(s"BUNDLE#${bundle.bundleId}"),
        "sk" -> Attr.s("v1"),
        "bundleId" -> Attr.s(bundle.bundleId),
        "queryId" -> Attr.s(queryId),
        "runId" -> Attr.s(runId),
        "itemCount" -> Attr.n(bundle.items.size.toLong),
        "bundleJson" -> Attr.s(json),
        "gsi1pk" -> Attr.s(s"QUERY#$queryId"),
        "gsi1sk" -> Attr.s(bundle.bundleId)
      ) ++ Attr.provenance(
        s"spark-run:$runId",
        Some(bundle.provenance.transformVersion)
      )
    )

  def load(table: DynamoTable, bundleId: String): Option[EvidenceBundle] =
    table
      .get(s"BUNDLE#$bundleId", "v1")
      .map(item => upickle.default.read[EvidenceBundle](item("bundleJson").str))

/** ArtifactRegistry. Access patterns E1: an artifact by class, run and name;
  * E2: the artifacts of a run (index `run-index`).
  */
object ArtifactRegistry:
  def spec(names: TableNames): DynamoTableSpec =
    DynamoTableSpec(
      names.of("artifact-registry"),
      "pk",
      "sk",
      TtlPolicy.Forbidden,
      Vector(IndexSpec("run-index", "gsi1pk", "gsi1sk")),
      Vector(
        AccessPattern(
          "E1",
          "artifact by class, run and name",
          None,
          "pk = ARTIFACT#<class>, sk = RUN#<runSegment>#<name>"
        ),
        AccessPattern(
          "E2",
          "artifacts of a run",
          Some("run-index"),
          "gsi1pk = RUN#<runId>"
        )
      )
    )

  def register(
      table: DynamoTable,
      artifactClass: String,
      runId: String,
      name: String,
      s3Key: String,
      sha256: String,
      bytes: Long,
      sparkTransformVersion: Option[String] = None
  ): WriteOutcome =
    val run = RunDirectory.segment(runId)
    Idempotent.put(
      table,
      Map(
        "pk" -> Attr.s(s"ARTIFACT#$artifactClass"),
        "sk" -> Attr.s(s"RUN#$run#$name"),
        "artifactClass" -> Attr.s(artifactClass),
        "runId" -> Attr.s(runId),
        "name" -> Attr.s(name),
        "s3Key" -> Attr.s(s3Key),
        "sha256" -> Attr.s(sha256),
        "bytes" -> Attr.n(bytes),
        "gsi1pk" -> Attr.s(s"RUN#$runId"),
        "gsi1sk" -> Attr.s(s"$artifactClass#$name")
      ) ++ Attr.provenance(s"spark-run:$runId/$name", sparkTransformVersion)
    )

/** ResearcherMetricCache (optional, disposable, TTL). Access patterns F1: the
  * metrics of a researcher in one window of one run; F2: all windows of a
  * researcher.
  */
object ResearcherMetricCache:
  def spec(names: TableNames): DynamoTableSpec =
    DynamoTableSpec(
      names.of("researcher-metric-cache"),
      "pk",
      "sk",
      TtlPolicy.Required,
      Vector.empty,
      Vector(
        AccessPattern(
          "F1",
          "metrics of a researcher in a window of a run",
          None,
          "pk = RESEARCHER#<id>, sk = WINDOW#<start>-<end>#RUN#<run>"
        ),
        AccessPattern(
          "F2",
          "all windows of a researcher",
          None,
          "pk = RESEARCHER#<id>, sk begins_with WINDOW#"
        )
      )
    )

  def sortKey(window: YearWindow, runId: String): String =
    val run = RunDirectory.segment(runId)
    s"WINDOW#${window.startYear}-${window.endYear}#RUN#$run"

  def put(
      table: DynamoTable,
      metrics: ResearcherMetrics,
      runId: String,
      extras: Map[String, ujson.Value],
      ttlEpochSeconds: Long
  ): WriteOutcome =
    val base: Item = Map(
      "pk" -> Attr.s(s"RESEARCHER#${metrics.researcherId}"),
      "sk" -> Attr.s(sortKey(metrics.window, runId)),
      "runId" -> Attr.s(runId),
      "metricsJson" -> Attr.s(upickle.default.write(metrics)),
      "ttl" -> Attr.n(ttlEpochSeconds)
    ) ++ Attr.provenance(
      s"spark-run:$runId",
      Some(metrics.provenance.transformVersion)
    )
    Idempotent.put(table, extras ++ base)

final case class DynamoLoadReport(
    ledgerCreated: Int,
    artifactsCreated: Int,
    artifactsUnchanged: Int,
    metricsCreated: Int,
    metricsUnchanged: Int
)

/** Loads the DynamoDB-planned content of a Spark run: the ledger entry, the
  * artifact registry and the researcher metric cache. Idempotent: a second load
  * creates nothing.
  */
object DynamoRunLoader:
  private def metricsFrom(row: Row): ResearcherMetrics =
    try
      ResearcherMetrics(
        ContractSchema.currentVersion,
        Rows.str(row, "researcherId"),
        YearWindow(Rows.int(row, "windowStart"), Rows.int(row, "windowEnd")),
        Rows.long(row, "publicationCount"),
        Rows.long(row, "distinctCoauthorCount"),
        Rows.long(row, "totalCoauthorOccurrences"),
        Rows.optDouble(row, "averageCoauthorsPerPaper").getOrElse(0.0),
        Rows.long(row, "maximumCoauthorsOnOnePaper"),
        Rows.strList(row, "maximumTeamWorkIds"),
        Rows.optDouble(row, "weightedDegree").getOrElse(0.0),
        Rows.str(row, "componentId"),
        Rows.long(row, "componentSize"),
        Rows.optStr(row, "communityId"),
        Rows.optDouble(row, "bridgeScore"),
        Rows.optLong(row, "coreness").map(_.toInt),
        Rows.provenance(row)
      )
    catch
      case error: IllegalArgumentException =>
        Fail(
          ErrorCode.MalformedRow,
          s"Invalid metrics row: ${error.getMessage}"
        )

  private def extras(row: Row): Map[String, ujson.Value] =
    val names = Vector(
      "nodeKind",
      "unresolvedCoauthorOccurrences",
      "distinctUnresolvedCoauthorCount"
    )
    names
      .flatMap(name => row.get(name).filter(_ != ujson.Null).map(name -> _))
      .toMap

  /** One researcher-metrics row into the cache. The row is first rebuilt as a
    * core `ResearcherMetrics`, so a row that breaks the contract is refused.
    */
  def putMetrics(
      cache: DynamoTable,
      row: Row,
      runId: String,
      ttlEpochSeconds: Long
  ): WriteOutcome =
    ResearcherMetricCache.put(
      cache,
      metricsFrom(row),
      runId,
      extras(row),
      ttlEpochSeconds
    )

  def load(
      run: RunDirectory,
      s3: S3RunManifest,
      source: TableSource,
      ledger: DynamoTable,
      registry: DynamoTable,
      cache: DynamoTable,
      startedAt: String,
      ttlEpochSeconds: Long,
      bounds: LoadBounds
  ): DynamoLoadReport =
    val rows = source.rows("researcher-metrics")
    bounds.checkRows(rows.size.toLong, "researcher-metrics")
    val metrics = rows.map(row => (metricsFrom(row), extras(row)))
    // The ledger keeps Spark's own transform version (from the rows' own
    // provenance); the storage version is recorded beside it, not instead.
    val sparkTransform = metrics
      .map(_._1.provenance.transformVersion)
      .distinct
      .sorted
      .headOption
    val started = RunLedger.start(
      ledger,
      run.runId,
      "spark-analytics",
      sparkTransform.getOrElse("unavailable"),
      startedAt
    )
    val registered = s3.objects.map { entry =>
      ArtifactRegistry.register(
        registry,
        entry.key.takeWhile(_ != '/'),
        run.runId,
        entry.relativePath,
        entry.key,
        entry.sha256,
        entry.bytes,
        sparkTransform
      )
    }
    val cached = metrics.map { case (m, more) =>
      ResearcherMetricCache.put(cache, m, run.runId, more, ttlEpochSeconds)
    }
    val status = ledger.get(s"RUN#${run.runId}", "META")
    if status.flatMap(Attr.optS(_, "status")).contains("STARTED") then
      RunLedger.transition(ledger, run.runId, "STARTED", "LOADED")
    DynamoLoadReport(
      if started == WriteOutcome.Created then 1 else 0,
      registered.count(_ == WriteOutcome.Created),
      registered.count(_ == WriteOutcome.Unchanged),
      cached.count(_ == WriteOutcome.Created),
      cached.count(_ == WriteOutcome.Unchanged)
    )
