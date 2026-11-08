package scholarchain.tests.adversarial

import java.nio.file.Files
import java.util.Locale
import munit.FunSuite
import scala.collection.immutable.ListMap
import scala.util.Random
import scholarchain.rag.*
import scholarchain.storage.*
import P8.*

/** Spec item 8: templates, chunking, the semantic-context format and the
  * re-runnable `IngestMain`.
  */
class Phase8RagSuite extends FunSuite:
  private def row(label: String): Row =
    World.summaries(World.specs.indexWhere(_.label == label))

  // Templates ---------------------------------------------------------------

  // The JVM default Locale is global and root suites run in parallel in one
  // JVM, so no test here calls Locale.setDefault. RagTemplates reads no locale
  // (plain string interpolation of Long and String, no format call), so the
  // locale-dependent risks are checked on its output: numbers are ASCII digits
  // and locale-sensitive letters pass through byte for byte, with the default
  // locale untouched.
  test("templates render locale-sensitive input without locale effects"):
    val defaultBefore = Locale.getDefault
    val tricky = Vector(
      "TITLE I İSTANBUL Iıİi ß ǅ",
      "عدد ٣ مؤلفين ١٢٣",
      "สามผู้เขียน ๑๒๓",
      "２０２０ ＡＢＣ"
    )
    tricky.foreach { text =>
      val r = row("workA").updated("summaryText", s(text))
      val rendered = RagTemplates.render(r)
      assertEquals(rendered, RagTemplates.render(r), "repeatable")
      assert(rendered.text.contains(s"Summary: $text"), "kept byte for byte")
    }
    val windowed = RagTemplates.render(row("adaW1")).text
    assert(windowed.contains("Window years: 2015 to 2019"), windowed)
    val big = row("adaW1").updated("windowStart", n(1234567890L))
    assert(
      RagTemplates.render(big).text.contains("Window years: 1234567890 to 2019")
    )
    assertEquals(Locale.getDefault, defaultBefore)

  test("templates ignore map insertion order"):
    val random = new Random(8)
    World.summaries.foreach { r =>
      val expected = RagTemplates.render(r)
      (0 until 20).foreach { _ =>
        val shuffled = ListMap.from(random.shuffle(r.toVector))
        assertEquals(RagTemplates.render(shuffled), expected)
      }
    }

  test("rendering is repeatable and keeps the text byte for byte"):
    World.summaries.foreach { r =>
      val first = RagTemplates.render(r)
      (0 until 5).foreach(_ => assertEquals(RagTemplates.render(r), first))
      val original = r("summaryText").str
      assertEquals(first.text.split(original, -1).length, 2, "text kept once")
    }

  private val digits = "\\d+".r
  private val longIds = "[a-z]+:v1:[0-9a-f]{64}".r

  private def rowValues(r: Row): String =
    def flat(v: ujson.Value): Vector[String] = v match
      case ujson.Str(x)  => Vector(x)
      case ujson.Num(x)  => Vector(x.toLong.toString)
      case ujson.Arr(xs) => xs.toVector.flatMap(flat)
      case _             => Vector.empty
    r.values.toVector.flatMap(flat).mkString("\n")

  test("a template never introduces a number or an ID absent from the row"):
    val extras = World.summaries.flatMap { r =>
      Vector(
        r - "window" - "windowStart" - "windowEnd",
        r - "nodeKind" - "evidenceMetrics",
        r.updated("evidenceNodeIds", ujson.Arr()),
        r.updated("evidenceWorkIds", ujson.Null)
      )
    }
    (World.summaries ++ extras).foreach { r =>
      val text = RagTemplates.render(r).text
      val present = rowValues(r)
      val rowNumbers = digits.findAllIn(present).toSet
      digits.findAllIn(text).foreach { number =>
        assert(rowNumbers.contains(number), s"invented number $number")
      }
      val rowIds = longIds.findAllIn(present).toSet
      longIds.findAllIn(text).foreach { found =>
        assert(rowIds.contains(found), s"invented id $found")
      }
    }

  test("fixed template wording contains no digit at all"):
    Vector("researcher-profile", "community", "work", "collaboration-path")
      .foreach { kind =>
        val bare = Map(
          "summaryId" -> s("summary-x"),
          "summaryType" -> s(kind),
          "subjectId" -> s("someone"),
          "summaryText" -> s("plain words only"),
          "provenance" -> prov("x")
        )
        val rendered = RagTemplates.render(bare)
        assert(!rendered.text.exists(_.isDigit), s"$kind: ${rendered.text}")
        assert(
          !rendered.templateVersion.isEmpty,
          "every template has a version"
        )
      }

  test("a missing field is stated as missing, never guessed"):
    val bare = row("workA") - "nodeKind" - "evidenceMetrics" -
      "evidenceNodeIds" - "windowStart" - "windowEnd" - "window"
    val text = RagTemplates.render(bare).text
    assert(text.contains(RagTemplates.NotRecorded), text)
    assert(text.contains(RagTemplates.NoneRecorded), text)

  test("unknown types and unusable rows are refused"):
    assertEquals(
      code(
        RagTemplates.render(row("workA").updated("summaryType", s("topic")))
      ),
      ErrorCode.UnmappedSummary
    )
    assertEquals(
      code(RagTemplates.render(row("workA") - "summaryText")),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(RagTemplates.render(row("workA") - "subjectId")),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(RagTemplates.render(row("workA").updated("summaryText", s(" \n ")))),
      ErrorCode.MalformedRow
    )

  test("template versions are per type, versioned and stable"):
    val versions = World.summaries.map(r =>
      r("summaryType").str -> RagTemplates.render(r).templateVersion
    )
    assertEquals(
      versions.toMap,
      Map(
        "researcher-profile" -> "rag-template/researcher-profile/1",
        "community" -> "rag-template/community/1",
        "work" -> "rag-template/work/1",
        "collaboration-path" -> "rag-template/collaboration-path/1"
      )
    )

  // Chunking ----------------------------------------------------------------

  private def chunkOf(text: String, policy: ChunkPolicy) =
    Chunker.chunk("entity-1", "v1", "abstract", text, policy)

  private def assertChunkInvariants(
      text: String,
      policy: ChunkPolicy,
      chunks: Vector[TextChunk]
  ): Unit =
    assertEquals(Chunker.reassemble(chunks), text, "no text lost")
    assertEquals(chunks.map(_.ordinal), chunks.indices.toVector)
    assertEquals(chunks.head.start, 0)
    assertEquals(chunks.last.end, text.length)
    chunks.foreach { c =>
      assertEquals(c.text, text.substring(c.start, c.end))
      assert(c.text.length <= policy.maxChars, s"too long ${c.text.length}")
      // A cut never lands inside a word or a surrogate pair.
      if c.start > 0 then
        assert(Character.isWhitespace(text(c.start - 1)), s"start ${c.start}")
        assert(!Character.isWhitespace(text(c.start)), s"start ${c.start}")
      if c.end < text.length then
        assert(Character.isWhitespace(text(c.end - 1)), s"end ${c.end}")
        assert(!Character.isWhitespace(text(c.end)), s"end ${c.end}")
        assert(!Character.isLowSurrogate(text(c.end)))
    }
    chunks.zip(chunks.tail).foreach { (a, b) =>
      assert(b.start > a.start, "chunks advance")
      assert(a.end - b.start <= policy.overlapChars, "overlap within limit")
      assert(b.start <= a.end, "no gap")
    }
    assertEquals(chunks.map(_.chunkId).distinct.size, chunks.size)

  test("short text is one unchanged chunk"):
    val policy = ChunkPolicy(100, 10)
    val text = "short text here"
    val chunks = chunkOf(text, policy)
    assertEquals(chunks.size, 1)
    assertEquals(chunks.head.text, text)
    val exact = "x" * 100
    assertEquals(chunkOf(exact, policy).size, 1)

  test("chunking never loses or splits text: 300 random texts"):
    val random = new Random(300)
    val pieces = Vector(
      "alpha",
      "béta",
      "γάμμα",
      "张伟",
      "😀",
      "word",
      "a",
      "x" * 20,
      "  ",
      "\n",
      "\t",
      "\r\n",
      "   ",
      "end."
    )
    (0 until 300).foreach { _ =>
      val policy = ChunkPolicy(
        50 + random.nextInt(200),
        0
      )
      val withOverlap =
        ChunkPolicy(policy.maxChars, random.nextInt(policy.maxChars / 2 + 1))
      val parts = Vector.fill(1 + random.nextInt(400)) {
        val piece = pieces(random.nextInt(pieces.size))
        if piece.trim.isEmpty then piece else s" $piece"
      }
      val text = parts.mkString
      if text.exists(!Character.isWhitespace(_)) then
        try
          val chunks = chunkOf(text, withOverlap)
          assertChunkInvariants(text, withOverlap, chunks)
          assertEquals(chunkOf(text, withOverlap), chunks, "deterministic")
        catch
          case e: StorageException =>
            // The only permitted refusal: a run with no cut point in range.
            assertEquals(e.code, ErrorCode.BoundExceeded, e.getMessage)
    }

  test("a long ordinary text is chunked within bounds and reassembles"):
    val random = new Random(5)
    val words = Vector.fill(5000)(s"w${random.nextInt(1000)}")
    val text = words.mkString(" ")
    val chunks = chunkOf(text, ChunkPolicy.Default)
    assert(chunks.size > 5)
    assertChunkInvariants(text, ChunkPolicy.Default, chunks)

  test("blank text and an over-long word are refused, not cut"):
    assertEquals(
      code(chunkOf(" \n\t ", ChunkPolicy.Default)),
      ErrorCode.MissingText
    )
    assertEquals(
      code(chunkOf("start " + ("z" * 200) + " end", ChunkPolicy(100, 10))),
      ErrorCode.BoundExceeded
    )

  test("a long run of whitespace reassembles or is refused, never lost"):
    val text = "left" + (" " * 3000) + "right"
    val outcome =
      try Right(chunkOf(text, ChunkPolicy(1000, 100)))
      catch case e: StorageException => Left(e.code)
    outcome match
      case Right(chunks) => assertEquals(Chunker.reassemble(chunks), text)
      case Left(failure) => assertEquals(failure, ErrorCode.BoundExceeded)

  test("a refused whitespace run says whitespace, an over-long word says word"):
    val spaced = intercept[StorageException](
      chunkOf("left" + (" " * 3000) + "right", ChunkPolicy(1000, 100))
    )
    assertEquals(spaced.code, ErrorCode.BoundExceeded)
    assert(spaced.getMessage.contains("whitespace run"), spaced.getMessage)
    assert(!spaced.getMessage.contains("longer than"), spaced.getMessage)
    val word = intercept[StorageException](
      chunkOf("start " + ("z" * 200) + " end", ChunkPolicy(100, 10))
    )
    assertEquals(word.code, ErrorCode.BoundExceeded)
    assert(word.getMessage.contains("longer than"), word.getMessage)
    assert(!word.getMessage.contains("whitespace run"), word.getMessage)

  test("chunk IDs are stable and depend on entity, version, type and text"):
    val text = (1 to 400).map(i => s"token$i").mkString(" ")
    val policy = ChunkPolicy(200, 20)
    val base = Chunker.chunk("e1", "v1", "abstract", text, policy)
    assertEquals(Chunker.chunk("e1", "v1", "abstract", text, policy), base)
    val otherEntity = Chunker.chunk("e2", "v1", "abstract", text, policy)
    val otherVersion = Chunker.chunk("e1", "v2", "abstract", text, policy)
    val otherType = Chunker.chunk("e1", "v1", "title", text, policy)
    val ids = (base ++ otherEntity ++ otherVersion ++ otherType).map(_.chunkId)
    assertEquals(ids.distinct.size, ids.size)
    val edited = Chunker.chunk("e1", "v1", "abstract", text + " tail", policy)
    assertNotEquals(edited.last.chunkId, base.last.chunkId)

  test("policy values are bounded"):
    intercept[IllegalArgumentException](ChunkPolicy(10, 0))
    intercept[IllegalArgumentException](ChunkPolicy(100, 51))
    intercept[IllegalArgumentException](ChunkPolicy(100, -1))

  // Semantic-context format -------------------------------------------------

  private def fetched(text: String, key: String, ordinal: Int): RetrievedHit =
    val hash = sha(text)
    val md = Map(
      "documentType" -> MetaValue.Str("work"),
      "subjectId" -> MetaValue.Str(s"subject-$ordinal"),
      "textRef" -> MetaValue.Str(s"semantic-context/documents/x$ordinal.json"),
      "textHash" -> MetaValue.Str(hash),
      "evidenceWorkIds" -> MetaValue.Strs(Vector("a", "b")),
      "evidenceNodeIds" -> MetaValue.Strs(Vector("c"))
    )
    RetrievedHit(
      VectorIndexes.PaperAbstract,
      VectorMatch(key, 0.25, md),
      FetchedText(text, "ref", key, runId, "work", "t/1", hash)
    )

  private def hits(count: Int, size: Int): Vector[RetrievedHit] =
    Vector.tabulate(count) { i =>
      fetched(("w" * 9 + " ") * (size / 10), s"summary:v1:$i", i)
    }

  private def build(h: Vector[RetrievedHit], query: String = "query") =
    SemanticContext.fromHits(query, "hash-embed-v1", h)

  private val itemKeys = Set(
    "vectorKey",
    "index",
    "documentType",
    "distance",
    "window",
    "windowStart",
    "windowEnd",
    "subjectId",
    "evidenceWorkIds",
    "evidenceNodeIds",
    "textRef",
    "templateVersion",
    "textHash",
    "snippet",
    "snippetTruncated",
    "textChars"
  )

  test("the context carries exactly the agreed keys and no metric key"):
    val json = build(hits(2, 100)).toJson
    assertEquals(
      json.obj.keySet.toSet,
      Set(
        "schema",
        "embeddingModel",
        "query",
        "deterministicMetricsIncluded",
        "metricsNotice",
        "items"
      )
    )
    json("items").arr.foreach { item =>
      assertEquals(item.obj.keySet.toSet, itemKeys)
      assert(item.obj.keySet.intersect(metricKeys).isEmpty)
    }
    assertEquals(json("deterministicMetricsIncluded"), ujson.False)
    val notice = json("metricsNotice").str.toLowerCase
    assert(notice.contains("not included"), notice)
    assert(notice.contains("vector similarity"), notice)

  test("a context round trips through JSON"):
    val context = build(hits(3, 300), "ada lovelace")
    assertEquals(SemanticContext.read(SemanticContext.write(context)), context)

  test("item cap: 20 pass, 21 are refused on build and on read"):
    assertEquals(build(hits(20, 100)).items.size, 20)
    assertEquals(code(build(hits(21, 100))), ErrorCode.BoundExceeded)
    val json = build(hits(20, 100)).toJson
    json("items").arr.append(json("items")(0))
    assertEquals(
      code(SemanticContext.read(ujson.write(json))),
      ErrorCode.BoundExceeded
    )

  test("a long text is cut once at a word boundary and says so"):
    val long = (1 to 600).map(i => s"word$i").mkString(" ")
    val item = build(Vector(fetched(long, "summary:v1:long", 0))).items.head
    assert(item.snippetTruncated)
    assert(item.snippet.length <= SemanticContext.MaxSnippetChars)
    assertEquals(item.textChars, long.length)
    assert(long.startsWith(item.snippet), "the snippet is a prefix")
    assert(
      long.charAt(item.snippet.length) == ' ',
      "the cut falls on a word boundary"
    )
    val short =
      build(Vector(fetched("short text", "summary:v1:s", 1))).items.head
    assert(!short.snippetTruncated)
    assertEquals(short.snippet, "short text")

  private def utf8Bytes(text: String): Int =
    text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length

  private def totalBytes(context: SemanticContext): Int =
    context.items.map(i => utf8Bytes(i.snippet)).sum

  private def directItem(base: ContextItem, snippet: String): ContextItem =
    base.copy(
      snippet = snippet,
      snippetTruncated = false,
      textChars = snippet.length
    )

  private def direct(items: Vector[ContextItem]): SemanticContext =
    SemanticContext(
      SemanticContext.Schema,
      "hash-embed-v1",
      "query",
      false,
      SemanticContext.MetricsNotice,
      items
    )

  private lazy val baseItem: ContextItem = build(hits(1, 100)).items.head

  test("the cap constants are the documented ones and agree with each other"):
    assertEquals(SemanticContext.MaxItems, 20)
    assertEquals(SemanticContext.MaxSnippetChars, 1500)
    assertEquals(SemanticContext.MaxTotalSnippetBytes, 24 * 1024)
    assertEquals(SemanticContext.snippetBytesFor(16), 1536)
    assertEquals(SemanticContext.snippetBytesFor(20), 1228)
    (1 to SemanticContext.MaxItems).foreach { count =>
      assert(
        count * SemanticContext.snippetBytesFor(count) <=
          SemanticContext.MaxTotalSnippetBytes,
        s"$count shares overflow the total"
      )
    }
    val source = java.nio.file.Paths.get(
      "modules/rag/src/main/scala/scholarchain/rag/SemanticContext.scala"
    )
    assume(Files.exists(source), "rag source not visible from this directory")
    val doc = Files.readString(source)
    assert(doc.contains("`MaxItems` (20)"), "documented item cap")
    assert(doc.contains("`MaxSnippetChars` (1500)"), "documented snippet cap")
    assert(doc.contains("`MaxTotalSnippetBytes` (24 KiB)"), "documented total")

  test("long documents at the maximum item count are accepted within the cap"):
    val long = hits(SemanticContext.MaxItems, 4000)
    val context = build(long)
    assertEquals(context.items.size, SemanticContext.MaxItems)
    assert(totalBytes(context) <= SemanticContext.MaxTotalSnippetBytes)
    context.items.zip(long).foreach { (item, source) =>
      assert(item.snippetTruncated, "a cut is marked")
      assertEquals(item.textChars, 4000)
      assert(source.text.text.startsWith(item.snippet), "a prefix")
      assert(item.snippet.length <= SemanticContext.MaxSnippetChars)
      assert(
        utf8Bytes(item.snippet) <=
          SemanticContext.snippetBytesFor(SemanticContext.MaxItems)
      )
    }
    assertEquals(SemanticContext.read(SemanticContext.write(context)), context)

  test("every item count up to the maximum builds from 1500-character texts"):
    (1 to SemanticContext.MaxItems).foreach { count =>
      val context = build(hits(count, 1500))
      assertEquals(context.items.size, count)
      assert(
        totalBytes(context) <= SemanticContext.MaxTotalSnippetBytes,
        s"$count items exceed the total"
      )
      context.items.foreach { item =>
        assertEquals(item.textChars, 1500)
        assertEquals(item.snippetTruncated, count > 16, s"count $count")
      }
    }

  test("multibyte long documents at the item cap count bytes and stay valid"):
    val wide = Vector.tabulate(SemanticContext.MaxItems)(i =>
      fetched(("é" * 1499) + "x", s"summary:v1:big$i", i)
    )
    val wideContext = build(wide)
    assert(totalBytes(wideContext) <= SemanticContext.MaxTotalSnippetBytes)
    wideContext.items.foreach { item =>
      assert(item.snippetTruncated)
      assert(
        utf8Bytes(item.snippet) <=
          SemanticContext.snippetBytesFor(SemanticContext.MaxItems)
      )
    }
    val emoji = Vector.tabulate(SemanticContext.MaxItems)(i =>
      fetched("😀" * 1000, s"summary:v1:emoji$i", i)
    )
    val emojiContext = build(emoji)
    assert(totalBytes(emojiContext) <= SemanticContext.MaxTotalSnippetBytes)
    emojiContext.items.foreach { item =>
      assert(item.snippetTruncated)
      assert(item.snippet.nonEmpty)
      assertEquals(
        item.snippet.codePointCount(0, item.snippet.length) * 2,
        item.snippet.length,
        "no split surrogate pair"
      )
      assert(!Character.isHighSurrogate(item.snippet.last))
    }

  test("items over the total cap that arrive another way are refused"):
    val cap = SemanticContext.MaxTotalSnippetBytes
    val full = "x" * 1500
    // 16 x 1500 + 576 is exactly the 24 KiB cap; one more byte is over it.
    val atCap = Vector.fill(16)(directItem(baseItem, full)) :+
      directItem(baseItem, "y" * (cap - 16 * 1500))
    assertEquals(totalBytes(direct(atCap)), cap)
    val over = atCap.init :+ directItem(baseItem, "y" * (cap - 16 * 1500 + 1))
    assertEquals(code(direct(over)), ErrorCode.BoundExceeded)
    assertEquals(
      code(direct(Vector.fill(17)(directItem(baseItem, full)))),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(direct(Vector.fill(20)(directItem(baseItem, full)))),
      ErrorCode.BoundExceeded
    )
    // Multibyte text counts in bytes: 8 x 2,999 fit, 9 do not.
    val wide = directItem(baseItem, ("é" * 1499) + "x")
    assertEquals(direct(Vector.fill(8)(wide)).items.size, 8)
    assertEquals(code(direct(Vector.fill(9)(wide))), ErrorCode.BoundExceeded)
    // The same refusal on read.
    val json = direct(Vector.fill(16)(directItem(baseItem, full))).toJson
    json("items").arr.append(json("items")(0))
    assertEquals(
      code(SemanticContext.read(ujson.write(json))),
      ErrorCode.BoundExceeded
    )
    // A per-item snippet over the character cap is still refused.
    intercept[StorageException](directItem(baseItem, "x" * 1501))

  test("the query length cap is enforced"):
    assertEquals(build(hits(1, 100), "q" * 500).query.length, 500)
    assertEquals(code(build(hits(1, 100), "q" * 501)), ErrorCode.BoundExceeded)

  test("reading refuses unknown, metric and missing fields and bad flags"):
    val good = build(hits(2, 100)).toJson
    def mutate(change: ujson.Value => Unit): ErrorCode =
      val copy = ujson.read(ujson.write(good))
      change(copy)
      code(SemanticContext.read(ujson.write(copy)))
    Vector("publicationCount", "weightedDegree", "bridgeScore", "extra")
      .foreach { key =>
        assertEquals(
          mutate(_(key) = 1),
          ErrorCode.MalformedRow,
          s"top-level $key"
        )
        assertEquals(
          mutate(_("items")(0)(key) = 1),
          ErrorCode.MalformedRow,
          s"item $key"
        )
      }
    assertEquals(mutate(_.obj.remove("schema")), ErrorCode.MalformedRow)
    assertEquals(
      mutate(_("items")(0).obj.remove("snippet")),
      ErrorCode.MalformedRow
    )
    assertEquals(
      mutate(_("deterministicMetricsIncluded") = true),
      ErrorCode.MalformedRow
    )
    assertEquals(
      mutate(_("metricsNotice") = "all good"),
      ErrorCode.MalformedRow
    )
    assertEquals(mutate(_("schema") = "other/9"), ErrorCode.MalformedRow)
    assertEquals(
      mutate(_("items")(0)("distance") = "near"),
      ErrorCode.MalformedRow
    )
    assertEquals(
      mutate(_("items")(0)("snippetTruncated") = "no"),
      ErrorCode.MalformedRow
    )
    assertEquals(
      mutate(_("items")(0)("textHash") = "xyz"),
      ErrorCode.MalformedRow
    )
    assertEquals(
      mutate(_("items")(0)("snippet") = "x" * 1501),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(SemanticContext.read("{not json")),
      ErrorCode.MalformedRow
    )
    assertEquals(code(SemanticContext.read("[]")), ErrorCode.MalformedRow)

  test("a non-finite distance cannot enter a context"):
    val bad = fetched("text", "summary:v1:nan", 0)
    val nan = bad.copy(hit = bad.hit.copy(distance = Double.NaN))
    assertEquals(code(build(Vector(nan))), ErrorCode.MalformedRow)

  // IngestMain --------------------------------------------------------------

  private def runMain(exportDir: java.nio.file.Path, out: java.nio.file.Path) =
    val lines = Vector.newBuilder[String]
    val outcome =
      IngestMain.execute(IngestMain.Args(exportDir, out, None), lines += _)
    (outcome, lines.result())

  test("IngestMain is re-runnable with an identical ingest-report.json"):
    val dir = writeExport(tempDir("p8-main").resolve("export"))
    val out = tempDir("p8-main-out").resolve("out")
    val (first, firstLines) = runMain(dir, out)
    assert(first.success, firstLines.mkString("\n"))
    val report1 = Files.readAllBytes(out.resolve(IngestMain.ReportName))
    val (second, _) = runMain(dir, out)
    assert(second.success)
    assertEquals(
      Files.readAllBytes(out.resolve(IngestMain.ReportName)).toVector,
      report1.toVector
    )
    // A second output directory gives the very same report bytes.
    val other = tempDir("p8-main-out2").resolve("out")
    assert(runMain(dir, other)._1.success)
    assertEquals(
      Files.readAllBytes(other.resolve(IngestMain.ReportName)).toVector,
      report1.toVector
    )
    val report = ujson.read(report1)
    assertEquals(report("result").str, "SUCCESS")
    assertEquals(report("sourceRunId").str, runId)
    assertEquals(report("embeddingModel").str, "hash-embed-v1")
    assertEquals(report("dimension").num.toInt, 384)
    assertEquals(report("summaryRowsInExport").num.toInt, World.summaries.size)
    assertEquals(report("vectorsTotal").num.toInt, World.summaries.size)
    assertEquals(report("rejected").num.toInt, 0)
    assertEquals(report("failedBatches").num.toInt, 0)
    assertEquals(
      report("perIndex")("topic-institution-summary-v1")(
        "vectorsInIndex"
      ).num.toInt,
      0
    )
    assert(report("label").str.contains("not AWS"))
    assert(first.reason.isEmpty)
    assert(firstLines.exists(_.startsWith(IngestMain.ResultPrefix + "SUCCESS")))
    val costLine =
      "local cost: $0 (in-process fakes); AWS cost deferred to Phase 12"
    assert(firstLines.contains(costLine), firstLines.mkString("\n"))
    assertEquals(report("localCost").str, costLine)

  test(
    "IngestMain refuses an output directory for a different export or bound"
  ):
    val dir = writeExport(tempDir("p8-main").resolve("export"))
    val out = tempDir("p8-main-out").resolve("out")
    assert(runMain(dir, out)._1.success)
    val bounded =
      IngestMain.execute(IngestMain.Args(dir, out, Some(3)), _ => ())
    assert(!bounded.success, "a different bound needs its own directory")
    val otherExport = writeExport(
      tempDir("p8-main2").resolve("export"),
      World.tables.updated("semantic-summaries", World.summaries.take(5))
    )
    val changed = runMain(otherExport, out)._1
    assert(!changed.success, "a different export needs its own directory")
    // Overlap with the export and foreign non-empty directories are refused.
    assert(!runMain(dir, dir.resolve("inside"))._1.success)
    val foreign = tempDir("p8-foreign")
    Files.write(foreign.resolve("keep.txt"), utf8("do not touch"))
    assert(!runMain(dir, foreign)._1.success)
    assertEquals(Files.readString(foreign.resolve("keep.txt")), "do not touch")

  test("IngestMain with a bound ingests exactly that many rows"):
    val dir = writeExport(tempDir("p8-main").resolve("export"))
    val out = tempDir("p8-main-out").resolve("out")
    val outcome =
      IngestMain.execute(IngestMain.Args(dir, out, Some(4)), _ => ())
    assert(outcome.success, outcome.reason)
    val report =
      ujson.read(Files.readAllBytes(out.resolve(IngestMain.ReportName)))
    assertEquals(report("summaryRowsIngested").num.toInt, 4)
    assertEquals(report("vectorsTotal").num.toInt, 4)
    assertEquals(report("bound").num.toInt, 4)

  test("IngestMain argument parsing refuses nonsense"):
    Vector(
      Array.empty[String],
      Array("a"),
      Array("a", "b", "c", "d"),
      Array("a", "b", "zero"),
      Array("a", "b", "0"),
      Array("a", "b", "-3")
    ).foreach { args =>
      intercept[StorageException](IngestMain.parseArgs(args))
    }
    assertEquals(IngestMain.parseArgs(Array("a", "b", "7")).bound, Some(7))
