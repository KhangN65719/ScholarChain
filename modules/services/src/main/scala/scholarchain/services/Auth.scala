package scholarchain.services

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** The two scopes of the local deployment. A token carries exactly one. */
enum Scope(val wire: String):
  case Query extends Scope("query")
  case Read extends Scope("read")

/** Static bearer tokens read from configuration, compared in constant time.
  * Both candidates are always compared, so the time taken does not reveal which
  * token, if any, matched. No token value reaches a response or a log.
  */
final class Authenticator(queryToken: String, readToken: String):
  private def digest(value: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))

  private val queryDigest = digest(queryToken)
  private val readDigest = digest(readToken)

  private def bearer(header: Option[String]): Option[String] =
    header.flatMap { value =>
      val parts = value.trim.split(" +", 2)
      if parts.length == 2 && parts(0).equalsIgnoreCase("Bearer") then
        Some(parts(1).trim).filter(_.nonEmpty)
      else None
    }

  private def scopeOf(token: String): Option[Scope] =
    val candidate = digest(token)
    val isQuery = MessageDigest.isEqual(candidate, queryDigest)
    val isRead = MessageDigest.isEqual(candidate, readDigest)
    if isQuery then Some(Scope.Query)
    else if isRead then Some(Scope.Read)
    else None

  private def unauthorized(): ApiException =
    new ApiException(
      ErrorCode.Unauthorized,
      "A valid bearer token is required",
      Vector.empty,
      Map("WWW-Authenticate" -> "Bearer")
    )

  // A valid token of either scope, or 401. The router asks this before it
  // answers 404 or 405 for a /v1 path, so an anonymous caller cannot map the
  // routes.
  def authenticate(header: Option[String]): Unit =
    if bearer(header).flatMap(scopeOf).isEmpty then throw unauthorized()

  // Passes, or throws 401 (no valid token) or 403 (valid token, wrong scope).
  def authorize(header: Option[String], needed: Scope): Unit =
    val granted = bearer(header).flatMap(scopeOf)
    granted match
      case None                           => throw unauthorized()
      case Some(scope) if scope != needed =>
        throw new ApiException(
          ErrorCode.Forbidden,
          s"The token does not carry the ${needed.wire} scope"
        )
      case Some(_) => ()

/** Header values that must never be logged. */
object Redact:
  private val sensitive = Set(
    "authorization",
    "proxy-authorization",
    "idempotency-key",
    "cookie",
    "set-cookie",
    "x-api-key"
  )

  def header(name: String, value: String): String =
    if sensitive.contains(name.toLowerCase) then "<redacted>" else value
