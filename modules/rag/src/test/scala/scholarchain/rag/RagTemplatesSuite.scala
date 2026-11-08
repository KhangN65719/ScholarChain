package scholarchain.rag

import java.util.Locale
import scholarchain.storage.*

class RagTemplatesSuite extends munit.FunSuite:
  import RagFixtures.*

  private def byId(short: String): Row =
    summaries.find(_("summaryId") == ujson.Str(id(short))).get

  private def rowText(row: Row): String = ujson.write(ujson.Obj.from(row))

  private val number = "\\d+".r
  private val identifier = "[a-z-]+:v1:[A-Za-z0-9_:-]+".r

  test("researcher profile: fixed field order and wording") {
    val doc = RagTemplates.render(byId("p1"))
    assertEquals(doc.templateVersion, "rag-template/researcher-profile/1")
    val lines = doc.text.split("\n").toVector
    assertEquals(lines.head, "Researcher profile")
    assertEquals(
      lines.map(_.takeWhile(_ != ':')).tail,
      Vector(
        "Subject",
        "Window",
        "Window years",
        "Node kind",
        "Summary",
        "Metrics named by the evidence",
        "Evidence works",
        "Evidence nodes"
      )
    )
    assertEquals(lines(2), "Window: 2000-2009")
    assertEquals(lines(3), "Window years: 2000 to 2009")
    assertEquals(lines(4), "Node kind: canonical")
    assertEquals(
      lines(6),
      "Metrics named by the evidence: publicationCount, " +
        "distinctCoauthorCount, weightedDegree"
    )
    assert(lines(5).contains("Researcher Ada Quillfeather in window"))
  }

  test("every summary type renders with its own heading and version") {
    val seen = summaries
      .groupBy(r => Rows.str(r, "summaryType"))
      .view
      .mapValues(rows => RagTemplates.render(rows.head))
      .toMap
    assertEquals(seen.keySet, RagTemplates.summaryTypes.toSet)
    assertEquals(
      seen.map((k, d) => k -> d.templateVersion),
      Map(
        "researcher-profile" -> "rag-template/researcher-profile/1",
        "community" -> "rag-template/community/1",
        "work" -> "rag-template/work/1",
        "collaboration-path" -> "rag-template/collaboration-path/1"
      )
    )
    assertEquals(
      seen.map((k, d) => k -> d.text.linesIterator.next()),
      Map(
        "researcher-profile" -> "Researcher profile",
        "community" -> "Community profile",
        "work" -> "Representative paper",
        "collaboration-path" -> "Collaboration path"
      )
    )
    assertEquals(
      RagTemplates.versions,
      seen.map((k, d) => k -> d.templateVersion)
    )
  }

  test("a path summary and a work summary render") {
    val path = RagTemplates.render(byId("x2")).text
    assert(path.contains("Collaboration path\nSubject: pair:boris-dana"))
    assert(path.contains("no collaboration path connects them."))
    val work = RagTemplates.render(byId("w1")).text
    assert(work.contains("Window: not-windowed"))
    assert(work.contains(s"Evidence works: $w1"))
  }

  test("rendering is deterministic, in any locale") {
    val first = summaries.map(RagTemplates.render)
    assertEquals(summaries.map(RagTemplates.render), first)
    val saved = Locale.getDefault
    try
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals(summaries.map(RagTemplates.render), first)
    finally Locale.setDefault(saved)
    // The row's field order is not an input.
    val shuffled = byId("p1").toVector.reverse.toMap
    assertEquals(RagTemplates.render(shuffled), RagTemplates.render(byId("p1")))
  }

  test("no number or ID appears that is not in the row") {
    summaries.foreach { row =>
      val doc = RagTemplates.render(row)
      val source = rowText(row)
      val sourceNumbers = number.findAllIn(source).toSet
      val sourceIds = identifier.findAllIn(source).toSet
      val extraNumbers = number.findAllIn(doc.text).toSet.diff(sourceNumbers)
      val extraIds = identifier.findAllIn(doc.text).toSet.diff(sourceIds)
      assertEquals(extraNumbers, Set.empty[String], row("summaryId").str)
      assertEquals(extraIds, Set.empty[String], row("summaryId").str)
    }
  }

  test("the fixed wording of a row with nothing in it holds no digit") {
    val bare = Map(
      "summaryId" -> ujson.Str("summary:v1:z"),
      "summaryType" -> ujson.Str("community"),
      "subjectId" -> ujson.Str("c"),
      "summaryText" -> ujson.Str("text")
    )
    val doc = RagTemplates.render(bare)
    assert(!doc.text.exists(_.isDigit), doc.text)
  }

  test("missing fields are stated, not guessed") {
    val doc = RagTemplates.render(
      byId("p1") ++ Map(
        "nodeKind" -> ujson.Null,
        "windowStart" -> ujson.Null,
        "window" -> ujson.Null,
        "evidenceWorkIds" -> ujson.Arr(),
        "evidenceMetrics" -> ujson.Null,
        "evidenceNodeIds" -> ujson.Null
      )
    )
    val none = "not recorded in the source row"
    assert(doc.text.contains(s"Window: $none"))
    assert(doc.text.contains(s"Window years: $none"))
    assert(doc.text.contains(s"Node kind: $none"))
    val absent = "none recorded in the source row"
    assert(doc.text.contains(s"Metrics named by the evidence: $absent"))
    assert(doc.text.contains(s"Evidence works: $absent"))
    assert(doc.text.contains(s"Evidence nodes: $absent"))
  }

  test("an unknown summary type is refused, not rendered generically") {
    val error = intercept[StorageException](
      RagTemplates.render(
        byId("p1") + ("summaryType" -> ujson.Str("topic-summary"))
      )
    )
    assertEquals(error.code, ErrorCode.UnmappedSummary)
    assert(error.getMessage.contains("topic-summary"))
    assert(error.getMessage.contains("researcher-profile"))
  }

  test("a row without its text, subject or type is refused") {
    Vector("summaryText", "subjectId", "summaryType").foreach { column =>
      val error =
        intercept[StorageException](RagTemplates.render(byId("p1") - column))
      assertEquals(error.code, ErrorCode.MalformedRow, column)
    }
    val blank = byId("p1") + ("summaryText" -> ujson.Str("  "))
    assertEquals(
      intercept[StorageException](RagTemplates.render(blank)).code,
      ErrorCode.MalformedRow
    )
  }

  test("the renderer's text is what the ingest hashes, stores and embeds") {
    val env = new Env()
    env.run()
    val row = byId("p3")
    val hit = env.bucket
      .getVectors(VectorIndexes.ResearcherProfile, Vector(id("p3")))
      .head
    val doc = RagTemplates.render(row)
    assertEquals(
      hit.metadata("templateVersion"),
      MetaValue.Str(doc.templateVersion)
    )
    assertEquals(
      hit.metadata("textHash"),
      MetaValue.Str(Hashing.sha256(doc.text))
    )
    assertEquals(hit.embedding, env.embedder.embed(doc.text))
  }
