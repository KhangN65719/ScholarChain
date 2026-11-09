package scholarchain.tests.adversarial

import scala.util.{Failure, Success, Try}
import munit.FunSuite
import scholarchain.services.{CursorCodec, ServiceConfig}
import scholarchain.services.runtime.LocalRuntime

/** Phase 11 configuration omissions and bad values. Every required key that is
  * missing, and every key given a bad value, must give a clear typed error
  * (`IllegalArgumentException`) that names the key and never holds a secret
  * value: a token, a cursor secret or a bad value that was itself a secret.
  */
class Phase11ConfigSuite extends FunSuite:
  private val canary = "SECRETCANARY-p11-0123456789"
  private val queryToken = "p11-config-query-token-0123456789"
  private val readToken = "p11-config-read-token-9876543210"
  private val cursor = "p11-config-cursor-secret-abcdef012345"

  private val valid = Map(
    "auth.queryToken" -> queryToken,
    "auth.readToken" -> readToken,
    "auth.cursorSecret" -> cursor
  )

  private def failureOf(values: Map[String, String]): IllegalArgumentException =
    Try(ServiceConfig.from(values)) match
      case Failure(e: IllegalArgumentException) => e
      case Failure(other)                       =>
        fail(s"untyped failure ${other.getClass.getName}: ${other.getMessage}")
      case Success(_) => fail(s"accepted: ${values.keySet}")

  private def assertNoSecret(message: String, secrets: String*): Unit =
    (Vector(queryToken, readToken, cursor, canary) ++ secrets).foreach { s =>
      assert(!message.contains(s), s"'$s' appears in: $message")
    }

  test("the valid base configuration loads") {
    val config = ServiceConfig.from(valid)
    assertEquals(config.queryToken, queryToken)
    assertEquals(config.cursorSecret, Some(cursor))
  }

  test("each required key missing is a clear typed error naming that key") {
    val required = Vector("auth.queryToken", "auth.readToken")
    required.foreach { key =>
      val e = failureOf(valid - key)
      assertEquals(e.getMessage, s"Missing configuration: $key")
      assertNoSecret(e.getMessage)
    }
    val empty = failureOf(Map.empty)
    assert(
      empty.getMessage.startsWith("Missing configuration: "),
      empty.getMessage
    )
  }

  test("every key given a bad value is a typed error that echoes no value") {
    val rejected = scala.collection.mutable.ArrayBuffer.empty[String]
    ServiceConfig.Keys.foreach { key =>
      Try(ServiceConfig.from(valid.updated(key, canary))) match
        case Success(_) => // a free-text key may legitimately accept it
        case Failure(e: IllegalArgumentException) =>
          rejected += key
          assert(e.getMessage.trim.nonEmpty, key)
          assertNoSecret(e.getMessage)
        case Failure(other) =>
          fail(s"$key: untyped ${other.getClass.getName}: ${other.getMessage}")
    }
    // a vacuous loop would pass: most keys are numbers and must refuse a word
    assert(
      rejected.size >= 20,
      s"only ${rejected.size} keys refused: $rejected"
    )
    // and a key that did refuse names itself, not the value
    val numeric = failureOf(valid.updated("http.threads", canary))
    assertEquals(
      numeric.getMessage,
      "Invalid configuration value: http.threads"
    )
  }

  test("a bad token or secret is refused without being echoed") {
    // every bad value is also passed to the echo check, so an error that
    // repeated it fails (the short ones are not in the default secret list)
    val shortToken = "SHRTT"
    val shortCursor = "SHRTCUR8"
    val longToken = "t" * 300
    val cases = Vector(
      valid.updated("auth.queryToken", shortToken) -> shortToken,
      valid.updated(
        "auth.readToken",
        s"has space $canary-padding-padding"
      ) -> s"has space $canary-padding-padding",
      valid.updated("auth.queryToken", longToken) -> longToken,
      valid.updated(
        "auth.readToken",
        s"ctrl\u0007$canary-padding-padding"
      ) -> s"ctrl\u0007$canary-padding-padding",
      valid.updated("auth.readToken", queryToken) -> queryToken,
      valid.updated("auth.cursorSecret", shortCursor) -> shortCursor,
      valid.updated(
        "auth.cursorSecret",
        s"ctrl\u0001$canary-padding-padding"
      ) -> s"ctrl\u0001$canary-padding-padding"
    )
    cases.zipWithIndex.foreach { case ((values, bad), i) =>
      val e = failureOf(values)
      assertNoSecret(e.getMessage, bad)
      assert(e.getMessage.trim.nonEmpty, s"case $i")
    }
  }

  test("an unknown key is refused by name, and no secret value rides along") {
    val e = failureOf(valid.updated("auth.queryTokne", canary))
    assert(e.getMessage.contains("auth.queryTokne"), e.getMessage)
    assert(!e.getMessage.contains(canary), e.getMessage)
    assertNoSecret(e.getMessage)
  }

  test("the configuration prints as redacted however it is shown") {
    val config = ServiceConfig.from(valid)
    val shown = Vector(
      config.toString,
      s"$config",
      config.copy(maxRetries = 1).toString,
      Option(config).toString,
      Vector(config).toString,
      Map("config" -> config).toString
    )
    shown.foreach(text => assertNoSecret(text))
    shown.foreach(text => assert(text.contains("<redacted>"), text))
  }

  test("the environment form names the key and echoes no secret") {
    val env = Map(
      ServiceConfig.envName("auth.readToken") -> readToken,
      "UNRELATED_SECRET" -> canary
    )
    val e = Try(ServiceConfig.fromEnvironment(env)) match
      case Failure(e: IllegalArgumentException) => e
      case other => fail(s"expected a typed error, got $other")
    assertEquals(e.getMessage, "Missing configuration: auth.queryToken")
    assertNoSecret(e.getMessage)
    assertEquals(
      ServiceConfig.envName("auth.queryToken"),
      "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN"
    )
    val ok = ServiceConfig.fromEnvironment(
      Map(
        "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN" -> queryToken,
        "SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN" -> readToken,
        "UNRELATED_SECRET" -> canary
      )
    )
    assertEquals(ok.readToken, readToken)
  }

  test("a short cursor secret is refused by the codec without echoing it") {
    val e = intercept[IllegalArgumentException](CursorCodec(Some("tiny-key")))
    assertNoSecret(e.getMessage, "tiny-key")
    CursorCodec(Some(cursor)) // the floor accepts a proper secret
    CursorCodec(None) // and a missing one draws a random key
  }

  test("the local runtime refuses a bad override without echoing it") {
    val cases = Vector(
      Map("fault.workflow" -> canary),
      Map("fault.slowMillis" -> canary),
      Map("fault.nonsense" -> canary),
      Map("http.threads" -> canary)
    )
    cases.foreach { overrides =>
      val e = Try(LocalRuntime.start(overrides)) match
        case Failure(e: IllegalArgumentException) => e
        case Failure(other)                       =>
          fail(s"untyped ${other.getClass.getName}: ${other.getMessage}")
        case Success(runtime) =>
          runtime.close()
          fail(s"accepted $overrides")
      assert(!e.getMessage.contains(canary), e.getMessage)
      assert(e.getMessage.trim.nonEmpty)
    }
  }
