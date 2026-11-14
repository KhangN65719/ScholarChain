package scholarchain.storage

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.{
  AttributeValue,
  ConditionalCheckFailedException,
  GetItemRequest,
  GetItemResponse,
  PutItemRequest,
  PutItemResponse,
  QueryRequest,
  QueryResponse
}

class AwsDynamoTableSuite extends munit.FunSuite:
  private val spec = RunLedger.spec(TableNames("sc"))

  private def stub = new SdkStub(classOf[DynamoDbClient])

  private def table(s: SdkStub[DynamoDbClient]) =
    new AwsDynamoTable(s.client, spec)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val item: Item = RunLedger.runItem(
    "run:v1:x",
    "spark",
    "STARTED",
    "spark/1",
    "2026-11-14T00:00:00Z",
    2L
  )

  private val putOk = (_: AnyRef) => PutItemResponse.builder().build()

  private def str(value: String) = AttributeValue.builder().s(value).build()

  test("putIfAbsent is attribute_not_exists on the table's own name") {
    val s = stub.on("putItem")(putOk)
    table(s).putIfAbsent(item)
    val sent = s.requests("putItem").head.asInstanceOf[PutItemRequest]
    assertEquals(sent.tableName, "sc-run-ledger")
    assertEquals(sent.conditionExpression, "attribute_not_exists(#pk)")
    assertEquals(
      sent.expressionAttributeNames.asScala.toMap,
      Map("#pk" -> "pk")
    )
    assertEquals(sent.item.get("pk").s, "RUN#run:v1:x")
    assertEquals(sent.item.get("version").n, "2")
    assertEquals(DynamoValues.toItem(sent.item), item)
  }

  test("a failed condition is ConditionalCheckFailed, not a generic 400") {
    val s = stub.on("putItem")(_ =>
      throw ConditionalCheckFailedException.builder().message("no").build()
    )
    assertEquals(
      code(table(s).putIfAbsent(item)),
      ErrorCode.ConditionalCheckFailed
    )
    assertEquals(
      code(table(s).putIfVersion(item, 1L)),
      ErrorCode.ConditionalCheckFailed
    )
  }

  test("putIfVersion requires the item and the expected version") {
    val s = stub.on("putItem")(putOk)
    table(s).putIfVersion(item, 3L)
    val sent = s.requests("putItem").head.asInstanceOf[PutItemRequest]
    assertEquals(
      sent.conditionExpression,
      "attribute_exists(#pk) AND #v = :expected"
    )
    assertEquals(
      sent.expressionAttributeNames.asScala.toMap,
      Map("#pk" -> "pk", "#v" -> "version")
    )
    assertEquals(sent.expressionAttributeValues.get(":expected").n, "3")
  }

  test("version 0 also matches an item with no version attribute") {
    val s = stub.on("putItem")(putOk)
    table(s).putIfVersion(item, 0L)
    val sent = s.requests("putItem").head.asInstanceOf[PutItemRequest]
    assertEquals(
      sent.conditionExpression,
      "attribute_exists(#pk) AND (attribute_not_exists(#v) OR #v = :expected)"
    )
  }

  test("an invalid item is refused before any request") {
    val s = stub
    assertEquals(code(table(s).putIfAbsent(item - "sk")), ErrorCode.InvalidItem)
    assertEquals(
      code(table(s).putIfAbsent(item + ("ttl" -> ujson.Num(5)))),
      ErrorCode.TtlNotAllowed
    )
    assertEquals(s.names, Vector.empty)
  }

  test("get is a consistent read and absent is None") {
    val found = item.map { case (k, v) => k -> DynamoValues.toAttribute(v) }
    val s = stub.on("getItem")(_ =>
      GetItemResponse.builder().item(found.asJava).build()
    )
    assertEquals(
      table(s).get("RUN#run:v1:x", "META").map(_("status")),
      Some(ujson.Str("STARTED"))
    )
    val sent = s.requests("getItem").head.asInstanceOf[GetItemRequest]
    assert(sent.consistentRead.booleanValue)
    assertEquals(sent.key.get("pk").s, "RUN#run:v1:x")
    assertEquals(sent.key.get("sk").s, "META")
    val empty = stub.on("getItem")(_ => GetItemResponse.builder().build())
    assertEquals(table(empty).get("a", "b"), None)
  }

  private def row(sk: String): java.util.Map[String, AttributeValue] =
    Map("pk" -> str("RUN#r"), "sk" -> str(sk)).asJava

  test("query pages with LastEvaluatedKey until limit items are in") {
    val s = stub.on("query") { request =>
      val q = request.asInstanceOf[QueryRequest]
      if q.exclusiveStartKey == null || q.exclusiveStartKey.isEmpty then
        QueryResponse
          .builder()
          .items(row("STAGE#a"), row("STAGE#b"))
          .lastEvaluatedKey(row("STAGE#b"))
          .build()
      else QueryResponse.builder().items(row("STAGE#c"), row("STAGE#d")).build()
    }
    val items = table(s).query("RUN#r", Some("STAGE#"), 3)
    assertEquals(
      items.map(_("sk").str),
      Vector("STAGE#a", "STAGE#b", "STAGE#c")
    )
    val requests = s.requests("query").map(_.asInstanceOf[QueryRequest])
    assertEquals(requests.map(_.limit.intValue), Vector(3, 1))
    assertEquals(
      requests.head.keyConditionExpression,
      "#pk = :pk AND begins_with(#sk, :prefix)"
    )
    assertEquals(
      requests.head.expressionAttributeValues.get(":prefix").s,
      "STAGE#"
    )
    assertEquals(requests.head.expressionAttributeValues.get(":pk").s, "RUN#r")
    assert(requests.head.consistentRead.booleanValue)
    assertEquals(requests.head.indexName, null)
  }

  test("query without a prefix uses the partition key alone") {
    val s = stub.on("query")(_ => QueryResponse.builder().build())
    assertEquals(table(s).query("RUN#r", None, 5), Vector.empty)
    val sent = s.requests("query").head.asInstanceOf[QueryRequest]
    assertEquals(sent.keyConditionExpression, "#pk = :pk")
  }

  test("queryIndex names the index and its own key attributes") {
    val s = stub.on("query")(_ => QueryResponse.builder().build())
    table(s).queryIndex("status-index", "STATUS#STARTED", None, 10)
    val sent = s.requests("query").head.asInstanceOf[QueryRequest]
    assertEquals(sent.indexName, "status-index")
    assertEquals(
      sent.expressionAttributeNames.asScala.toMap,
      Map("#pk" -> "gsi1pk")
    )
    assert(sent.consistentRead == null)
    assertEquals(
      code(table(s).queryIndex("nope", "x", None, 1)),
      ErrorCode.InvalidItem
    )
  }

  test("throttling is transient, a validation error is not retried") {
    val throttled = stub.on("putItem")(_ =>
      throw SdkErrors.error(400, "ProvisionedThroughputExceededException")
    )
    intercept[TransientStorageException](table(throttled).putIfAbsent(item))
    val invalid =
      stub.on("getItem")(_ => throw SdkErrors.error(400, "ValidationException"))
    assertEquals(code(table(invalid).get("a", "b")), ErrorCode.AwsRejected)
    assertEquals(invalid.requests("getItem").size, 1)
  }

  test("values round trip: strings, numbers, booleans, null, lists, maps") {
    val value: Item = Map(
      "s" -> ujson.Str("x"),
      "n" -> ujson.Num(12),
      "d" -> ujson.Num(0.25),
      "b" -> ujson.Bool(true),
      "z" -> ujson.Null,
      "l" -> ujson.Arr("a", 1),
      "m" -> ujson.Obj("k" -> "v")
    )
    val back = DynamoValues.toItem(
      value.map { case (k, v) => k -> DynamoValues.toAttribute(v) }.asJava
    )
    assertEquals(back, value)
    assertEquals(DynamoValues.toAttribute(ujson.Num(12)).n, "12")
    assertEquals(DynamoValues.toAttribute(ujson.Num(0.1)).n, "0.1")
  }
