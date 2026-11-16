package scholarchain.storage

import scala.collection.mutable

/** Work order 105 item A3: AWS error text can carry ARNs with the 12-digit
  * account ID. The ID is masked where the failure text is built, so the live
  * LOADER_BATCH_FAILED line, the ledger item and the report (which rag writes
  * to `ingest-run.json`) carry the same masked text.
  */
class FailureTextMaskSuite extends munit.FunSuite:
  import Fixtures.*

  private val source = SourceRun(runId, "spark-analytics/1.1.0")
  private val profile = VectorIndexes.ResearcherProfile
  private val id = "123456789012"
  private val mask = "<account-id>"

  private val accessDenied =
    s"AwsAccessDenied: PutVectors: HTTP 403 AccessDeniedException User: " +
      s"arn:aws:sts::$id:assumed-role/loader/i-0abc is not authorized to " +
      s"perform: s3vectors:PutVectors on resource: " +
      s"arn:aws:s3vectors:us-east-1:$id:bucket/vec " +
      "(request 5b3c9d2e-1f4a-4c6b-8d7e-9a0b1c2d3e4f)"

  private val accessDeniedMasked = accessDenied.replace(id, mask)

  // Unit: the helper -------------------------------------------------------

  test("maskAccountIds masks a run of exactly 12 digits between delimiters") {
    assertEquals(
      Text.maskAccountIds(s"arn:aws:iam::$id:role/x"),
      s"arn:aws:iam::$mask:role/x"
    )
    assertEquals(Text.maskAccountIds(id), mask)
    assertEquals(Text.maskAccountIds(s"bucket-$id-data"), s"bucket-$mask-data")
    assertEquals(Text.maskAccountIds(s"($id)."), s"($mask).")
    assertEquals(Text.maskAccountIds(s"$id and $id"), s"$mask and $mask")
    assertEquals(Text.maskAccountIds(""), "")
  }

  test("maskAccountIds leaves shorter and longer digit runs alone") {
    Vector("12345678901", "1234567890123", "123456789012345678", "0", "2026")
      .foreach(text => assertEquals(Text.maskAccountIds(text), text))
    assertEquals(
      Text.maskAccountIds(s"n=1234567890123 m=12345678901 a=$id"),
      s"n=1234567890123 m=12345678901 a=$mask"
    )
  }

  private val hexWithTwelveDigits = "ab" + id + "cd" + ("e" * 48)

  test("maskAccountIds leaves hex digests and UUID request IDs alone") {
    assertEquals(hexWithTwelveDigits.length, 64)
    val digest = Hashing.sha256("anything")
    val uuid = "123e4567-e89b-12d3-a456-426614174000"
    val text =
      s"hash $hexWithTwelveDigits sha $digest request $uuid key summary:v1:r1"
    assertEquals(Text.maskAccountIds(text), text)
  }

  test("maskAccountIds is idempotent") {
    val once = Text.maskAccountIds(accessDenied)
    assertEquals(Text.maskAccountIds(once), once)
    assert(!once.contains(id), once)
  }

  test("oneLine masks the ID as well as flattening control characters") {
    assertEquals(
      EmbeddingIngest.oneLine(s"first\nUser $id\tsecond"),
      s"first User $mask second"
    )
  }

  // The ledger, directly ---------------------------------------------------

  test("the ledger masks the ID in a failure it is handed") {
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val ledger =
      new EmbeddingLedger(table, "exec", runId, source.transformVersion)
    ledger.recordBatch(
      profile,
      0,
      "FAILED",
      1L,
      Map("items" -> 1L),
      Some(accessDenied),
      "digest"
    )
    val stored = Attr
      .optS(
        table.get("RUN#exec", s"STAGE#batch#$profile#000000").get,
        "failure"
      )
      .get
    assertEquals(stored, accessDeniedMasked)
  }

  // A failing batch, end to end --------------------------------------------

  private def tablesOf(rows: Vector[Row]): TableSource =
    new InMemoryTableSource(
      Map(
        "semantic-summaries" -> rows,
        "nodes-work" -> graphTables()("nodes-work"),
        "researcher-metrics" -> Vector(metricsRow(r1, "2000-2009", 2000, 2009))
      )
    )

  /** A run whose only batch fails with `message`: the progress lines, the
    * report and the ledger table.
    */
  private def failingRun(message: String) =
    val lines = mutable.ArrayBuffer.empty[String]
    val bucket = new InMemoryVectorBucket:
      override def putVectors(i: String, batch: Vector[VectorRecord]): Unit =
        throw new IllegalStateException(message)
    val table = new InMemoryDynamoTable(RunLedger.spec(TableNames("t")))
    val ingest = new EmbeddingIngest(
      new HashEmbedder,
      PassThroughRenderer,
      bucket,
      new InMemoryObjectStore,
      table,
      NoSleep,
      () => 0L,
      line =>
        lines += line
        ()
    )
    val report = ingest.run(
      tablesOf(
        Vector(summaryRow("researcher-profile", "p001", "researcher one"))
      ),
      source,
      IngestConfig("2026-11-08T00:00:00Z", 2)
    )
    val stored = Attr
      .optS(
        table.get(s"RUN#embed:$runId:1", s"STAGE#batch#$profile#000000").get,
        "failure"
      )
      .get
    (
      lines.toVector.find(_.startsWith("LOADER_BATCH_FAILED")).get,
      report.failedBatches.head.reason,
      stored
    )

  private val linePrefix = s"LOADER_BATCH_FAILED index=$profile batch=1 reason="

  test("an AccessDenied ARN is masked in the line, the ledger and the report") {
    val (line, reported, stored) = failingRun(accessDenied)
    val expected = s"IllegalStateException: $accessDeniedMasked"
    assertEquals(line, linePrefix + expected)
    assertEquals(stored, expected)
    assertEquals(reported, expected)
    // The rest of the message is intact: principal, action, request ID.
    assert(
      line.contains(s"arn:aws:sts::$mask:assumed-role/loader/i-0abc"),
      line
    )
    assert(line.contains("s3vectors:PutVectors"), line)
    assert(line.contains("5b3c9d2e-1f4a-4c6b-8d7e-9a0b1c2d3e4f"), line)
    Vector(line, reported, stored).foreach(t => assert(!t.contains(id), t))
  }

  test("digest, long number and request ID are untouched in all three") {
    val digest = Hashing.sha256("anything")
    val message =
      s"hash $hexWithTwelveDigits sha $digest count 1234567890123 " +
        "short 12345678901 request 123e4567-e89b-12d3-a456-426614174000"
    val (line, reported, stored) = failingRun(message)
    val expected = s"IllegalStateException: $message"
    assertEquals(line, linePrefix + expected)
    assertEquals(stored, expected)
    assertEquals(reported, expected)
  }

  test("an ID at the 600-character cut is masked before the cut") {
    val message = ("p" * 570) + s" $id tail"
    val (line, _, stored) = failingRun(message)
    // 23 characters of class name, 570 of padding and one space put the ID at
    // character 594, so an unmasked cut would keep its first six digits.
    assertEquals(line.drop(linePrefix.length).length, 600)
    assertEquals(stored.length, 600)
    Vector(line, stored).foreach { text =>
      assert(!text.contains("1234"), text)
      assert(text.endsWith("<accou"), text)
    }
  }

  test("the empty-array reason of S run 7 is unchanged") {
    val message =
      "AwsRejected: PutVectors: HTTP 400 ValidationException Invalid record " +
        "for key 'summary:v1:x': Empty arrays are not allowed in metadata " +
        "(request 0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9)"
    val (line, reported, stored) = failingRun(message)
    val expected = s"IllegalStateException: $message"
    assertEquals(line, linePrefix + expected)
    assertEquals(stored, expected)
    assertEquals(reported, expected)
  }
