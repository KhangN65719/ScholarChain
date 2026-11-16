package scholarchain.storage

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.core.document.Document

/** Work order 101 item 4: the PutVectors wire rules, on their own and as the
  * local fake enforces them.
  */
class S3VectorsRulesSuite extends munit.FunSuite:
  private val nonFilterable = AwsVectorBucket.NonFilterableKeys

  private def str(value: String): Document = Document.fromString(value)

  private def doc(entries: (String, Document)*): Document =
    val map = new java.util.LinkedHashMap[String, Document]()
    entries.foreach((k, v) => map.put(k, v))
    Document.fromMap(map)

  private def list(items: Document*): Document =
    Document.fromList(items.toList.asJava)

  private def refusal(
      metadata: Document,
      key: String = "summary:v1:k"
  ): StorageException =
    intercept[StorageException](
      S3VectorsRules.check(key, metadata, nonFilterable)
    )

  private def accepted(metadata: Document, key: String = "summary:v1:k") =
    S3VectorsRules.check(key, metadata, nonFilterable)

  test("CONFIRMED: an empty array is refused and the message names the rule") {
    val error = refusal(doc("subjectId" -> str("r1"), "ids" -> list()))
    assertEquals(error.code, ErrorCode.AwsRejected)
    assert(
      error.getMessage.contains("Empty arrays are not allowed in metadata"),
      error.getMessage
    )
    assert(error.getMessage.contains("summary:v1:k"), error.getMessage)
    assert(error.getMessage.contains("ids"), error.getMessage)
  }

  test("a string, a number, a boolean and a list of strings pass") {
    accepted(
      doc(
        "a" -> str("x"),
        "b" -> Document.fromNumber(2005L),
        "c" -> Document.fromNumber(1.5),
        "d" -> Document.fromBoolean(true),
        "e" -> list(str("p"), str("q"))
      )
    )
  }

  test("INFERRED: null, a map, a nested list and a mixed list are refused") {
    val cases = Vector(
      "null" -> doc("a" -> Document.fromNull()),
      "map" -> doc("a" -> doc("b" -> str("c"))),
      "nested" -> doc("a" -> list(list(str("x")))),
      "mixed" -> doc("a" -> list(str("x"), Document.fromNumber(1L))),
      "numbers" -> doc("a" -> list(Document.fromNumber(1L))),
      "nan" -> doc("a" -> Document.fromNumber(Double.NaN))
    )
    cases.foreach { case (label, metadata) =>
      assertEquals(refusal(metadata).code, ErrorCode.AwsRejected, label)
    }
  }

  test("INFERRED: metadata that is not a map is refused, none is accepted") {
    assertEquals(refusal(str("x")).code, ErrorCode.AwsRejected)
    accepted(null)
    accepted(doc())
  }

  test("DOCUMENTED: at most 50 metadata keys") {
    def keys(n: Int) = doc((1 to n).map(i => s"k$i" -> str("v"))*)
    accepted(keys(50))
    val error = refusal(keys(51))
    assert(error.getMessage.contains("51 metadata keys"), error.getMessage)
  }

  test("DOCUMENTED: a key has 1 to 1,024 characters") {
    val metadata = doc("a" -> str("x"))
    accepted(metadata, "k" * 1024)
    assertEquals(refusal(metadata, "k" * 1025).code, ErrorCode.AwsRejected)
    assertEquals(refusal(metadata, "").code, ErrorCode.AwsRejected)
  }

  test("DOCUMENTED: filterable metadata is at most 2,048 bytes") {
    // One entry counts the UTF-8 bytes of its key plus the JSON of its value:
    // key "v" (1) plus a string of n characters in quotes (n + 2).
    accepted(doc("v" -> str("x" * (2048 - 3))))
    val error = refusal(doc("v" -> str("x" * (2048 - 2))))
    assert(error.getMessage.contains("filterable metadata"), error.getMessage)
    // Two filterable entries add up.
    assertEquals(
      refusal(doc("a" -> str("x" * 1100), "b" -> str("y" * 1100))).code,
      ErrorCode.AwsRejected
    )
  }

  test(
    "DOCUMENTED: a non-filterable key is outside the 2 KB but inside 40 KB"
  ) {
    accepted(doc("textRef" -> str("r" * 30000), "subjectId" -> str("r1")))
    val bigRef = doc("textRef" -> str("r" * 41000))
    val error = refusal(bigRef)
    assert(error.getMessage.contains("limit is 40960"), error.getMessage)
    // The same size under a filterable name fails the 2 KB rule first.
    assert(
      refusal(doc("other" -> str("r" * 3000))).getMessage
        .contains("filterable metadata")
    )
  }

  test("sizes count UTF-8 bytes, not characters") {
    // 700 characters of 3 bytes each is 2,100 bytes plus the quotes and key.
    val error = refusal(doc("v" -> str("€" * 700)))
    assert(error.getMessage.contains("filterable metadata"), error.getMessage)
  }

  test(
    "the size rule agrees with VectorMetadata.sizeBytes on a normal record"
  ) {
    val metadata = Map(
      "summaryType" -> MetaValue.Str("work"),
      "subjectId" -> MetaValue.Str("work:dblp:conf-a-1"),
      "year" -> MetaValue.Num(2005),
      "source" -> MetaValue.Strs(Vector("dblp", "orcid")),
      "flag" -> MetaValue.Flag(true)
    )
    val wire = VectorDocuments.metadata(metadata)
    val bytes = VectorMetadata.sizeBytes(metadata)
    // Exactly at the 2,048 limit when padded with one more string.
    val pad = "p" * (2048 - bytes - 3)
    accepted(VectorDocuments.metadata(metadata + ("v" -> MetaValue.Str(pad))))
    assertEquals(
      refusal(
        VectorDocuments.metadata(metadata + ("v" -> MetaValue.Str(pad + "p")))
      ).code,
      ErrorCode.AwsRejected
    )
    accepted(wire)
  }

  // The local fake -------------------------------------------------------

  private val spec = VectorIndexSpec("idx", 2, "cosine", None)

  private def record(metadata: Map[String, MetaValue]) =
    VectorRecord("summary:v1:k", Vector(1f, 0f), metadata)

  private val required = Map(
    "summaryType" -> MetaValue.Str("work"),
    "subjectId" -> MetaValue.Str("r1"),
    "textRef" -> MetaValue.Str("ref"),
    "runId" -> MetaValue.Str("run")
  )

  /** The in-process check lets everything through, so only the wire rules are
    * left to refuse a record.
    */
  private final class WireOnlyBucket extends InMemoryVectorBucket:
    override protected def validateMetadata(
        metadata: Map[String, MetaValue]
    ): Unit = ()

  test("the fake refuses an empty array on the wire rules alone") {
    val bucket = new WireOnlyBucket
    bucket.ensureIndex(spec)
    val bad = record(required + ("evidenceNodeIds" -> MetaValue.Strs(Vector())))
    val error =
      intercept[StorageException](bucket.putVectors("idx", Vector(bad)))
    assertEquals(error.code, ErrorCode.AwsRejected)
    assert(
      error.getMessage.contains("Empty arrays are not allowed in metadata"),
      error.getMessage
    )
    assertEquals(bucket.count("idx"), 0)
    bucket.putVectors("idx", Vector(record(required)))
    assertEquals(bucket.count("idx"), 1)
  }

  test(
    "the fake refuses an empty array in process first, with a clear reason"
  ) {
    val bucket = new InMemoryVectorBucket
    bucket.ensureIndex(spec)
    val bad = record(required + ("evidenceNodeIds" -> MetaValue.Strs(Vector())))
    val error =
      intercept[StorageException](bucket.putVectors("idx", Vector(bad)))
    assertEquals(error.code, ErrorCode.MetadataInvalid)
    assert(
      error.getMessage.contains(
        "S3 Vectors rejects empty arrays; confirmed on the real service in S run 7"
      ),
      error.getMessage
    )
    assertEquals(bucket.count("idx"), 0)
  }

  test("a batch with one bad record writes nothing, as the service does") {
    val bucket = new WireOnlyBucket
    bucket.ensureIndex(spec)
    val bad = VectorRecord(
      "summary:v1:bad",
      Vector(1f, 0f),
      required + ("source" -> MetaValue.Strs(Vector()))
    )
    intercept[StorageException](
      bucket.putVectors("idx", Vector(record(required), bad))
    )
    assertEquals(bucket.count("idx"), 0)
  }
