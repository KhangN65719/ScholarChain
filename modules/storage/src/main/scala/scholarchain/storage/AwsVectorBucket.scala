package scholarchain.storage

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.s3vectors.S3VectorsClient
import software.amazon.awssdk.services.s3vectors.model.{
  GetIndexRequest,
  GetVectorsRequest,
  ListVectorsRequest,
  PutInputVector,
  PutVectorsRequest,
  QueryVectorsRequest,
  VectorData
}

/** Translation between the storage value types and the SDK `Document`, which
  * carries S3 Vectors metadata and filters.
  */
object VectorDocuments:
  def number(value: Double): Document =
    if value.isWhole && value.abs < 9.0e15 then
      Document.fromNumber(value.toLong)
    else Document.fromNumber(value)

  def toDocument(value: MetaValue): Document = value match
    case MetaValue.Str(v)  => Document.fromString(v)
    case MetaValue.Num(v)  => number(v)
    case MetaValue.Flag(v) => Document.fromBoolean(v)
    case MetaValue.Strs(v) =>
      Document.fromList(v.map(Document.fromString(_)).asJava)

  def metadata(values: Map[String, MetaValue]): Document =
    val entries = new java.util.LinkedHashMap[String, Document]()
    values.toVector.sortBy(_._1).foreach { case (key, value) =>
      entries.put(key, toDocument(value))
    }
    Document.fromMap(entries)

  def fromDocument(document: Document): Option[MetaValue] =
    if document == null then None
    else if document.isString then Some(MetaValue.Str(document.asString))
    else if document.isNumber then
      Some(MetaValue.Num(document.asNumber.doubleValue))
    else if document.isBoolean then
      Some(MetaValue.Flag(document.asBoolean.booleanValue))
    else if document.isList then
      val items = document.asList.asScala.toVector
      if items.forall(_.isString) then
        Some(MetaValue.Strs(items.map(_.asString)))
      else None
    else None

  def metadataOf(document: Document): Map[String, MetaValue] =
    if document == null || !document.isMap then Map.empty
    else
      document.asMap.asScala.toMap.flatMap { case (key, value) =>
        fromDocument(value).map(key -> _)
      }

  /** A JSON value as a Document. Whole numbers go as integers. */
  def fromJson(json: ujson.Value): Document = json match
    case ujson.Str(v)  => Document.fromString(v)
    case ujson.Num(v)  => number(v)
    case ujson.Bool(v) => Document.fromBoolean(v)
    case ujson.Arr(vs) => Document.fromList(vs.toVector.map(fromJson).asJava)
    case ujson.Obj(kv) =>
      val entries = new java.util.LinkedHashMap[String, Document]()
      kv.foreach { case (key, value) => entries.put(key, fromJson(value)) }
      Document.fromMap(entries)
    case ujson.Null => Document.fromNull()

/** `VectorFilter` as an S3 Vectors metadata filter (JSON).
  *
  * Operators used: `$eq`, `$gte`, `$lte`, `$exists`, `$and`, `$or`.
  * `$startsWith` is never used (ENHANCED indexes only). `$eq` on a list
  * metadata value is true when any element matches (documentation page
  * s3-vectors-metadata-filtering, read), which is how `source`, `topic`,
  * `institution` and the `evidenceNodeIds` half of `researcher` are expressed.
  *
  * Difference from `VectorFilter.matches` (the local reference): a windowed
  * vector is recognised by `windowStart` being present, so a vector with a
  * `windowStart` but no `windowEnd` (the loader never writes one) does not
  * reach the year rule here, while the reference falls through to `year`.
  */
object S3VectorsFilters:
  private def eq(field: String, value: String): ujson.Value =
    ujson.Obj(field -> ujson.Obj("$eq" -> value))

  def toJson(filter: VectorFilter): Option[ujson.Value] =
    val parts = Vector.newBuilder[ujson.Value]
    filter.researcher.foreach { id =>
      parts += ujson.Obj(
        "$or" -> ujson.Arr(eq("subjectId", id), eq("evidenceNodeIds", id))
      )
    }
    if filter.yearFrom.isDefined || filter.yearTo.isDefined then
      val from = filter.yearFrom.getOrElse(Limits.S3Vectors.MinYear)
      val to = filter.yearTo.getOrElse(Limits.S3Vectors.MaxYear)
      parts += ujson.Obj(
        "$or" -> ujson.Arr(
          ujson.Obj(
            "$and" -> ujson.Arr(
              ujson.Obj("windowStart" -> ujson.Obj("$gte" -> from)),
              ujson.Obj("windowEnd" -> ujson.Obj("$lte" -> to))
            )
          ),
          ujson.Obj(
            "$and" -> ujson.Arr(
              ujson.Obj("windowStart" -> ujson.Obj("$exists" -> false)),
              ujson.Obj("year" -> ujson.Obj("$gte" -> from, "$lte" -> to))
            )
          )
        )
      )
    filter.source.foreach(v => parts += eq("source", v))
    filter.topic.foreach(v => parts += eq("topic", v))
    filter.institution.foreach(v => parts += eq("institution", v))
    filter.community.foreach(v => parts += eq("communityId", v))
    filter.documentType.foreach(v => parts += eq("documentType", v))
    val all = parts.result()
    val json = all.size match
      case 0 => None
      case 1 => Some(all.head)
      case _ => Some(ujson.Obj("$and" -> ujson.Arr.from(all)))
    json.foreach(requireWithinLimit)
    json

  /** Constraints are the leaf comparisons: one per operator on a field. */
  def constraintCount(json: ujson.Value): Int = json match
    case ujson.Obj(fields) =>
      fields.toVector.map {
        case ("$and" | "$or", ujson.Arr(items)) =>
          items.toVector.map(constraintCount).sum
        case (_, ujson.Obj(operators)) => operators.size
        case _                         => 1
      }.sum
    case _ => 1

  def requireWithinLimit(json: ujson.Value): Unit =
    val count = constraintCount(json)
    Fail.unless(
      count <= Limits.S3Vectors.MaxFilterConstraints,
      ErrorCode.BoundExceeded,
      s"The filter has $count constraints, the limit is " +
        s"${Limits.S3Vectors.MaxFilterConstraints}"
    )

  def toDocument(filter: VectorFilter): Option[Document] =
    toJson(filter).map(VectorDocuments.fromJson)

/** What GetIndex reported for one index. */
final case class IndexDescription(
    name: String,
    dimension: Int,
    dataType: String,
    distanceMetric: String,
    nonFilterableKeys: Set[String],
    indexMode: Option[String] = None
)

object AwsVectorBucket:
  /** Terraform's default `non_filterable_metadata_keys`
    * (infra/terraform/modules/s3vectors/variables.tf).
    */
  val NonFilterableKeys: Set[String] = Set("textRef", "textHash")

  val DataType = "float32"

  /** ListVectors page size: up to 1,000 (service-limits.md). */
  val ListPageSize = 1000

/** `VectorBucketPort` over one S3 Vectors bucket.
  *
  * `ensureIndex` only calls GetIndex and compares: it never creates or deletes
  * an index (Terraform owns that and the loader role has no CreateIndex). A
  * missing index is `IndexMissing`; a different dimension, data type, metric or
  * non-filterable key set is `IndexSpecMismatch`.
  *
  * `count` pages ListVectors (GetIndex is not read for a count). Writes are
  * upserts on the service; the read-before-write that refuses changed content
  * (decision D7) stays in `VectorWriter` and `EmbeddingIngest`.
  */
final class AwsVectorBucket(
    client: S3VectorsClient,
    vectorBucketName: String,
    expectedNonFilterableKeys: Set[String] = AwsVectorBucket.NonFilterableKeys
) extends VectorBucketPort:
  Fail.unless(
    vectorBucketName.trim.nonEmpty,
    ErrorCode.InvalidKey,
    "A vector bucket name is required"
  )

  private val verified = mutable.Map.empty[String, IndexDescription]

  def describe(index: String): IndexDescription =
    val response = AwsErrors.optional("S3 Vectors GetIndex")(
      client.getIndex(
        GetIndexRequest
          .builder()
          .vectorBucketName(vectorBucketName)
          .indexName(index)
          .build()
      )
    ) match
      case Some(r) => r
      case None    =>
        Fail(
          ErrorCode.IndexMissing,
          s"Index $index does not exist in $vectorBucketName; Terraform " +
            "creates indexes and this adapter never does"
        )
    val found = response.index
    val keys =
      Option(found.metadataConfiguration)
        .map(_.nonFilterableMetadataKeys.asScala.toSet)
        .getOrElse(Set.empty[String])
    IndexDescription(
      index,
      Option(found.dimension).map(_.intValue).getOrElse(-1),
      Option(found.dataTypeAsString).getOrElse(""),
      Option(found.distanceMetricAsString).getOrElse(""),
      keys,
      Option(found.indexModeAsString)
    )

  def ensureIndex(spec: VectorIndexSpec): Unit =
    val actual = describe(spec.name)
    val problems = Vector(
      Option.when(actual.dimension != spec.dimension)(
        s"dimension ${actual.dimension}, expected ${spec.dimension}"
      ),
      Option.when(!actual.dataType.equalsIgnoreCase(AwsVectorBucket.DataType))(
        s"data type ${actual.dataType}, expected ${AwsVectorBucket.DataType}"
      ),
      Option.when(!actual.distanceMetric.equalsIgnoreCase(spec.distanceMetric))(
        s"distance metric ${actual.distanceMetric}, expected " +
          spec.distanceMetric
      ),
      Option.when(actual.nonFilterableKeys != expectedNonFilterableKeys)(
        "non-filterable keys " +
          actual.nonFilterableKeys.toVector.sorted.mkString("[", ",", "]") +
          ", expected " +
          expectedNonFilterableKeys.toVector.sorted.mkString("[", ",", "]")
      )
    ).flatten
    Fail.unless(
      problems.isEmpty,
      ErrorCode.IndexSpecMismatch,
      s"Index ${spec.name} differs from the expected spec: " +
        problems.mkString("; ")
    )
    synchronized { verified.update(spec.name, actual) }

  private def knownDimension(index: String): Option[Int] =
    synchronized { verified.get(index).map(_.dimension) }

  private def vectorData(embedding: Vector[Float]): VectorData =
    VectorData.fromFloat32(embedding.map(java.lang.Float.valueOf(_)).asJava)

  def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
    val dimension = knownDimension(index)
    batch.foreach { record =>
      VectorKeys.validate(record.key)
      Fail.unless(
        dimension.forall(_ == record.embedding.size),
        ErrorCode.EmbeddingInvalid,
        s"${record.key}: dimension ${record.embedding.size}, index " +
          s"$index expects ${dimension.getOrElse(-1)}"
      )
    }
    batch.grouped(Limits.S3Vectors.MaxBatchVectors).foreach { part =>
      val vectors = part.map { record =>
        PutInputVector
          .builder()
          .key(record.key)
          .data(vectorData(record.embedding))
          .metadata(VectorDocuments.metadata(record.metadata))
          .build()
      }
      AwsErrors.call("S3 Vectors PutVectors")(
        client.putVectors(
          PutVectorsRequest
            .builder()
            .vectorBucketName(vectorBucketName)
            .indexName(index)
            .vectors(vectors.asJava)
            .build()
        )
      )
    }

  def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
    keys.grouped(Limits.S3Vectors.MaxGetBatch).toVector.flatMap { part =>
      val response = AwsErrors.call("S3 Vectors GetVectors")(
        client.getVectors(
          GetVectorsRequest
            .builder()
            .vectorBucketName(vectorBucketName)
            .indexName(index)
            .keys(part.asJava)
            .returnData(true)
            .returnMetadata(true)
            .build()
        )
      )
      response.vectors.asScala.toVector.map { found =>
        VectorRecord(
          found.key,
          found.data.float32.asScala.toVector.map(_.floatValue),
          VectorDocuments.metadataOf(found.metadata)
        )
      }
    }

  def count(index: String): Int =
    var total = 0L
    var token: Option[String] = None
    var more = true
    while more do
      val builder = ListVectorsRequest
        .builder()
        .vectorBucketName(vectorBucketName)
        .indexName(index)
        .maxResults(AwsVectorBucket.ListPageSize)
      token.foreach(builder.nextToken)
      val page = AwsErrors.optional("S3 Vectors ListVectors")(
        client.listVectors(builder.build())
      ) match
        case Some(p) => p
        case None    =>
          Fail(ErrorCode.IndexMissing, s"Index $index does not exist")
      total += page.vectors.size
      val next = Option(page.nextToken).filter(_.nonEmpty)
      Fail.unless(
        next.isEmpty || next != token,
        ErrorCode.AwsRejected,
        "S3 Vectors ListVectors repeated its pagination token"
      )
      more = next.isDefined
      token = next
    Fail.unless(
      total <= Int.MaxValue,
      ErrorCode.BoundExceeded,
      s"Index $index holds more than ${Int.MaxValue} vectors"
    )
    total.toInt

  def query(request: VectorQuery): Vector[VectorMatch] =
    knownDimension(request.index).foreach { dimension =>
      Fail.unless(
        request.embedding.size == dimension,
        ErrorCode.EmbeddingInvalid,
        s"Query dimension ${request.embedding.size}, index expects $dimension"
      )
    }
    val filter = S3VectorsFilters.toDocument(request.filter)
    val hits = Vector.newBuilder[VectorMatch]
    var collected = 0
    var token: Option[String] = None
    var more = true
    while more do
      val builder = QueryVectorsRequest
        .builder()
        .vectorBucketName(vectorBucketName)
        .indexName(request.index)
        .topK(request.topK)
        .queryVector(vectorData(request.embedding))
        .returnMetadata(true)
        .returnDistance(true)
      filter.foreach(builder.filter)
      token.foreach(builder.nextToken)
      val response = AwsErrors.call("S3 Vectors QueryVectors")(
        client.queryVectors(builder.build())
      )
      response.vectors.asScala.foreach { hit =>
        collected += 1
        hits += VectorMatch(
          hit.key,
          Option(hit.distance).map(_.doubleValue).getOrElse(Double.NaN),
          VectorDocuments.metadataOf(hit.metadata)
        )
      }
      val next = Option(response.nextToken).filter(_.nonEmpty)
      Fail.unless(
        next.isEmpty || next != token,
        ErrorCode.AwsRejected,
        "S3 Vectors QueryVectors repeated its pagination token"
      )
      more = next.isDefined && collected < request.topK
      token = next
    hits
      .result()
      .sortBy(hit => (hit.distance, hit.key))
      .take(request.topK)
