package scholarchain.tests.adversarial

import java.net.{InetAddress, ServerSocket}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration
import scala.util.{Failure, Success, Try}

/** Proves the classifier behind `assertOversizedRefused` (work order 13, the m1
  * race). The race itself, a server that refuses early and closes while the
  * client library is still writing, depends on timing, so here it is forced
  * with a fake server: it must be recognised, and a timeout, a refused
  * connection and a non-IO error must not be.
  */
class Phase10EarlyCloseSuite extends P10Suite:
  private def loopback = InetAddress.getLoopbackAddress

  /** Accepts one connection, reads the head, then resets the connection while
    * the client is still writing its body.
    */
  private def resettingServer(): (ServerSocket, Thread) =
    val server = new ServerSocket(0, 1, loopback)
    val t = new Thread(
      new Runnable:
        def run(): Unit =
          Try {
            val s = server.accept()
            s.setSoLinger(true, 0)
            val in = s.getInputStream
            val buf = new Array[Byte](512)
            in.read(buf)
            s.close()
          }
          ()
    )
    t.setDaemon(true)
    t.start()
    (server, t)

  test("a connection reset while the 5 MiB body is written is an early close") {
    val (server, _) = resettingServer()
    try
      val big = "q" * (5 * 1024 * 1024)
      val r = Try(
        Http.send(
          "POST",
          s"http://${loopback.getHostAddress}:${server.getLocalPort}/v1/query",
          Some(big),
          Map("Content-Type" -> "application/json")
        )
      )
      r match
        case Failure(e) =>
          assert(isEarlyClose(e), s"not classified as an early close: $e")
        case Success(resp) =>
          fail(s"a reset connection produced a response: ${resp.status}")
    finally server.close()
  }

  test("a refused connection is not an early close") {
    val server = new ServerSocket(0, 1, loopback)
    val port = server.getLocalPort
    server.close()
    val r = Try(
      Http.send(
        "POST",
        s"http://${loopback.getHostAddress}:$port/v1/query",
        Some("{}"),
        Map.empty
      )
    )
    r match
      case Failure(e) => assert(!isEarlyClose(e), s"misclassified: $e")
      case Success(_) => fail("nothing listens on that port")
  }

  test("a request timeout is not an early close") {
    val server = new ServerSocket(0, 1, loopback)
    try
      val client = HttpClient.newHttpClient()
      val req = HttpRequest
        .newBuilder(
          java.net.URI.create(
            s"http://${loopback.getHostAddress}:${server.getLocalPort}/"
          )
        )
        .timeout(JDuration.ofMillis(400))
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build()
      val r = Try(client.send(req, HttpResponse.BodyHandlers.ofString()))
      r match
        case Failure(e) =>
          assert(
            e.isInstanceOf[java.net.http.HttpTimeoutException],
            s"expected a timeout, got $e"
          )
          assert(!isEarlyClose(e), s"a timeout was classified as a refusal: $e")
        case Success(_) => fail("a silent server answered")
    finally server.close()
  }

  test("a non-IO exception and a bare IOException are not early closes") {
    assert(!isEarlyClose(new IllegalStateException("Broken pipe")))
    assert(!isEarlyClose(new java.io.IOException("something else entirely")))
    assert(isEarlyClose(new java.io.IOException("Broken pipe")))
    assert(
      isEarlyClose(
        new java.io.IOException(
          "fixed content-length: 187, bytes received: 0",
          new java.io.IOException("Broken pipe")
        )
      )
    )
  }
