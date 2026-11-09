package scholarchain.tests.adversarial

/** Graph exploration bounds, cursoring, cursor tamper and cursor reuse. The
  * node IDs come from answers over the fixture world, not from typed IDs.
  * Written from spec.md; not executed.
  */
class Phase10GraphSuite extends P10Suite:
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

  private def nextCursor(v: ujson.Value): Option[String] =
    Json.firstValue(v, "nextCursor").collect {
      case ujson.Str(s) if s.nonEmpty => s
    }

  private def neighbors(path: String): Resp = api.get(path)

  private def nodeIds(): Vector[String] =
    (Questions.candidates ++ Questions.coauthors)
      .map(q => api.ask(q))
      .filter(_.status == 200)
      .flatMap(r => Json.researcherIds(r.body))
      .distinct

  /** A node with at least two neighbors in the fixture graph. */
  private lazy val busy: (String, Vector[String]) =
    nodeIds().iterator
      .map(id =>
        id -> neighborIds(
          neighbors(s"/v1/graph/nodes/$id/neighbors?limit=100").json
        )
      )
      .find(_._2.size >= 2)
      .getOrElse(
        fail("no fixture node has two neighbors (looked at answer node IDs)")
      )

  private def path(id: String, query: String = ""): String =
    s"/v1/graph/nodes/${api.enc(id)}/neighbors$query"

  test("a known node is 200 with its ID; an unknown node is 404") {
    val (id, _) = busy
    val r = api.get(s"/v1/graph/nodes/${api.enc(id)}")
    assertEquals(r.status, 200, r.body)
    assertTraceHeaders(r, "node")
    assertEquals(Json.firstString(r.json, "nodeId"), Some(id))
    assertTypedError(
      api.get("/v1/graph/nodes/researcher:v1:" + ("0" * 64)),
      404,
      api,
      "unknown node"
    )
    assertTypedError(
      api.get(path("researcher:v1:" + ("0" * 64))),
      404,
      api,
      "unknown node neighbors"
    )
  }

  test("neighbors default to depth 1 and return a bounded list") {
    val (id, all) = busy
    val r = api.get(path(id))
    assertEquals(r.status, 200, r.body)
    val ids = neighborIds(r.json)
    assert(ids.nonEmpty && ids.size <= all.size, ids.toString)
    assert(!ids.contains(id), "a node is not its own neighbor")
    assertEquals(ids.distinct, ids)
  }

  test("limit bounds the page and nextCursor walks the rest without gaps") {
    val (id, all) = busy
    val first = api.get(path(id, "?limit=1"))
    assertEquals(first.status, 200, first.body)
    val page1 = neighborIds(first.json)
    assertEquals(page1.size, 1)
    val seen = scala.collection.mutable.ArrayBuffer.from(page1)
    var cursor = nextCursor(first.json)
    assert(
      cursor.isDefined,
      s"more neighbors exist but no cursor: ${first.body}"
    )
    var pages = 1
    while cursor.isDefined && pages < 50 do
      val r = api.get(path(id, s"?limit=1&cursor=${api.enc(cursor.get)}"))
      assertEquals(r.status, 200, r.body)
      val ids = neighborIds(r.json)
      assert(ids.size <= 1)
      seen ++= ids
      cursor = nextCursor(r.json)
      pages += 1
    assertEquals(cursor, None, "the cursor chain never ended")
    assertEquals(seen.toVector.sorted, all.sorted)
    assertEquals(seen.toSet.size, seen.size, "a neighbor repeated across pages")
  }

  test("the same cursor can be replayed and returns the same page") {
    val (id, _) = busy
    val first = api.get(path(id, "?limit=1"))
    val c = nextCursor(first.json).getOrElse(fail("no cursor"))
    val a = api.get(path(id, s"?limit=1&cursor=${api.enc(c)}"))
    val b = api.get(path(id, s"?limit=1&cursor=${api.enc(c)}"))
    assertEquals(a.status, 200)
    assertEquals(neighborIds(b.json), neighborIds(a.json))
  }

  test("bad depth, limit and edgeTypes values are 400") {
    val (id, _) = busy
    val bad = Vector(
      "?depth=0",
      "?depth=-1",
      "?depth=abc",
      "?depth=",
      "?depth=1.5",
      "?limit=0",
      "?limit=-5",
      "?limit=abc",
      "?limit=",
      "?edgeTypes=NOT_AN_EDGE",
      "?edgeTypes=COAUTHORED_WITH,NOT_AN_EDGE",
      "?edgeTypes=coauthored_with",
      "?edgeTypes=",
      "?edgeTypes=" + api.enc("COAUTHORED_WITH]-() DETACH DELETE n //"),
      "?edgeTypes=" + api.enc("' OR 1=1 --")
    )
    bad.foreach { q =>
      assertTypedError(api.get(path(id, q)), 400, api, q)
    }
  }

  test("depth and limit above the configured maximum are bounded, not 5xx") {
    val (id, all) = busy
    Vector("?depth=1000", "?depth=2147483647", "?depth=99999999999999999999")
      .foreach { q =>
        val r = api.get(path(id, q))
        assert(Set(200, 400, 422).contains(r.status), s"$q: ${r.status}")
        if r.status == 200 then assert(neighborIds(r.json).size <= 1000, q)
        else assertTypedError(r, r.status, api, q)
      }
    Vector("?limit=100000", "?limit=2147483647", "?limit=99999999999999999999")
      .foreach { q =>
        val r = api.get(path(id, q))
        assert(Set(200, 400, 422).contains(r.status), s"$q: ${r.status}")
        if r.status == 200 then assert(neighborIds(r.json).size <= all.size, q)
        else assertTypedError(r, r.status, api, q)
      }
  }

  test("valid edgeTypes filter the result; an unrelated type matches no more") {
    val (id, all) = busy
    val co = api.get(path(id, "?edgeTypes=COAUTHORED_WITH"))
    assertEquals(co.status, 200, co.body)
    assertEquals(neighborIds(co.json).sorted, all.sorted)
    val other = api.get(path(id, "?edgeTypes=AUTHORED"))
    assertEquals(other.status, 200, other.body)
    assert(neighborIds(other.json).forall(all.contains))
    assert(neighborIds(other.json).size <= all.size)
    val both = api.get(path(id, "?edgeTypes=COAUTHORED_WITH,AUTHORED"))
    assertEquals(both.status, 200, both.body)
  }

  test("a tampered cursor is rejected with a typed 400") {
    val (id, _) = busy
    val first = api.get(path(id, "?limit=1"))
    val c = nextCursor(first.json).getOrElse(fail("no cursor"))
    def swap(s: String, at: Int): String =
      val repl = if s(at) == 'A' then 'B' else 'A'
      s.updated(at, repl)
    val tampered = Vector(
      "flip first" -> swap(c, 0),
      "flip middle" -> swap(c, c.length / 2),
      "flip last" -> swap(c, c.length - 1),
      "truncated" -> c.dropRight(4),
      "extended" -> (c + "AAAA"),
      "doubled" -> (c + c),
      "garbage" -> "AAAA",
      "not base64" -> "!!!not-a-cursor!!!",
      "json offset" -> java.util.Base64.getUrlEncoder.withoutPadding
        .encodeToString("{\"offset\":99}".getBytes("UTF-8")),
      "injection" -> "x\" OR 1=1 //"
    )
    tampered.foreach { (label, v) =>
      val r = api.get(path(id, s"?limit=1&cursor=${api.enc(v)}"))
      assertTypedError(r, 400, api, s"cursor $label")
    }
    assertTypedError(
      api.get(path(id, "?limit=1&cursor=")),
      400,
      api,
      "empty cursor"
    )
  }

  test("a cursor issued for one node is rejected on another node") {
    val ids = nodeIds()
    val withNeighbors = ids.filter(i =>
      neighborIds(api.get(path(i, "?limit=100")).json).size >= 2
    )
    val (id, _) = busy
    val c = nextCursor(api.get(path(id, "?limit=1")).json)
      .getOrElse(fail("no cursor"))
    val others = ids.filterNot(_ == id)
    assert(others.nonEmpty, "the fixture world has a single node")
    others.foreach { o =>
      val r = api.get(path(o, s"?limit=1&cursor=${api.enc(c)}"))
      assertTypedError(r, 400, api, s"cursor from $id on $o")
    }
    assert(withNeighbors.contains(id))
  }

  test("a cursor is bound to its filters: edgeTypes changes reject it") {
    val (id, _) = busy
    val c =
      nextCursor(api.get(path(id, "?limit=1&edgeTypes=COAUTHORED_WITH")).json)
        .getOrElse(fail("no cursor"))
    val reuse =
      api.get(path(id, s"?limit=1&edgeTypes=AUTHORED&cursor=${api.enc(c)}"))
    assertTypedError(reuse, 400, api, "cursor with other edgeTypes")
    val noFilter = api.get(path(id, s"?limit=1&cursor=${api.enc(c)}"))
    assertTypedError(noFilter, 400, api, "cursor with the filter dropped")
  }

  test("a cursor never carries a secret or the token") {
    val (id, _) = busy
    val c = nextCursor(api.get(path(id, "?limit=1")).json)
      .getOrElse(fail("no cursor"))
    assertEquals(leaks(c, tokensOf(api)), Vector.empty[String])
    val decoded = scala.util
      .Try(
        new String(java.util.Base64.getUrlDecoder.decode(c), "UTF-8")
      )
      .getOrElse("")
    assertEquals(leaks(decoded, tokensOf(api)), Vector.empty[String])
  }

  test(
    "injection-shaped node IDs are 404 or 400, never 5xx or reflected code"
  ) {
    Vector(
      "MATCH (n) DETACH DELETE n",
      "' OR 1=1 --",
      "../../etc/passwd",
      "researcher:v1:" + ("a" * 300),
      "%00",
      "${jndi:ldap://x}"
    ).foreach { raw =>
      val r = api.get(s"/v1/graph/nodes/${api.enc(raw)}")
      assert(r.status == 400 || r.status == 404, s"$raw: ${r.status}")
      assertTypedError(r, r.status, api, raw.take(20))
      val n = api.get(s"/v1/graph/nodes/${api.enc(raw)}/neighbors")
      assert(n.status == 400 || n.status == 404, s"$raw: ${n.status}")
    }
  }
