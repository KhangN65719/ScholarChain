package scholarchain.storage

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  ConditionalCheckFailedException,
  GetItemRequest,
  PutItemRequest,
  QueryRequest
}

/** Items as DynamoDB attribute values. */
object DynamoValues:
  def number(value: Double): String =
    if value.isWhole && value.abs < 9.0e15 then value.toLong.toString
    else BigDecimal(value).bigDecimal.toPlainString

  def toAttribute(value: ujson.Value): AttributeValue = value match
    case ujson.Str(v)  => AttributeValue.builder().s(v).build()
    case ujson.Num(v)  => AttributeValue.builder().n(number(v)).build()
    case ujson.Bool(v) => AttributeValue.builder().bool(v).build()
    case ujson.Null    => AttributeValue.builder().nul(true).build()
    case ujson.Arr(vs) =>
      AttributeValue.builder().l(vs.toVector.map(toAttribute).asJava).build()
    case ujson.Obj(kv) =>
      AttributeValue
        .builder()
        .m(kv.toMap.map { case (k, v) => k -> toAttribute(v) }.asJava)
        .build()

  def fromAttribute(attribute: AttributeValue): ujson.Value =
    if attribute.s != null then ujson.Str(attribute.s)
    else if attribute.n != null then ujson.Num(attribute.n.toDouble)
    else if attribute.bool != null then ujson.Bool(attribute.bool.booleanValue)
    else if attribute.nul != null && attribute.nul.booleanValue then ujson.Null
    else if attribute.hasL then
      ujson.Arr.from(attribute.l.asScala.toVector.map(fromAttribute))
    else if attribute.hasM then
      ujson.Obj.from(
        attribute.m.asScala.toVector.map { case (k, v) =>
          k -> fromAttribute(v)
        }
      )
    else
      Fail(
        ErrorCode.MalformedRow,
        s"Unsupported DynamoDB attribute type ${attribute.`type`}"
      )

  def toItem(attributes: java.util.Map[String, AttributeValue]): Item =
    attributes.asScala.toMap.map { case (k, v) => k -> fromAttribute(v) }

/** `DynamoTable` over one DynamoDB table.
  *
  *   - `putIfAbsent` is a PutItem with `attribute_not_exists(pk)`;
  *     `putIfVersion` one with `attribute_exists(pk) AND version = :expected`
  *     (an item with no `version` attribute counts as version 0, like the local
  *     fake). A failed condition is `ConditionalCheckFailed`.
  *   - Item validation (key, size, TTL policy) is `ItemRules.validate`, the
  *     same as the local fake, run before the request.
  *   - `get` is a strongly consistent read; `query` and `queryIndex` page with
  *     `LastEvaluatedKey` until `limit` items are collected. A global secondary
  *     index read is eventually consistent.
  */
final class AwsDynamoTable(client: DynamoDbClient, val spec: DynamoTableSpec)
    extends DynamoTable:
  private val Version = "version"

  private def key(
      pk: String,
      sk: String
  ): java.util.Map[String, AttributeValue] =
    Map(
      spec.pk -> AttributeValue.builder().s(pk).build(),
      spec.sk -> AttributeValue.builder().s(sk).build()
    ).asJava

  private def put(
      item: Item,
      condition: String,
      names: Map[String, String],
      values: Map[String, AttributeValue],
      what: String
  ): Unit =
    val builder = PutItemRequest
      .builder()
      .tableName(spec.name)
      .item(item.map { case (k, v) => k -> DynamoValues.toAttribute(v) }.asJava)
      .conditionExpression(condition)
      .expressionAttributeNames(names.asJava)
    if values.nonEmpty then builder.expressionAttributeValues(values.asJava)
    val request = builder.build()
    // The condition failure is read inside the call, before AwsErrors would
    // classify it as an ordinary 400.
    AwsErrors.call("DynamoDB PutItem") {
      try client.putItem(request)
      catch
        case _: ConditionalCheckFailedException =>
          Fail(ErrorCode.ConditionalCheckFailed, s"${spec.name}: $what")
    }
    ()

  def putIfAbsent(item: Item): Unit =
    val (pk, sk) = ItemRules.validate(spec, item)
    put(
      item,
      "attribute_not_exists(#pk)",
      Map("#pk" -> spec.pk),
      Map.empty,
      s"item $pk / $sk already exists"
    )

  def putIfVersion(item: Item, expectedVersion: Long): Unit =
    val (pk, sk) = ItemRules.validate(spec, item)
    val expected = AttributeValue.builder().n(expectedVersion.toString).build()
    val versionTest =
      if expectedVersion == 0L then
        "(attribute_not_exists(#v) OR #v = :expected)"
      else "#v = :expected"
    put(
      item,
      s"attribute_exists(#pk) AND $versionTest",
      Map("#pk" -> spec.pk, "#v" -> Version),
      Map(":expected" -> expected),
      s"version of $pk / $sk is not $expectedVersion"
    )

  def get(pk: String, sk: String): Option[Item] =
    val response = AwsErrors.call("DynamoDB GetItem")(
      client.getItem(
        GetItemRequest
          .builder()
          .tableName(spec.name)
          .key(key(pk, sk))
          .consistentRead(true)
          .build()
      )
    )
    if response.hasItem && !response.item.isEmpty then
      Some(DynamoValues.toItem(response.item))
    else None

  private def select(
      index: Option[IndexSpec],
      pk: String,
      skPrefix: Option[String],
      limit: Int
  ): Vector[Item] =
    Fail.unless(limit >= 1, ErrorCode.BoundExceeded, "limit is below 1")
    val pkName = index.map(_.pk).getOrElse(spec.pk)
    val skName = index.map(_.sk).getOrElse(spec.sk)
    val condition =
      if skPrefix.isDefined then "#pk = :pk AND begins_with(#sk, :prefix)"
      else "#pk = :pk"
    val names =
      if skPrefix.isDefined then Map("#pk" -> pkName, "#sk" -> skName)
      else Map("#pk" -> pkName)
    val values = Map(":pk" -> AttributeValue.builder().s(pk).build()) ++
      skPrefix.map(p => ":prefix" -> AttributeValue.builder().s(p).build())
    val found = Vector.newBuilder[Item]
    var count = 0
    var start: Option[java.util.Map[String, AttributeValue]] = None
    var more = true
    while more do
      val builder = QueryRequest
        .builder()
        .tableName(spec.name)
        .keyConditionExpression(condition)
        .expressionAttributeNames(names.asJava)
        .expressionAttributeValues(values.asJava)
        .scanIndexForward(true)
        .limit(limit - count)
      index.foreach(i => builder.indexName(i.name))
      if index.isEmpty then builder.consistentRead(true)
      start.foreach(builder.exclusiveStartKey)
      val page =
        AwsErrors.call("DynamoDB Query")(client.query(builder.build()))
      page.items.asScala.foreach { attributes =>
        found += DynamoValues.toItem(attributes)
        count += 1
      }
      start =
        if page.hasLastEvaluatedKey && !page.lastEvaluatedKey.isEmpty then
          Some(page.lastEvaluatedKey)
        else None
      more = start.isDefined && count < limit
    found.result().take(limit)

  def query(pk: String, skPrefix: Option[String], limit: Int): Vector[Item] =
    select(None, pk, skPrefix, limit)

  def queryIndex(
      index: String,
      pk: String,
      skPrefix: Option[String],
      limit: Int
  ): Vector[Item] =
    val found = spec.indexes
      .find(_.name == index)
      .getOrElse(Fail(ErrorCode.InvalidItem, s"No index $index"))
    select(Some(found), pk, skPrefix, limit)
