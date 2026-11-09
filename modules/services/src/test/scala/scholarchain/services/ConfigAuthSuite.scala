package scholarchain.services

import munit.FunSuite

class ConfigAuthSuite extends FunSuite:
  private val tokens = Map(
    "auth.queryToken" -> "query-token-0123456789",
    "auth.readToken" -> "read-token-0123456789"
  )

  private def refused(values: Map[String, String]): Unit =
    intercept[IllegalArgumentException](ServiceConfig.from(values))
    ()

  test("defaults apply and every policy value can be set from a key") {
    val c = ServiceConfig.from(tokens)
    assertEquals(c.bodyLimitBytes, 65536)
    assertEquals(c.maxRetries, 2)
    assertEquals(c.metricsEnabled, false)
    val tuned = ServiceConfig.from(
      tokens ++ Map(
        "http.bodyLimitBytes" -> "1024",
        "metrics.enabled" -> "true"
      )
    )
    assertEquals(tuned.bodyLimitBytes, 1024)
    assertEquals(tuned.metricsEnabled, true)
  }

  test("the tokens have no default and must be distinct and long enough") {
    refused(Map.empty)
    refused(tokens - "auth.readToken")
    refused(tokens + ("auth.readToken" -> tokens("auth.queryToken")))
    refused(tokens + ("auth.readToken" -> "short"))
    refused(tokens + ("auth.readToken" -> ("white space token" * 2)))
  }

  test("an unknown key or an out-of-range value is refused") {
    refused(tokens + ("http.bodyLimitBytse" -> "1"))
    refused(tokens + ("retry.max" -> "3"))
    refused(tokens + ("breaker.failureThreshold" -> "0"))
    refused(tokens + ("metrics.enabled" -> "yes"))
    refused(tokens + ("graph.defaultWindow" -> "2009-2000"))
    refused(tokens + ("graph.defaultWindow" -> "1-9999"))
  }

  test("toString never shows a token and the key list is complete") {
    val c = ServiceConfig.from(tokens)
    assert(!c.toString.contains("query-token"))
    assert(!c.toString.contains("read-token"))
    assert(ServiceConfig.Keys.contains("auth.queryToken"))
    assert(ServiceConfig.Keys.contains("breaker.failureThreshold"))
    assert(ServiceConfig.Keys.contains("fast.deadlineMillis"))
    assertEquals(
      ServiceConfig.envName("auth.queryToken"),
      "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN"
    )
  }

  test("configuration can be read from environment variables") {
    val env = Map(
      "SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN" -> "query-token-0123456789",
      "SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN" -> "read-token-0123456789",
      "SCHOLARCHAIN_SERVICES_HTTP_THREADS" -> "3",
      "UNRELATED" -> "x"
    )
    assertEquals(ServiceConfig.fromEnvironment(env).httpThreads, 3)
  }

  private val auth =
    new Authenticator("query-token-0123456789", "read-token-0123456789")

  private def code(header: Option[String], scope: Scope): Option[ErrorCode] =
    try
      auth.authorize(header, scope)
      None
    catch case e: ApiException => Some(e.code)

  test("a missing, malformed or wrong token is 401") {
    val bad = Vector(
      None,
      Some(""),
      Some("Bearer"),
      Some("Bearer "),
      Some("Bearer wrong"),
      Some("Basic read-token-0123456789"),
      Some("read-token-0123456789"),
      Some("Bearer read-token-0123456789x")
    )
    bad.foreach(h =>
      assertEquals(
        code(h, Scope.Read),
        Some(ErrorCode.Unauthorized),
        h.toString
      )
    )
  }

  test("a valid token of the wrong scope is 403 and the right scope passes") {
    assertEquals(
      code(Some("Bearer query-token-0123456789"), Scope.Read),
      Some(ErrorCode.Forbidden)
    )
    assertEquals(
      code(Some("Bearer read-token-0123456789"), Scope.Query),
      Some(ErrorCode.Forbidden)
    )
    assertEquals(code(Some("Bearer read-token-0123456789"), Scope.Read), None)
    assertEquals(code(Some("bearer query-token-0123456789"), Scope.Query), None)
  }

  test("a 401 or 403 message never carries a credential") {
    val e = intercept[ApiException](
      auth.authorize(Some("Bearer secret-value-123456"), Scope.Read)
    )
    assert(!e.getMessage.contains("secret-value"))
  }

  test("sensitive header values are redacted for logs") {
    assertEquals(Redact.header("Authorization", "Bearer x"), "<redacted>")
    assertEquals(Redact.header("Idempotency-Key", "k"), "<redacted>")
    assertEquals(Redact.header("Accept", "text/plain"), "text/plain")
  }
