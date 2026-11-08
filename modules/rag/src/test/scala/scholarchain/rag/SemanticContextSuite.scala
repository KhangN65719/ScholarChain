package scholarchain.rag

import scholarchain.storage.*

class SemanticContextSuite extends munit.FunSuite:
  import RagFixtures.*

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private lazy val env =
    val e = new Env()
    e.run()
    e

  private lazy val hits: Vector[RetrievedHit] =
    env.retrieval.search(VectorIndexes.ResearcherProfile, "Ada Quillfeather", 3)

  private lazy val context: SemanticContext =
    env.retrieval.context("Ada Quillfeather", hits)

  private def withText(hit: RetrievedHit, text: String): RetrievedHit =
    hit.copy(text = hit.text.copy(text = text))

  private def jsonOf(c: SemanticContext): ujson.Value =
    ujson.read(c.toJson.render())

  test("a context carries the retrieved items and the metrics notice") {
    assertEquals(context.schema, "scholarchain.rag.semantic-context/1")
    assertEquals(context.embeddingModel, "hash-embed-v1")
    assertEquals(context.query, "Ada Quillfeather")
    assert(!context.deterministicMetricsIncluded)
    assert(context.metricsNotice.contains("not from this retrieval"))
    assert(context.metricsNotice.contains("graph or metric stores"))
    assertEquals(context.items.size, 3)
    val first = context.items.head
    assertEquals(first.index, "researcher-profile-v1")
    assertEquals(first.documentType, "researcher-profile")
    assertEquals(first.subjectId, ada)
    assertEquals(first.evidenceNodeIds, Vector(ada))
    assert(first.evidenceWorkIds.nonEmpty)
    assert(first.window.isDefined && first.windowStart.isDefined)
    assert(first.textRef.startsWith("semantic-context/documents/"))
    assertEquals(first.templateVersion, "rag-template/researcher-profile/1")
    assertEquals(first.textHash.length, 64)
    assertEquals(first.snippet, hits.head.text.text)
    assert(!first.snippetTruncated)
    assertEquals(first.textChars, first.snippet.length)
    assert(first.distance >= 0.0)
  }

  test("JSON round trip") {
    val written = SemanticContext.write(context)
    assertEquals(SemanticContext.read(written), context)
    val empty = env.retrieval.context("nothing", Vector.empty)
    assertEquals(SemanticContext.read(SemanticContext.write(empty)), empty)
  }

  test("an absent window is null in the JSON and an option in the item") {
    val work = env.retrieval.search(
      VectorIndexes.PaperAbstract,
      "Protein folding with molecular dynamics",
      1
    )
    val c = env.retrieval.context("protein", work)
    assertEquals(c.items.head.windowStart, None)
    val item = jsonOf(c)("items")(0)
    assertEquals(item("windowStart"), ujson.Null)
    assertEquals(SemanticContext.read(SemanticContext.write(c)), c)
  }

  test("the JSON holds exactly the schema keys and no metric") {
    val json = jsonOf(context)
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
    assertEquals(
      json("items")(0).obj.keySet.toSet,
      Set(
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
    )
    assertEquals(json("deterministicMetricsIncluded"), ujson.False)
  }

  test("more than the item cap is refused, not cut") {
    val many = Vector.fill(SemanticContext.MaxItems + 1)(hits.head)
    assertEquals(
      code(env.retrieval.context("q", many)),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(
        env.retrieval
          .search(
            VectorIndexes.ResearcherProfile,
            "Ada",
            SemanticContext.MaxItems + 1
          )
      ),
      ErrorCode.BoundExceeded
    )
    val atCap = Vector.fill(SemanticContext.MaxItems)(
      hits.head.copy(text = hits.head.text)
    )
    assertEquals(
      env.retrieval.context("q", atCap).items.size,
      SemanticContext.MaxItems
    )
  }

  test("a long text is cut at a word boundary and marked, never silently") {
    val long = "alpha beta gamma delta " * 200
    val item =
      env.retrieval.context("q", Vector(withText(hits.head, long))).items.head
    assert(item.snippetTruncated)
    assertEquals(item.textChars, long.length)
    assert(item.snippet.length <= SemanticContext.MaxSnippetChars)
    assert(long.startsWith(item.snippet))
    // 1500 mod 23 = 5: index 1500 is the space after "alpha", so the first
    // 1500 characters end on a whole word and that is the exact cut.
    assertEquals(long.charAt(SemanticContext.MaxSnippetChars), ' ')
    assertEquals(item.snippet, long.take(SemanticContext.MaxSnippetChars))
    assert(item.snippet.endsWith("alpha"))
    assertEquals(long.charAt(item.snippet.length), ' ')
    val exact = "x" * SemanticContext.MaxSnippetChars
    val whole =
      env.retrieval.context("q", Vector(withText(hits.head, exact))).items.head
    assert(!whole.snippetTruncated)
    assertEquals(whole.snippet, exact)
  }

  private def snippetBytes(c: SemanticContext): Int =
    c.items.map(_.snippet.getBytes("UTF-8").length).sum

  test("long documents at the maximum item count fit: cut and marked") {
    val big = "alpha beta gamma delta " * 200
    val items = Vector.fill(SemanticContext.MaxItems)(withText(hits.head, big))
    val c = env.retrieval.context("q", items)
    assertEquals(c.items.size, SemanticContext.MaxItems)
    assert(snippetBytes(c) <= SemanticContext.MaxTotalSnippetBytes)
    c.items.foreach { i =>
      assert(i.snippetTruncated)
      assertEquals(i.textChars, big.length)
      assert(big.startsWith(i.snippet))
    }
    assertEquals(SemanticContext.read(SemanticContext.write(c)), c)
    // Non-ASCII text hits the byte share first, still within the cap.
    Vector("数据 " * 900, "é" * 3000, "😀 " * 1000).foreach { text =>
      val wide =
        Vector.fill(SemanticContext.MaxItems)(withText(hits.head, text))
      val w = env.retrieval.context("q", wide)
      assert(snippetBytes(w) <= SemanticContext.MaxTotalSnippetBytes)
      assert(w.items.forall(_.snippetTruncated))
    }
    // Fewer items get a bigger share: one long document keeps 1500 characters.
    val one = env.retrieval.context("q", Vector(withText(hits.head, big)))
    assert(one.items.head.snippet.length > 1400)
    assert(one.items.head.snippet.length <= SemanticContext.MaxSnippetChars)
    assertEquals(
      SemanticContext.snippetBytesFor(SemanticContext.MaxItems),
      SemanticContext.MaxTotalSnippetBytes / SemanticContext.MaxItems
    )
  }

  test("the total snippet bytes cap still refuses items built another way") {
    val base = context.items.head
    val text = "é" * SemanticContext.MaxSnippetChars
    val fat = base.copy(
      snippet = text,
      snippetTruncated = false,
      textChars = text.length
    )
    def build(n: Int) =
      SemanticContext(
        SemanticContext.Schema,
        "hash-embed-v1",
        "q",
        deterministicMetricsIncluded = false,
        SemanticContext.MetricsNotice,
        Vector.fill(n)(fat)
      )
    assertEquals(build(8).items.size, 8)
    assertEquals(code(build(9)), ErrorCode.BoundExceeded)
    val json = jsonOf(build(8))
    json("items") = ujson.Arr.from(Vector.fill(9)(json("items")(0)))
    assertEquals(
      code(SemanticContext.read(ujson.write(json))),
      ErrorCode.BoundExceeded
    )
  }

  test("a snippet cut never splits a surrogate pair") {
    val emoji = "😀"
    val odd = "a" + emoji * 1000
    val (snippet, truncated) = SemanticContext.snippetOf(odd)
    assert(truncated)
    assertEquals(snippet.length, SemanticContext.MaxSnippetChars - 1)
    assert(!Character.isHighSurrogate(snippet.last))
    assert(odd.startsWith(snippet))
    val item =
      env.retrieval.context("q", Vector(withText(hits.head, odd))).items.head
    assertEquals(item.snippet, snippet)
    assert(item.snippetTruncated)
    assertEquals(item.textChars, odd.length)
    assertEquals(
      SemanticContext
        .read(
          SemanticContext.write(
            env.retrieval.context("q", Vector(withText(hits.head, odd)))
          )
        )
        .items
        .head,
      item
    )
    // The byte share can end between pairs too: 1228 bytes is 307 emoji.
    val (shared, cut) = SemanticContext.snippetOf(emoji * 1000, 1228)
    assert(cut)
    assertEquals(shared, emoji * 307)
    // An odd byte budget never leaves half a character.
    val (three, _) = SemanticContext.snippetOf("数" * 1000, 1000)
    assertEquals(three, "数" * 333)
  }

  test("no whitespace in the leading text: cut on a code point, marked") {
    val solid = "x" * 4000
    val item =
      env.retrieval.context("q", Vector(withText(hits.head, solid))).items.head
    assert(item.snippetTruncated)
    assertEquals(item.snippet, "x" * SemanticContext.MaxSnippetChars)
    assertEquals(item.textChars, 4000)
    // The text ends right after the word that fills the limit: still a cut.
    val (s, t) = SemanticContext.snippetOf(("y" * 1499) + " tail")
    assert(t)
    assertEquals(s, "y" * 1499)
  }

  test("a query over the cap is refused") {
    assertEquals(
      code(env.retrieval.context("q" * 501, hits)),
      ErrorCode.BoundExceeded
    )
    env.retrieval.context("q" * 500, hits)
  }

  private def tampered(change: ujson.Value => Unit): String =
    val json = jsonOf(context)
    change(json)
    ujson.write(json)

  test("reading refuses an unknown key, a missing key and a metric field") {
    assertEquals(
      code(SemanticContext.read(tampered(_("publicationCount") = 5))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(SemanticContext.read(tampered(_("items")(0)("bridgeScore") = 0.5))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(SemanticContext.read(tampered(_.obj.remove("query")))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(
        SemanticContext.read(tampered(_("items")(0).obj.remove("textHash")))
      ),
      ErrorCode.MalformedRow
    )
  }

  test("reading refuses a context that does not disclaim metrics") {
    assertEquals(
      code(
        SemanticContext.read(tampered(_("deterministicMetricsIncluded") = true))
      ),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(SemanticContext.read(tampered(_("metricsNotice") = "fine"))),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(SemanticContext.read(tampered(_("schema") = "other/2"))),
      ErrorCode.MalformedRow
    )
  }

  test("reading refuses oversize input and bad shapes") {
    val longSnippet = "w " * 800
    assertEquals(
      code(
        SemanticContext.read(tampered { json =>
          json("items")(0)("snippet") = longSnippet
          json("items")(0)("snippetTruncated") = true
          json("items")(0)("textChars") = 5000
        })
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(
        SemanticContext.read(tampered { json =>
          val item = json("items")(0)
          json("items") = ujson.Arr.from(Vector.fill(21)(item))
        })
      ),
      ErrorCode.BoundExceeded
    )
    assertEquals(
      code(
        SemanticContext.read(tampered(_("items")(0)("snippetTruncated") = true))
      ),
      ErrorCode.MalformedRow
    )
    assertEquals(
      code(SemanticContext.read(tampered(_("items")(0)("textChars") = 3))),
      ErrorCode.MalformedRow
    )
    assertEquals(code(SemanticContext.read("not json")), ErrorCode.MalformedRow)
    assertEquals(code(SemanticContext.read("[1]")), ErrorCode.MalformedRow)
    assertEquals(
      code(SemanticContext.read(tampered(_("items")(0)("distance") = "near"))),
      ErrorCode.MalformedRow
    )
  }

  test("an item with a long evidence list or a blank field is refused") {
    val item = context.items.head
    assertEquals(
      code(item.copy(evidenceWorkIds = Vector.fill(6)(w1))),
      ErrorCode.BoundExceeded
    )
    assertEquals(code(item.copy(subjectId = " ")), ErrorCode.MalformedRow)
    assertEquals(code(item.copy(textHash = "abc")), ErrorCode.MalformedRow)
    assertEquals(
      code(item.copy(distance = Double.NaN)),
      ErrorCode.MalformedRow
    )
  }
