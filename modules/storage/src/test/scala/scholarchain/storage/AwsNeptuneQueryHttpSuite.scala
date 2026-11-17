package scholarchain.storage

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration.ofMillis
import java.util.concurrent.Executors
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region

/** The real SDK client against a fake HTTP endpoint on the loopback address:
  * the request shape and the SigV4 headers on the wire, the answer parsing and
  * the deadlines. No AWS service is contacted.
  */
class AwsNeptuneQueryHttpSuite extends munit.FunSuite:
  import Fixtures.*

  private val window = "2000-2009"
  private val accessKey = "AKIATESTONLYKEY0000"
  private val secretKey = "test-only-secret-key-never-sent-0000000000"

  private final case class Seen(
      method: String,
      path: String,
      headers: Map[String, String],
      body: String
  )

  private final case class Reply(
      status: Int,
      body: String,
      headers: Map[String, String] = Map.empty,
      delayMillis: Long = 0L
  )

  private def withServer[A](
      reply: Seen => Reply
  )(use: (URI, mutable.ArrayBuffer[Seen]) => A): A =
    val seen = mutable.ArrayBuffer.empty[Seen]
    val server =
      HttpServer.create(
        new InetSocketAddress(InetAddress.getLoopbackAddress, 0),
        0
      )
    val pool = Executors.newFixedThreadPool(4)
    server.setExecutor(pool)
    server.createContext(
      "/",
      (exchange: HttpExchange) =>
        try
          val headers = exchange.getRequestHeaders.asScala.toMap.map {
            case (name, values) =>
              name.toLowerCase -> values.asScala.mkString(",")
          }
          val body = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
          val request = Seen(
            exchange.getRequestMethod,
            exchange.getRequestURI.getPath,
            headers,
            body
          )
          seen.synchronized { seen += request }
          val out = reply(request)
          if out.delayMillis > 0 then Thread.sleep(out.delayMillis)
          out.headers.foreach { case (k, v) =>
            exchange.getResponseHeaders.add(k, v)
          }
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          val bytes = out.body.getBytes(UTF_8)
          exchange.sendResponseHeaders(out.status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        catch case _: java.io.IOException => ()
        finally exchange.close()
    )
    server.start()
    try
      use(
        URI.create(s"http://127.0.0.1:${server.getAddress.getPort}"),
        seen
      )
    finally
      server.stop(0)
      pool.shutdownNow()

  private def adapter(
      endpoint: URI,
      timeouts: NeptuneQueryTimeouts = NeptuneQueryTimeouts.Default,
      log: String => Unit = _ => ()
  ): AwsNeptuneQuery =
    AwsNeptuneQuery.open(
      Region.US_EAST_2,
      StaticCredentialsProvider.create(
        AwsBasicCredentials.create(accessKey, secretKey)
      ),
      endpoint,
      timeouts,
      log
    )

  private def resultsBody(edge: String, other: String): String =
    ujson.write(
      ujson.Obj(
        "results" -> ujson.Arr(
          ujson.Obj(
            "coauthorId" -> other,
            "edgeId" -> edge,
            "jointPublicationCount" -> 2,
            "firstCollaborationYear" -> 2001,
            "lastCollaborationYear" -> 2005,
            "evidencePointer" -> s"edge-support:edgeId=$edge"
          )
        )
      )
    )

  /** The request body's fields, whatever the SDK names the query field. The
    * `parameters` member is a JSON string or an object.
    */
  private def bodyParts(body: String): (Vector[String], ujson.Value) =
    val json = ujson.read(body).obj
    val parameters = json.get("parameters") match
      case Some(ujson.Str(text)) => ujson.read(text)
      case Some(other)           => other
      case None                  => fail(s"no parameters member in $body")
    val texts = json.toVector.collect {
      case (name, ujson.Str(value)) if name != "parameters" => value
    }
    (texts, parameters)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  test("the request is a signed POST to /opencypher with the values apart") {
    val edge = edgeId(r1, r2)
    withServer(_ => Reply(200, resultsBody(edge, r2))) { (endpoint, seen) =>
      val neptune = adapter(endpoint)
      try
        val rows = neptune.run(CypherCatalog.coauthorsOf(r1, window, 25))
        assertEquals(rows.size, 1)
        assertEquals(Rows.str(rows.head, "coauthorId"), r2)
        assertEquals(Rows.long(rows.head, "jointPublicationCount"), 2L)
      finally neptune.close()
      assertEquals(seen.size, 1)
      val request = seen.head
      assertEquals(request.method, "POST")
      assertEquals(request.path.toLowerCase, "/opencypher")
      val authorization = request.headers.getOrElse("authorization", "")
      assert(
        authorization.startsWith("AWS4-HMAC-SHA256 Credential="),
        authorization
      )
      assert(authorization.contains(accessKey + "/"), authorization)
      assert(
        authorization.contains("/us-east-2/neptune-db/aws4_request"),
        authorization
      )
      assert(authorization.contains("SignedHeaders="), authorization)
      assert(authorization.contains("Signature="), authorization)
      assert(
        request.headers.contains("x-amz-date"),
        request.headers.keySet.toString
      )
      assert(request.headers.contains("host"))
      val serialized = request.headers.values.mkString(" ") + request.body
      assert(!serialized.contains(secretKey))
      val (texts, parameters) = bodyParts(request.body)
      assert(texts.contains(CypherCatalog.coauthorsOf(r1, window, 25).text))
      texts.foreach { text =>
        assert(!text.contains(r1), text)
        assert(!text.contains(window), text)
      }
      assertEquals(parameters("researcherId").str, r1)
      assertEquals(parameters("window").str, window)
      assertEquals(parameters("limit").num, 25.0)
    }
  }

  test("an empty answer is no rows, and edge-evidence parses end to end") {
    val edge = edgeId(r1, r2)
    val body = ujson.write(
      ujson.Obj(
        "results" -> ujson.Arr(
          ujson.Obj(
            "edgeId" -> edge,
            "evidencePointer" -> s"edge-support:edgeId=$edge",
            "representativeWorkIds" -> s"$w1;$w2"
          )
        )
      )
    )
    withServer(request =>
      if request.body.contains("representativeWorkIds") then Reply(200, body)
      else Reply(200, """{"results":[]}""")
    ) { (endpoint, _) =>
      val neptune = adapter(endpoint)
      try
        val rows = neptune.run(CypherCatalog.edgeEvidence(edge, 1))
        assertEquals(
          Rows.strList(rows.head, "representativeWorkIds"),
          Vector(w1, w2)
        )
        assertEquals(
          neptune.run(CypherCatalog.coauthorsOf(r1, window, 5)),
          Vector.empty
        )
      finally neptune.close()
    }
  }

  test("access denied (403) and a bad query (400) are mapped") {
    withServer { request =>
      if request.body.contains("coauthor") then
        Reply(
          403,
          """{"code":"AccessDeniedException","detailedMessage":"no"}""",
          Map("x-amzn-ErrorType" -> "AccessDeniedException")
        )
      else
        Reply(
          400,
          """{"code":"MalformedQueryException","detailedMessage":"bad"}""",
          Map("x-amzn-ErrorType" -> "MalformedQueryException")
        )
    } { (endpoint, _) =>
      val neptune = adapter(endpoint)
      try
        assertEquals(
          code(neptune.run(CypherCatalog.coauthorsOf(r1, window, 5))),
          ErrorCode.AwsAccessDenied
        )
        assertEquals(
          code(neptune.run(CypherCatalog.edgeEvidence("e1", 1))),
          ErrorCode.QueryRejected
        )
      finally neptune.close()
    }
  }

  test("throttling (429) is transient and a 503 is not retried") {
    withServer(_ =>
      Reply(
        429,
        """{"code":"ThrottlingException","detailedMessage":"slow"}""",
        Map("x-amzn-ErrorType" -> "ThrottlingException")
      )
    ) { (endpoint, seen) =>
      val neptune = adapter(endpoint)
      try
        val e = intercept[TransientStorageException](
          neptune.run(CypherCatalog.coauthorsOf(r1, window, 5))
        )
        assert(e.getMessage.startsWith("NEPTUNE_THROTTLED"), e.getMessage)
      finally neptune.close()
      assertEquals(seen.size, 1, "the SDK retried a throttled call")
    }
    withServer(_ => Reply(503, """{"message":"down"}""")) { (endpoint, seen) =>
      val neptune = adapter(endpoint)
      try
        intercept[TransientStorageException](
          neptune.run(CypherCatalog.coauthorsOf(r1, window, 5))
        )
      finally neptune.close()
      assertEquals(seen.size, 1, "the SDK retried a 503")
    }
  }

  test("a slow endpoint ends inside the client deadline as a timeout") {
    withServer(_ => Reply(200, """{"results":[]}""", delayMillis = 4000L)) {
      (endpoint, _) =>
        val neptune = adapter(
          endpoint,
          NeptuneQueryTimeouts(
            ofMillis(300),
            ofMillis(300),
            ofMillis(500),
            ofMillis(700)
          )
        )
        try
          val began = System.nanoTime()
          val e = intercept[TransientStorageException](
            neptune.run(CypherCatalog.coauthorsOf(r1, window, 5))
          )
          val elapsedMillis = (System.nanoTime() - began) / 1000000L
          assert(e.getMessage.startsWith("NEPTUNE_TIMEOUT"), e.getMessage)
          assert(elapsedMillis < 3000L, s"took $elapsedMillis ms")
        finally neptune.close()
    }
  }

  test("a closed port is a transient failure, not a hang") {
    val closed = withServer(_ => Reply(200, "{}"))((endpoint, _) => endpoint)
    val neptune = adapter(
      closed,
      NeptuneQueryTimeouts(
        ofMillis(300),
        ofMillis(300),
        ofMillis(500),
        ofMillis(700)
      )
    )
    try
      val e = intercept[TransientStorageException](
        neptune.run(CypherCatalog.coauthorsOf(r1, window, 5))
      )
      assert(e.getMessage.startsWith("NEPTUNE_TIMEOUT"), e.getMessage)
    finally neptune.close()
  }

  test("the log line names the template and counts, never a value") {
    val edge = edgeId(r1, r2)
    val lines = mutable.ArrayBuffer.empty[String]
    withServer(_ => Reply(200, resultsBody(edge, r2))) { (endpoint, _) =>
      val neptune = adapter(endpoint, log = lines += _)
      try neptune.run(CypherCatalog.coauthorsOf(r1, window, 25))
      finally neptune.close()
    }
    assertEquals(lines.size, 1)
    assert(lines.head.startsWith("NEPTUNE_QUERY name=coauthors-of rows=1 ms="))
    assert(!lines.head.contains(r1))
  }
