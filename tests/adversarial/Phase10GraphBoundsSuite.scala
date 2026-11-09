package scholarchain.tests.adversarial

/** The configured graph bounds, proved with tight overrides instead of the
  * generous defaults: a request above the maximum must be cut to the maximum,
  * which a default-sized fixture graph cannot show. Added in pass B after the
  * vacuity review found the Phase10GraphSuite upper-bound assertions too loose.
  */
class Phase10GraphBoundsSuite extends P10Suite:
  private val listKeys =
    Vector("neighbors", "items", "edges", "nodes", "results")
  private val idKeys = Vector("neighborId", "nodeId", "id", "toId")

  private def neighborIds(v: ujson.Value): Vector[String] =
    val list = listKeys
      .flatMap(k => Json.objects(v).flatMap(_.value.get(k)))
      .collectFirst { case a: ujson.Arr => a.value.toVector }
      .getOrElse(Vector.empty)
    list.flatMap {
      case ujson.Str(s) => Vector(s)
      case o: ujson.Obj =>
        idKeys
          .flatMap(o.value.get)
          .collectFirst { case ujson.Str(s) => s }
          .toVector
      case _ => Vector.empty
    }

  private def total(v: ujson.Value): Int =
    Json
      .firstValue(v, "total")
      .collect { case ujson.Num(d) => d.toInt }
      .getOrElse(0)

  private def path(id: String, query: String): String =
    s"/v1/graph/nodes/$id/neighbors$query"

  private def answerNodes(a: Api): Vector[String] =
    (Questions.candidates ++ Questions.coauthors)
      .map(q => a.ask(q))
      .filter(_.status == 200)
      .flatMap(r => Json.researcherIds(r.body))
      .distinct

  /** A node whose neighbor list holds at least two entries in this runtime. */
  private def busyNode(a: Api): String =
    answerNodes(a)
      .find(id => total(a.get(path(id, "?limit=1")).json) >= 2)
      .getOrElse(fail("no fixture node has two neighbors"))

  test("limit above graph.maxLimit is cut to the maximum, with a cursor") {
    val tight =
      launch(Map("graph.maxLimit" -> "1", "graph.defaultLimit" -> "1"))
    val id = busyNode(tight)
    val huge = tight.get(path(id, "?limit=99999999999999999999"))
    assertTypedError(huge, 400, tight, "limit beyond 18 digits")
    Vector("100", "100000", "2147483647").foreach { l =>
      val r = tight.get(path(id, s"?limit=$l"))
      assertEquals(r.status, 200, s"limit=$l: ${r.body}")
      assertEquals(neighborIds(r.json).size, 1, s"limit=$l: ${r.body}")
      assert(
        Json.firstValue(r.json, "nextCursor").exists(_.strOpt.isDefined),
        s"limit=$l: more neighbors exist but no cursor: ${r.body}"
      )
    }
  }

  test("with no limit the page is the configured default, not everything") {
    val tight =
      launch(Map("graph.maxLimit" -> "1", "graph.defaultLimit" -> "1"))
    val id = busyNode(tight)
    val r = tight.get(path(id, ""))
    assertEquals(r.status, 200, r.body)
    assertEquals(neighborIds(r.json).size, 1, r.body)
  }

  test("depth above graph.maxDepth is cut to the maximum") {
    val deep = answerNodes(api)
      .find(id =>
        total(api.get(path(id, "?depth=2&limit=50")).json) >
          total(api.get(path(id, "?depth=1&limit=50")).json)
      )
      .getOrElse(
        fail("control: no fixture node reaches further at depth 2 than depth 1")
      )
    val control = total(api.get(path(deep, "?depth=2&limit=50")).json)
    val shallow = launch(Map("graph.maxDepth" -> "1"))
    val one = shallow.get(path(deep, "?depth=1&limit=50"))
    assertEquals(one.status, 200, one.body)
    assert(total(one.json) < control, s"control: ${one.body}")
    Vector("2", "4", "1000", "2147483647").foreach { d =>
      val r = shallow.get(path(deep, s"?depth=$d&limit=50"))
      assertEquals(r.status, 200, s"depth=$d: ${r.body}")
      assertEquals(
        neighborIds(r.json),
        neighborIds(one.json),
        s"depth=$d reached beyond the configured maximum"
      )
      assertEquals(total(r.json), total(one.json), s"depth=$d")
    }
  }

  test("a cursor walk under a tight limit visits every neighbor once") {
    val tight =
      launch(Map("graph.maxLimit" -> "1", "graph.defaultLimit" -> "1"))
    val id = busyNode(tight)
    val first = tight.get(path(id, "?limit=1"))
    val expected = total(first.json)
    assert(expected >= 2, first.body)
    val seen =
      scala.collection.mutable.ArrayBuffer.from(neighborIds(first.json))
    var cursor =
      Json.firstValue(first.json, "nextCursor").flatMap(_.strOpt)
    var pages = 1
    while cursor.isDefined && pages < 200 do
      val r = tight.get(path(id, s"?limit=1&cursor=${tight.enc(cursor.get)}"))
      assertEquals(r.status, 200, r.body)
      assertEquals(neighborIds(r.json).size, 1, r.body)
      seen ++= neighborIds(r.json)
      cursor = Json.firstValue(r.json, "nextCursor").flatMap(_.strOpt)
      pages += 1
    assertEquals(cursor, None, "the cursor chain never ended")
    assertEquals(seen.size, expected)
    assertEquals(seen.toSet.size, seen.size, "a neighbor repeated")
  }
