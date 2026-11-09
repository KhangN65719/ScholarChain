package scholarchain.services

import munit.FunSuite
import scholarchain.core.GraphEdgeType

class GraphRequestSuite extends FunSuite:
  private val codec = new CursorCodec("a-test-secret-value".getBytes("UTF-8"))
  private val binding = "researcher:v1:x|1|2000-2009|*"

  private def rejected(thunk: => Any): Unit =
    val e = intercept[ApiException](thunk)
    assertEquals(e.code.status, 400)

  test("a cursor round-trips its offset") {
    assertEquals(codec.verify(codec.issue(binding, 7), binding), 7)
  }

  test("changing any character of a cursor is rejected") {
    val cursor = codec.issue(binding, 7)
    cursor.indices.foreach { i =>
      val other = if cursor(i) == 'A' then 'B' else 'A'
      rejected(codec.verify(cursor.updated(i, other), binding))
    }
  }

  test("a cursor is bound to the request it was issued for") {
    val cursor = codec.issue(binding, 3)
    rejected(codec.verify(cursor, "researcher:v1:y|1|2000-2009|*"))
    rejected(codec.verify(cursor, "researcher:v1:x|2|2000-2009|*"))
    rejected(codec.verify(cursor, "researcher:v1:x|1|2010-2019|*"))
    rejected(
      codec.verify(cursor, "researcher:v1:x|1|2000-2009|COAUTHORED_WITH")
    )
  }

  test("a cursor signed with another secret is rejected") {
    val other = new CursorCodec("another-secret".getBytes("UTF-8"))
    rejected(codec.verify(other.issue(binding, 1), binding))
  }

  test("truncated, extended and garbage cursors are rejected") {
    val c = codec.issue(binding, 1)
    Vector(
      c.dropRight(2),
      c + "AA",
      c + c,
      "",
      ".",
      "zz.zz",
      "AAAA",
      c.replace(".", "")
    )
      .foreach(v => rejected(codec.verify(v, binding)))
  }

  test("a cursor holds no secret text") {
    val c = codec.issue(binding, 1)
    assert(!c.contains("a-test-secret-value"))
    assertEquals(c, c.toUpperCase)
  }

  test("graph windows are all or a canonical year range") {
    assert(GraphWindow.valid("all"))
    assert(GraphWindow.valid("2000-2009"))
    assert(GraphWindow.valid("1-10"))
    Vector(
      "",
      "1-9999",
      "2009-2000",
      "0-5",
      "2000",
      "2000-20099",
      "a-b",
      "02000-2009"
    )
      .foreach(w => assert(!GraphWindow.valid(w), w))
  }

  test("neighbor parameters are typed and strict") {
    val q = NeighborQuery.parse(
      Map(
        "depth" -> "2",
        "limit" -> "5",
        "edgeTypes" -> "COAUTHORED_WITH,AUTHORED",
        "window" -> "2000-2009"
      )
    )
    assertEquals(q.depth, Some(2))
    assertEquals(q.limit, Some(5))
    assertEquals(
      q.edgeTypes,
      Some(Vector(GraphEdgeType.CoauthoredWith, GraphEdgeType.Authored))
    )
    assertEquals(NeighborQuery.parse(Map.empty).depth, None)
    assertEquals(
      NeighborQuery.parse(Map("limit" -> "2147483647")).limit,
      Some(Int.MaxValue)
    )
  }

  test("bad neighbor parameters are 400") {
    Vector(
      Map("depth" -> "0"),
      Map("depth" -> "-1"),
      Map("depth" -> "abc"),
      Map("depth" -> ""),
      Map("depth" -> "1.5"),
      Map("limit" -> "99999999999999999999"),
      Map("edgeTypes" -> "NOT_AN_EDGE"),
      Map("edgeTypes" -> "coauthored_with"),
      Map("edgeTypes" -> ""),
      Map("cursor" -> ""),
      Map("window" -> "nope"),
      Map("unknown" -> "1")
    ).foreach(m => rejected(NeighborQuery.parse(m)))
  }
