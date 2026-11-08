package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

enum MetaValue:
  case Str(value: String)
  case Num(value: Double)
  case Flag(value: Boolean)
  case Strs(values: Vector[String])

  def json: ujson.Value = this match
    case Str(v)  => ujson.Str(v)
    case Num(v)  => ujson.Num(v)
    case Flag(v) => ujson.Bool(v)
    case Strs(v) => ujson.Arr.from(v.map(ujson.Str(_)))

/** The five required indexes. Names are versioned; a new embedding model or
  * metadata contract means a new index name, never an in-place change.
  */
final case class VectorIndexSpec(
    name: String,
    dimension: Int,
    distanceMetric: String,
    summaryType: Option[String]
):
  Fail.unless(
    dimension >= Limits.S3Vectors.MinDimension &&
      dimension <= Limits.S3Vectors.MaxDimension,
    ErrorCode.EmbeddingInvalid,
    s"Dimension $dimension is outside the S3 Vectors range"
  )

object VectorIndexes:
  val ResearcherProfile = "researcher-profile-v1"
  val CollaborationCommunity = "collaboration-community-v1"
  val PaperAbstract = "paper-abstract-v1"
  val CollaborationPath = "collaboration-path-v1"
  val TopicInstitutionSummary = "topic-institution-summary-v1"

  /** The Spark summary types that feed each index. Spark produces no topic or
    * institution summary, so that index has no source yet.
    */
  def all(dimension: Int, distanceMetric: String): Vector[VectorIndexSpec] =
    Vector(
      VectorIndexSpec(
        ResearcherProfile,
        dimension,
        distanceMetric,
        Some("researcher-profile")
      ),
      VectorIndexSpec(
        CollaborationCommunity,
        dimension,
        distanceMetric,
        Some("community")
      ),
      VectorIndexSpec(PaperAbstract, dimension, distanceMetric, Some("work")),
      VectorIndexSpec(
        CollaborationPath,
        dimension,
        distanceMetric,
        Some("collaboration-path")
      ),
      VectorIndexSpec(TopicInstitutionSummary, dimension, distanceMetric, None)
    )

final case class VectorRecord(
    key: String,
    embedding: Vector[Float],
    metadata: Map[String, MetaValue]
)

object VectorKeys:
  /** The key is the Spark summary ID (`summary:v1:<sha256>`), which is a hash
    * of summary type, window and subject, so a rerun yields the same key.
    */
  def stable(summaryId: String): String =
    validate(summaryId)
    summaryId

  def validate(key: String): Unit =
    Fail.unless(
      key.nonEmpty && key.trim == key,
      ErrorCode.VectorKeyInvalid,
      "Vector key must be nonblank and trimmed"
    )
    Fail.unless(
      key.length <= Limits.S3Vectors.MaxVectorKeyChars,
      ErrorCode.VectorKeyInvalid,
      s"Vector key is longer than ${Limits.S3Vectors.MaxVectorKeyChars}"
    )
    Fail.unless(
      !key.exists(_.isControl),
      ErrorCode.VectorKeyInvalid,
      "Vector key has control characters"
    )

/** Filterable metadata holds references and short facets only. Long text stays
  * in S3 and is reached through `textRef`.
  */
object VectorMetadata:
  val Required = Set("summaryType", "subjectId", "textRef", "runId")
  val Allowed: Set[String] = Required ++ Set(
    "window",
    "windowStart",
    "windowEnd",
    "nodeKind",
    "embeddingModel",
    "storageTransformVersion",
    "sparkTransformVersion",
    "evidenceWorkCount",
    "evidenceWorkIds",
    "evidenceNodeIds",
    // Filter facets. `topic` and `institution` are optional and the Spark
    // loader never sets them: no Phase 6 table links a work to a topic or a
    // researcher to an institution, so there is nothing to put there.
    "documentType",
    "year",
    "source",
    "topic",
    "institution",
    "communityId",
    // Phase 8: SHA-256 of the rendered document text (64 lowercase hex) and
    // the version of the template that rendered it.
    "textHash",
    "templateVersion"
  )

  /** What the Phase 8 write path (`EmbeddingIngest`) requires on every vector.
    * `textHash` is not in the global `Required` set so that the Phase 7 loader
    * and its tests, which predate it, stay valid; the ingest path enforces it.
    */
  val RequiredForIngest: Set[String] =
    Required ++ Set("textHash", "embeddingModel", "documentType")

  private val hexDigest = "[0-9a-f]{64}"

  /** Facet keys whose value shape is fixed. */
  private val stringKeys = Set("documentType", "communityId")
  private val listKeys = Set("source", "topic", "institution")

  val TextKeys = Set(
    "text",
    "summaryText",
    "abstract",
    "abstractText",
    "body",
    "content",
    "description"
  )
  private val maxWords = 12

  private def checkString(key: String, value: String): Unit =
    Text.requireNoNul(value, s"Metadata $key")
    Fail.unless(
      value.length <= Limits.S3Vectors.MaxMetadataStringChars,
      ErrorCode.MetadataInvalid,
      s"Metadata $key is longer than " +
        s"${Limits.S3Vectors.MaxMetadataStringChars} characters; long text " +
        "belongs in S3"
    )
    Fail.unless(
      !value.contains('\n') && value.split("\\s+").length <= maxWords,
      ErrorCode.MetadataInvalid,
      s"Metadata $key looks like prose; store a reference instead"
    )

  private def checkShape(key: String, value: MetaValue): Unit =
    val ok =
      if key == "year" then
        value match
          case MetaValue.Num(v) =>
            v.isWhole && v >= Limits.S3Vectors.MinYear &&
            v <= Limits.S3Vectors.MaxYear
          case _ => false
      else if key == "textHash" then
        value match
          case MetaValue.Str(v) => v.matches(hexDigest)
          case _                => false
      else if stringKeys.contains(key) then value.isInstanceOf[MetaValue.Str]
      else if listKeys.contains(key) then value.isInstanceOf[MetaValue.Strs]
      else true
    Fail.unless(
      ok,
      ErrorCode.MetadataInvalid,
      s"Metadata $key has the wrong value type or range"
    )

  def sizeBytes(metadata: Map[String, MetaValue]): Int =
    metadata.map { case (k, v) =>
      Text.utf8Size(k) + ujson.write(v.json).getBytes(UTF_8).length
    }.sum

  def validate(metadata: Map[String, MetaValue]): Unit =
    Fail.unless(
      metadata.size <= Limits.S3Vectors.MaxMetadataKeys,
      ErrorCode.MetadataInvalid,
      s"More than ${Limits.S3Vectors.MaxMetadataKeys} metadata keys"
    )
    val missing = Required.diff(metadata.keySet)
    Fail.unless(
      missing.isEmpty,
      ErrorCode.MetadataInvalid,
      s"Missing metadata keys: ${missing.toVector.sorted.mkString(",")}"
    )
    metadata.foreach { case (key, value) =>
      Fail.unless(
        !TextKeys.contains(key),
        ErrorCode.MetadataInvalid,
        s"Metadata key $key is reserved for text, which stays in S3"
      )
      Fail.unless(
        Allowed.contains(key),
        ErrorCode.MetadataInvalid,
        s"Unknown metadata key $key"
      )
      checkShape(key, value)
      value match
        case MetaValue.Str(v) => checkString(key, v)
        case MetaValue.Num(v) =>
          Fail.unless(v.isFinite, ErrorCode.MetadataInvalid, s"$key not finite")
        case MetaValue.Flag(_)  => ()
        case MetaValue.Strs(vs) =>
          Fail.unless(
            vs.size <= Limits.S3Vectors.MaxMetadataListItems,
            ErrorCode.MetadataInvalid,
            s"Metadata $key lists more than " +
              s"${Limits.S3Vectors.MaxMetadataListItems} references"
          )
          vs.foreach(v => checkString(key, v))
    }
    val size = sizeBytes(metadata)
    Fail.unless(
      size <= Limits.S3Vectors.MaxFilterableMetadataBytes,
      ErrorCode.MetadataInvalid,
      s"Metadata is $size bytes, filterable limit is " +
        s"${Limits.S3Vectors.MaxFilterableMetadataBytes}"
    )

object VectorMetadataIngest:
  /** `VectorMetadata.validate` plus the keys the Phase 8 path requires. */
  def validate(metadata: Map[String, MetaValue]): Unit =
    VectorMetadata.validate(metadata)
    val missing = VectorMetadata.RequiredForIngest.diff(metadata.keySet)
    Fail.unless(
      missing.isEmpty,
      ErrorCode.MetadataInvalid,
      s"Missing ingest metadata keys: ${missing.toVector.sorted.mkString(",")}"
    )

final case class RejectedVector(
    index: String,
    key: String,
    code: ErrorCode,
    detail: String
):
  def json: ujson.Value =
    ujson.Obj(
      "index" -> index,
      "key" -> key,
      "code" -> code.toString,
      "detail" -> detail
    )

/** A metadata filter for QueryVectors. Every present field is one constraint
  * and all constraints must hold (AND). Fields:
  *
  *   - `researcher`: the vector's `subjectId`, or one of its `evidenceNodeIds`;
  *   - `yearFrom` and `yearTo` (inclusive, either may be open): a windowed
  *     vector (`windowStart` and `windowEnd`) matches when its window lies
  *     inside the range, so the all-years window matches only a range that
  *     covers it; an unwindowed vector matches on its `year` key; a vector with
  *     neither never matches a year filter;
  *   - `source`, `topic`, `institution`: the list key contains the value;
  *   - `community`: the `communityId` key equals the value;
  *   - `documentType`: the `documentType` key equals the value.
  *
  * A vector that lacks a key never matches a constraint on it: a missing
  * optional key is absence, not a wildcard.
  */
final case class VectorFilter(
    researcher: Option[String] = None,
    yearFrom: Option[Int] = None,
    yearTo: Option[Int] = None,
    source: Option[String] = None,
    topic: Option[String] = None,
    institution: Option[String] = None,
    community: Option[String] = None,
    documentType: Option[String] = None
):
  VectorFilter.validate(this)

  def isEmpty: Boolean = this == VectorFilter.empty

  def constraintCount: Int =
    Vector(
      researcher,
      source,
      topic,
      institution,
      community,
      documentType
    ).count(_.isDefined) + (if yearFrom.isDefined || yearTo.isDefined then 1
                            else 0)

  private def text(md: Map[String, MetaValue], key: String): Option[String] =
    md.get(key) match
      case Some(MetaValue.Str(v)) => Some(v)
      case _                      => None

  private def list(md: Map[String, MetaValue], key: String): Vector[String] =
    md.get(key) match
      case Some(MetaValue.Strs(v)) => v
      case _                       => Vector.empty

  private def whole(md: Map[String, MetaValue], key: String): Option[Int] =
    md.get(key) match
      case Some(MetaValue.Num(v)) if v.isWhole && v.abs < Int.MaxValue =>
        Some(v.toInt)
      case _ => None

  private def yearMatches(md: Map[String, MetaValue]): Boolean =
    val from = yearFrom.getOrElse(Limits.S3Vectors.MinYear)
    val to = yearTo.getOrElse(Limits.S3Vectors.MaxYear)
    (whole(md, "windowStart"), whole(md, "windowEnd")) match
      case (Some(start), Some(end)) => from <= start && end <= to
      case _                        =>
        whole(md, "year").exists(y => from <= y && y <= to)

  def matches(md: Map[String, MetaValue]): Boolean =
    researcher.forall { id =>
      text(md, "subjectId").contains(id) ||
      list(md, "evidenceNodeIds").contains(id)
    } &&
      (yearFrom.isEmpty && yearTo.isEmpty || yearMatches(md)) &&
      source.forall(list(md, "source").contains) &&
      topic.forall(list(md, "topic").contains) &&
      institution.forall(list(md, "institution").contains) &&
      community.forall(id => text(md, "communityId").contains(id)) &&
      documentType.forall(t => text(md, "documentType").contains(t))

object VectorFilter:
  val empty: VectorFilter = VectorFilter()

  /** The accepted keys of the JSON form. */
  val Keys: Set[String] = Set(
    "researcher",
    "yearFrom",
    "yearTo",
    "source",
    "topic",
    "institution",
    "community",
    "documentType"
  )

  private def checkText(name: String, value: String): Unit =
    Text.requireNoNul(value, s"Filter $name")
    Fail.unless(
      value.nonEmpty && value.trim == value && !value.exists(_.isControl),
      ErrorCode.InvalidFilter,
      s"Filter $name must be a nonblank, trimmed string without controls"
    )
    Fail.unless(
      value.length <= Limits.S3Vectors.MaxMetadataStringChars,
      ErrorCode.InvalidFilter,
      s"Filter $name is longer than " +
        s"${Limits.S3Vectors.MaxMetadataStringChars} characters"
    )

  private def checkYear(name: String, value: Int): Unit =
    Fail.unless(
      value >= Limits.S3Vectors.MinYear && value <= Limits.S3Vectors.MaxYear,
      ErrorCode.InvalidFilter,
      s"Filter $name must be a year from ${Limits.S3Vectors.MinYear} to " +
        s"${Limits.S3Vectors.MaxYear}"
    )

  private[storage] def validate(filter: VectorFilter): Unit =
    Vector(
      "researcher" -> filter.researcher,
      "source" -> filter.source,
      "topic" -> filter.topic,
      "institution" -> filter.institution,
      "community" -> filter.community,
      "documentType" -> filter.documentType
    ).foreach { case (name, value) => value.foreach(checkText(name, _)) }
    filter.yearFrom.foreach(checkYear("yearFrom", _))
    filter.yearTo.foreach(checkYear("yearTo", _))
    Fail.unless(
      (for from <- filter.yearFrom; to <- filter.yearTo yield from <= to)
        .getOrElse(true),
      ErrorCode.InvalidFilter,
      "Filter yearFrom is after yearTo"
    )
    Fail.unless(
      filter.constraintCount <= Limits.S3Vectors.MaxFilterConstraints,
      ErrorCode.BoundExceeded,
      s"More than ${Limits.S3Vectors.MaxFilterConstraints} filter constraints"
    )

  private def textOf(name: String, value: ujson.Value): String = value match
    case ujson.Str(v) => v
    case _            =>
      Fail(ErrorCode.InvalidFilter, s"Filter $name must be a string")

  private def yearOf(name: String, value: ujson.Value): Int = value match
    case ujson.Num(v) if v.isWhole && v.abs < Int.MaxValue => v.toInt
    case _                                                 =>
      Fail(ErrorCode.InvalidFilter, s"Filter $name must be a whole year")

  /** Reads the JSON form. An unknown key is refused by name, never ignored, and
    * so is any value of the wrong type, including null.
    */
  def parse(json: ujson.Value): VectorFilter =
    val fields = json.objOpt.getOrElse(
      Fail(ErrorCode.InvalidFilter, "A filter must be a JSON object")
    )
    val unknown = fields.keySet.diff(Keys)
    Fail.unless(
      unknown.isEmpty,
      ErrorCode.UnknownFilterKey,
      s"Unknown filter keys: ${unknown.toVector.sorted.mkString(",")}"
    )
    def text(name: String): Option[String] =
      fields.get(name).map(textOf(name, _))
    def year(name: String): Option[Int] = fields.get(name).map(yearOf(name, _))
    VectorFilter(
      researcher = text("researcher"),
      yearFrom = year("yearFrom"),
      yearTo = year("yearTo"),
      source = text("source"),
      topic = text("topic"),
      institution = text("institution"),
      community = text("community"),
      documentType = text("documentType")
    )

/** One QueryVectors call. */
final case class VectorQuery(
    index: String,
    embedding: Vector[Float],
    topK: Int,
    filter: VectorFilter = VectorFilter.empty
):
  Fail.unless(
    index.trim.nonEmpty,
    ErrorCode.InvalidQuery,
    "A query names an index"
  )
  Fail.unless(
    topK >= 1,
    ErrorCode.InvalidQuery,
    s"topK must be at least 1, got $topK"
  )
  Fail.unless(
    topK <= Limits.S3Vectors.MaxTopK,
    ErrorCode.BoundExceeded,
    s"topK $topK exceeds the QueryVectors limit ${Limits.S3Vectors.MaxTopK}"
  )
  Fail.unless(
    embedding.nonEmpty && embedding.forall(f => !f.isNaN && !f.isInfinite),
    ErrorCode.EmbeddingInvalid,
    "The query embedding must be nonempty and finite"
  )

/** A hit: the vector key, its distance (smaller is nearer) and its metadata. */
final case class VectorMatch(
    key: String,
    distance: Double,
    metadata: Map[String, MetaValue]
)

object VectorDistance:
  /** Cosine distance is 1 minus the cosine similarity; euclidean is the L2
    * distance. A stored vector of zero length has no direction, so under cosine
    * it is treated as orthogonal (distance 1); a zero query is refused.
    */
  def between(
      metric: String,
      query: Vector[Float],
      stored: Vector[Float]
  ): Double =
    metric match
      case "cosine" =>
        val dot = query.zip(stored).map((a, b) => a.toDouble * b).sum
        val qn = math.sqrt(query.map(a => a.toDouble * a).sum)
        val sn = math.sqrt(stored.map(a => a.toDouble * a).sum)
        Fail.unless(
          qn > 0.0,
          ErrorCode.InvalidQuery,
          "A cosine query needs a nonzero embedding"
        )
        if sn == 0.0 then 1.0 else 1.0 - dot / (qn * sn)
      case "euclidean" =>
        math.sqrt(
          query.zip(stored).map((a, b) => math.pow(a.toDouble - b, 2)).sum
        )
      case other =>
        Fail(ErrorCode.InvalidQuery, s"Unsupported distance metric $other")

/** Port for one S3 Vectors bucket. The AWS-backed adapter is DEFERRED. A
  * throttled call raises TransientStorageException.
  */
trait VectorBucketPort:
  def ensureIndex(spec: VectorIndexSpec): Unit
  def putVectors(index: String, batch: Vector[VectorRecord]): Unit
  def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord]
  def count(index: String): Int

  /** QueryVectors: the nearest `topK` vectors that satisfy the filter, nearest
    * first, ties broken by ascending key so the order is deterministic.
    */
  def query(request: VectorQuery): Vector[VectorMatch]

/** Local fake. Unlike the real service (an upsert), it refuses changed content
  * under an existing key; see decision D7 in VectorWriter.
  */
class InMemoryVectorBucket extends VectorBucketPort:
  private val specs = mutable.LinkedHashMap.empty[String, VectorIndexSpec]
  private val data =
    mutable.LinkedHashMap
      .empty[String, mutable.LinkedHashMap[String, VectorRecord]]

  def ensureIndex(spec: VectorIndexSpec): Unit = synchronized {
    specs.get(spec.name) match
      case Some(existing) if existing != spec =>
        Fail(
          ErrorCode.MetadataInvalid,
          s"Index ${spec.name} exists differently"
        )
      case Some(_) => ()
      case None    =>
        specs.update(spec.name, spec)
        data.update(spec.name, mutable.LinkedHashMap.empty)
  }

  def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
    synchronized {
      val spec = specs.getOrElse(index, Fail(ErrorCode.InvalidKey, index))
      Fail.unless(
        batch.size <= Limits.S3Vectors.MaxBatchVectors,
        ErrorCode.BoundExceeded,
        s"Batch of ${batch.size} exceeds ${Limits.S3Vectors.MaxBatchVectors}"
      )
      batch.foreach { record =>
        VectorKeys.validate(record.key)
        VectorMetadata.validate(record.metadata)
        Fail.unless(
          record.embedding.size == spec.dimension,
          ErrorCode.EmbeddingInvalid,
          s"${record.key}: dimension ${record.embedding.size}"
        )
      }
      batch.foreach { record =>
        Fail.unless(
          data(index).get(record.key).forall(_ == record),
          ErrorCode.ChangedVectorContent,
          s"${record.key} exists with different content; refused"
        )
      }
      batch.foreach(record => data(index).update(record.key, record))
    }

  def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
    synchronized {
      Fail.unless(
        keys.size <= Limits.S3Vectors.MaxGetBatch,
        ErrorCode.BoundExceeded,
        s"More than ${Limits.S3Vectors.MaxGetBatch} keys"
      )
      keys.flatMap(key => data.get(index).flatMap(_.get(key)))
    }

  def count(index: String): Int = synchronized {
    data.get(index).map(_.size).getOrElse(0)
  }

  def query(request: VectorQuery): Vector[VectorMatch] = synchronized {
    val spec =
      specs.getOrElse(request.index, Fail(ErrorCode.InvalidKey, request.index))
    Fail.unless(
      request.embedding.size == spec.dimension,
      ErrorCode.EmbeddingInvalid,
      s"Query dimension ${request.embedding.size}, index expects " +
        s"${spec.dimension}"
    )
    Fail.unless(
      Limits.S3Vectors.DistanceMetrics.contains(spec.distanceMetric),
      ErrorCode.InvalidQuery,
      s"Unsupported distance metric ${spec.distanceMetric}"
    )
    data(request.index).values.iterator
      .filter(record => request.filter.matches(record.metadata))
      .map { record =>
        VectorMatch(
          record.key,
          VectorDistance
            .between(spec.distanceMetric, request.embedding, record.embedding),
          record.metadata
        )
      }
      .toVector
      .sortBy(hit => (hit.distance, hit.key))
      .take(request.topK)
  }

final case class RetryPolicy(
    maxAttempts: Int,
    baseDelayMillis: Long,
    maxDelayMillis: Long
):
  require(maxAttempts >= 1, "maxAttempts must be at least 1")

  /** Exponential backoff, capped. Attempt numbers start at 1. */
  def delayAfter(attempt: Int): Long =
    val shift = math.max(0, math.min(attempt - 1, 62))
    val cap = math.max(0L, maxDelayMillis)
    val base = math.max(0L, baseDelayMillis)
    if base == 0L then 0L
    else if shift >= 63 || base > (cap >> shift) then cap
    else math.min(cap, base << shift)

trait Sleeper:
  def sleep(millis: Long): Unit

object NoSleep extends Sleeper:
  def sleep(millis: Long): Unit = ()

final case class VectorWriteReport(
    index: String,
    written: Int,
    duplicatesCollapsed: Int,
    batches: Int,
    retries: Int,
    rejected: Vector[RejectedVector]
)

/** Validates, de-duplicates, batches and writes vectors with bounded retries. A
  * record that cannot be written is returned as a rejected-vector record; it is
  * never dropped silently.
  */
final class VectorWriter(
    bucket: VectorBucketPort,
    retry: RetryPolicy,
    sleeper: Sleeper,
    batchSize: Int = Limits.S3Vectors.MaxBatchVectors
):
  require(
    batchSize >= 1 && batchSize <= Limits.S3Vectors.MaxBatchVectors,
    "batchSize must be within 1 to the S3 Vectors PutVectors limit"
  )

  private def validate(
      spec: VectorIndexSpec,
      record: VectorRecord
  ): Option[RejectedVector] =
    try
      VectorKeys.validate(record.key)
      Fail.unless(
        record.embedding.size == spec.dimension,
        ErrorCode.EmbeddingInvalid,
        s"dimension ${record.embedding.size}, index expects ${spec.dimension}"
      )
      Fail.unless(
        record.embedding.forall(f => !f.isNaN && !f.isInfinite),
        ErrorCode.EmbeddingInvalid,
        "embedding has a non-finite value"
      )
      VectorMetadata.validate(record.metadata)
      None
    catch
      case error: StorageException =>
        Some(
          RejectedVector(spec.name, record.key, error.code, error.getMessage)
        )

  private def sendWithRetry(index: String, batch: Vector[VectorRecord]): Int =
    var attempt = 1
    var done = false
    var retries = 0
    while !done do
      try
        bucket.putVectors(index, batch)
        done = true
      catch
        case _: TransientStorageException if attempt < retry.maxAttempts =>
          sleeper.sleep(retry.delayAfter(attempt))
          attempt += 1
          retries += 1
    retries

  def write(
      spec: VectorIndexSpec,
      records: Vector[VectorRecord]
  ): VectorWriteReport =
    bucket.ensureIndex(spec)
    val rejected = Vector.newBuilder[RejectedVector]
    val accepted = mutable.LinkedHashMap.empty[String, VectorRecord]
    var collapsed = 0
    records.foreach { record =>
      validate(spec, record) match
        case Some(reject) => rejected += reject
        case None         =>
          accepted.get(record.key) match
            case None => accepted.update(record.key, record)
            case Some(first) if first == record => collapsed += 1
            case Some(_)                        =>
              rejected += RejectedVector(
                spec.name,
                record.key,
                ErrorCode.DuplicateVectorKey,
                "same key with different content; the first record is kept"
              )
    }
    // Real PutVectors is an upsert, so the AWS adapter must read before write
    // (or compare a content hash) to give the same refusal. Decision D7
    // (PENDING-HUMAN): a changed embedding or metadata under an existing key is
    // refused; a new embedding model means a new index or a new key.
    val stored = accepted.keys.toVector
      .grouped(Limits.S3Vectors.MaxGetBatch)
      .flatMap(keys => bucket.getVectors(spec.name, keys))
      .map(r => r.key -> r)
      .toMap
    val fresh = accepted.values.toVector.filter { record =>
      stored.get(record.key) match
        case None                       => true
        case Some(old) if old == record =>
          collapsed += 1
          false
        case Some(_) =>
          rejected += RejectedVector(
            spec.name,
            record.key,
            ErrorCode.ChangedVectorContent,
            "key exists with a different embedding or metadata; refused"
          )
          false
    }
    var written = 0
    var retries = 0
    var batches = 0
    fresh.grouped(batchSize).foreach { batch =>
      batches += 1
      try
        retries += sendWithRetry(spec.name, batch)
        written += batch.size
      catch
        case _: TransientStorageException =>
          retries += retry.maxAttempts - 1
          batch.foreach { record =>
            rejected += RejectedVector(
              spec.name,
              record.key,
              ErrorCode.RetryExhausted,
              s"batch failed after ${retry.maxAttempts} attempts"
            )
          }
    }
    VectorWriteReport(
      spec.name,
      written,
      collapsed,
      batches,
      retries,
      rejected.result()
    )

object RejectedVectors:
  /** Persists rejected-vector records as JSON lines under `logs/`. */
  def persist(
      store: ObjectStore,
      runId: String,
      index: String,
      rejected: Vector[RejectedVector]
  ): Option[String] =
    if rejected.isEmpty then None
    else
      val body = rejected
        .sortBy(_.key)
        .map(r => ujson.write(r.json))
        .mkString("", "\n", "\n")
      // The key carries a digest of the content: the same reject list reloads
      // to the same key (no-op), a different list gets its own key instead of
      // an ImmutableKeyConflict.
      val digest = Hashing.sha256(body).take(12)
      val key = ObjectKeys.versioned(
        S3Prefix.Logs,
        "vector-rejects",
        "v1",
        runId,
        s"$index-$digest.jsonl"
      )
      store.putImmutable(key, body.getBytes(UTF_8), s"spark-run:$runId")
      Some(key)

/** Port for the embedding model (Phase 8 supplies the real one). */
trait Embedder:
  def modelVersion: String
  def dimension: Int
  def embed(text: String): Vector[Float]

final case class SemanticLoadReport(
    perIndex: Vector[VectorWriteReport],
    unmapped: Vector[RejectedVector]
)

/** Lookups from other Spark tables that give a summary its filter facets: the
  * year and source of a work (`nodes-work`) and the community of a researcher
  * in a window (`researcher-metrics`). Absent tables give empty lookups, so the
  * facets they back are simply not set.
  */
final case class SummaryFacets(
    workYear: Map[String, Int],
    workSource: Map[String, String],
    communityOf: Map[(String, String), String]
)

object SummaryFacets:
  val empty: SummaryFacets = SummaryFacets(Map.empty, Map.empty, Map.empty)

  def fromTables(tables: TableSource, bounds: LoadBounds): SummaryFacets =
    def rowsOf(table: String): Vector[Row] =
      if tables.tableNames.contains(table) then
        val rows = tables.rows(table)
        bounds.checkRows(rows.size.toLong, table)
        rows
      else Vector.empty
    val works = rowsOf("nodes-work")
    val metrics = rowsOf("researcher-metrics")
    SummaryFacets(
      works.flatMap { row =>
        Rows
          .optLong(row, "year")
          .map(y => Rows.str(row, "canonicalWorkId") -> y.toInt)
      }.toMap,
      works.flatMap { row =>
        Rows
          .optStr(row, "source")
          .map(s => Rows.str(row, "canonicalWorkId") -> s)
      }.toMap,
      metrics.flatMap { row =>
        Rows
          .optStr(row, "communityId")
          .map(c =>
            (Rows.str(row, "researcherId"), Rows.str(row, "window")) -> c
          )
      }.toMap
    )

/** Maps `semantic-summaries` rows to vectors. The summary text is embedded and
  * never copied into metadata; metadata carries a reference to it in S3.
  */
object SemanticVectorLoader:
  private def firstN(values: Vector[String]): Vector[String] =
    values.take(Limits.S3Vectors.MaxMetadataListItems)

  def record(
      row: Row,
      embedder: Embedder,
      textRefBase: String,
      facets: SummaryFacets = SummaryFacets.empty
  ): VectorRecord =
    val summaryId = Rows.str(row, "summaryId")
    val text = Rows.str(row, "summaryText")
    VectorRecord(
      VectorKeys.stable(summaryId),
      embedder.embed(text),
      metadataFor(
        row,
        embedder.modelVersion,
        s"$textRefBase#$summaryId",
        facets
      )
    )

  /** The compact metadata of one summary row. `textRef` is stored as given. */
  private[storage] def metadataFor(
      row: Row,
      modelVersion: String,
      textRef: String,
      facets: SummaryFacets
  ): Map[String, MetaValue] =
    val workIds = Rows.strList(row, "evidenceWorkIds")
    val base = Map(
      "summaryType" -> MetaValue.Str(Rows.str(row, "summaryType")),
      "subjectId" -> MetaValue.Str(Rows.str(row, "subjectId")),
      "textRef" -> MetaValue.Str(textRef),
      "runId" -> MetaValue.Str(Rows.provenance(row).runId),
      "embeddingModel" -> MetaValue.Str(modelVersion),
      "storageTransformVersion" ->
        MetaValue.Str(StorageProvenance.TransformVersion),
      "sparkTransformVersion" ->
        MetaValue.Str(Rows.provenance(row).transformVersion),
      "evidenceWorkCount" -> MetaValue.Num(workIds.size.toDouble),
      "evidenceWorkIds" -> MetaValue.Strs(firstN(workIds)),
      "evidenceNodeIds" ->
        MetaValue.Strs(firstN(Rows.strList(row, "evidenceNodeIds")))
    )
    val optional = Vector(
      Rows.optStr(row, "window").map("window" -> MetaValue.Str(_)),
      Rows.optStr(row, "nodeKind").map("nodeKind" -> MetaValue.Str(_)),
      Rows
        .optLong(row, "windowStart")
        .map(v => "windowStart" -> MetaValue.Num(v.toDouble)),
      Rows
        .optLong(row, "windowEnd")
        .map(v => "windowEnd" -> MetaValue.Num(v.toDouble))
    ).flatten
    base ++ optional ++ facetEntries(row, workIds, facets)

  /** The filter facets. A facet is set only when a Spark column backs it. Topic
    * and institution are never set: no Phase 6 table links a work to a topic or
    * a researcher to an institution.
    */
  private def facetEntries(
      row: Row,
      workIds: Vector[String],
      facets: SummaryFacets
  ): Vector[(String, MetaValue)] =
    val kind = Rows.str(row, "summaryType")
    val subject = Rows.str(row, "subjectId")
    val sources = workIds.flatMap(facets.workSource.get).distinct.sorted
    val community = kind match
      case "community"          => Some(subject)
      case "researcher-profile" =>
        Rows
          .optStr(row, "window")
          .flatMap(window => facets.communityOf.get((subject, window)))
      case _ => None
    Vector(
      Some("documentType" -> MetaValue.Str(kind)),
      community.map("communityId" -> MetaValue.Str(_)),
      Option
        .when(sources.nonEmpty)("source" -> MetaValue.Strs(firstN(sources))),
      Option
        .when(kind == "work")(facets.workYear.get(subject))
        .flatten
        .map(y => "year" -> MetaValue.Num(y.toDouble))
    ).flatten

  def load(
      rows: Vector[Row],
      indexes: Vector[VectorIndexSpec],
      embedder: Embedder,
      textRefBase: String,
      writer: VectorWriter,
      bounds: LoadBounds,
      facets: SummaryFacets = SummaryFacets.empty
  ): SemanticLoadReport =
    bounds.checkRows(rows.size.toLong, "semantic-summaries")
    val byType = indexes.flatMap(i => i.summaryType.map(_ -> i)).toMap
    val unmapped = Vector.newBuilder[RejectedVector]
    val grouped = mutable.LinkedHashMap.empty[String, Vector[VectorRecord]]
    rows.foreach { row =>
      val kind = Rows.str(row, "summaryType")
      byType.get(kind) match
        case Some(spec) =>
          val made = record(row, embedder, textRefBase, facets)
          grouped.update(
            spec.name,
            grouped.getOrElse(spec.name, Vector.empty) :+ made
          )
        case None =>
          unmapped += RejectedVector(
            "none",
            Rows.str(row, "summaryId"),
            ErrorCode.UnmappedSummary,
            s"No index takes summary type $kind"
          )
    }
    val reports = indexes.map { spec =>
      writer.write(spec, grouped.getOrElse(spec.name, Vector.empty))
    }
    SemanticLoadReport(reports, unmapped.result())
