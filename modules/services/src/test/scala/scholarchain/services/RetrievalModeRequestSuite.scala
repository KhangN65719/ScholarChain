package scholarchain.services

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite
import scholarchain.rag.{DemoWorld, RetrievalMode, Session, WorkflowResult}

/** The optional `retrievalMode` request field, below the HTTP layer. */
class RetrievalModeRequestSuite extends FunSuite:
  private val config = ServiceConfig.from(
    Map("auth.queryToken" -> ("q" * 16), "auth.readToken" -> ("r" * 16))
  )

  private def body(fields: (String, ujson.Value)*): Array[Byte] =
    ujson
      .write(ujson.Obj.from(("question" -> ujson.Str("Who is Ada?")) +: fields))
      .getBytes(UTF_8)

  private def refused(fields: (String, ujson.Value)*): ApiException =
    intercept[ApiException](QueryRequests.parse(body(fields*), config))

  test("an absent retrievalMode parses to None and resolves to fused") {
    val parsed = QueryRequests.parse(body(), config)
    assertEquals(parsed.retrievalMode, None)
    val request = QueryRequests.resolve(parsed, false, config)
    assertEquals(request.retrievalMode, RetrievalMode.Fused)
  }

  test("a null retrievalMode is the same as an absent one") {
    val parsed =
      QueryRequests.parse(body("retrievalMode" -> ujson.Null), config)
    assertEquals(parsed.retrievalMode, None)
  }

  test("graph, vector and fused each parse and resolve to their mode") {
    Vector(
      "graph" -> RetrievalMode.Graph,
      "vector" -> RetrievalMode.Vector,
      "fused" -> RetrievalMode.Fused
    ).foreach { (wire, mode) =>
      val parsed =
        QueryRequests.parse(body("retrievalMode" -> ujson.Str(wire)), config)
      assertEquals(parsed.retrievalMode, Some(mode), wire)
      assertEquals(
        QueryRequests.resolve(parsed, false, config).retrievalMode,
        mode,
        wire
      )
    }
  }

  test("an unknown value is 400 INVALID_REQUEST and is not echoed") {
    Vector("hybrid", "Graph", "FUSED", "", " graph", "graph ").foreach { v =>
      val e = refused("retrievalMode" -> ujson.Str(v))
      assertEquals(e.code, ErrorCode.InvalidRequest, v)
      assertEquals(e.code.status, 400)
      assertEquals(
        e.getMessage,
        "retrievalMode must be graph, vector or fused",
        v
      )
    }
    assert(
      !refused("retrievalMode" -> ujson.Str("zz-secret-zz")).getMessage
        .contains("zz-secret-zz")
    )
  }

  test("a retrievalMode that is not a string is 400 INVALID_REQUEST") {
    Vector[ujson.Value](
      ujson.Num(1),
      ujson.Bool(true),
      ujson.Arr(),
      ujson.Obj()
    )
      .foreach { v =>
        assertEquals(
          refused("retrievalMode" -> v).code,
          ErrorCode.InvalidRequest
        )
      }
  }

  test("the wire names are the rag wire names") {
    assertEquals(
      Vector(RetrievalMode.Graph, RetrievalMode.Vector, RetrievalMode.Fused)
        .map(RetrievalModes.wire),
      Vector("graph", "vector", "fused")
    )
    assertEquals(RetrievalModes.Default, RetrievalMode.Fused)
  }

  test("a two-argument workflow port runs fused and refuses other modes") {
    val result: WorkflowResult =
      DemoWorld
        .build()
        .workflow
        .answer("Who is Ada Quillfeather?", Session("s", 0))
    val legacy = new WorkflowPort:
      def answer(question: String, session: Session): WorkflowResult = result
      def ready(): Unit = ()
    assertEquals(
      legacy.answer("q", Session("s", 1), RetrievalMode.Fused),
      result
    )
    intercept[UnsupportedOperationException](
      legacy.answer("q", Session("s", 1), RetrievalMode.Graph)
    )
    intercept[UnsupportedOperationException](
      legacy.answer("q", Session("s", 1), RetrievalMode.Vector)
    )
  }
