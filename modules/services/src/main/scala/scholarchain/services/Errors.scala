package scholarchain.services

import java.time.Instant

/** The typed error codes of the API, one per condition of the status mapping.
  * Messages are fixed text: they never echo input, tokens, stack traces or the
  * text of a downstream failure.
  */
// JOB_FAILED (409) answers GET /v1/jobs/{id}/result for a job that ended
// failed: the GET itself completed, so it never replays the job's own status.
// The job's original error code is the single entry of `details`.
enum ErrorCode(val wire: String, val status: Int):
  case InvalidJson extends ErrorCode("INVALID_JSON", 400)
  case InvalidRequest extends ErrorCode("INVALID_REQUEST", 400)
  case Unauthorized extends ErrorCode("UNAUTHORIZED", 401)
  case Forbidden extends ErrorCode("FORBIDDEN", 403)
  case NotFound extends ErrorCode("NOT_FOUND", 404)
  case MethodNotAllowed extends ErrorCode("METHOD_NOT_ALLOWED", 405)
  case ResultNotReady extends ErrorCode("RESULT_NOT_READY", 409)
  case JobFailed extends ErrorCode("JOB_FAILED", 409)
  case PayloadTooLarge extends ErrorCode("PAYLOAD_TOO_LARGE", 413)
  case UnsupportedMediaType extends ErrorCode("UNSUPPORTED_MEDIA_TYPE", 415)
  case UnsupportedConstraint extends ErrorCode("UNSUPPORTED_CONSTRAINT", 422)
  case IdempotencyKeyReused extends ErrorCode("IDEMPOTENCY_KEY_REUSED", 422)
  case Internal extends ErrorCode("INTERNAL", 500)
  case DownstreamUnavailable extends ErrorCode("DOWNSTREAM_UNAVAILABLE", 503)
  case CircuitOpen extends ErrorCode("CIRCUIT_OPEN", 503)
  case Overloaded extends ErrorCode("OVERLOADED", 503)
  case Timeout extends ErrorCode("TIMEOUT", 504)

final class ApiException(
    val code: ErrorCode,
    message: String,
    val details: Vector[String] = Vector.empty,
    val headers: Map[String, String] = Map.empty
) extends RuntimeException(message, null, false, false)

/** The identifiers every response carries. */
final case class RequestContext(requestId: String, traceId: String)

object ApiError:
  def body(error: ApiException, ctx: RequestContext): ujson.Value =
    ujson.Obj(
      "error" -> ujson.Obj(
        "code" -> error.code.wire,
        "message" -> error.getMessage,
        "traceId" -> ctx.traceId,
        "requestId" -> ctx.requestId,
        "details" -> J.strs(error.details)
      )
    )

  def response(error: ApiException, ctx: RequestContext): Response =
    Response.json(error.code.status, body(error, ctx), error.headers)

/** Small JSON builders shared by the views. */
object J:
  def strs(values: Iterable[String]): ujson.Value =
    ujson.Arr.from(values.map(v => ujson.Str(v): ujson.Value))

  def opt(value: Option[String]): ujson.Value =
    value.fold[ujson.Value](ujson.Null)(v => ujson.Str(v))

  def optNum(value: Option[Double]): ujson.Value =
    value.fold[ujson.Value](ujson.Null)(v => ujson.Num(v))

  def instant(value: Instant): ujson.Value = ujson.Str(value.toString)

  def arr(values: Iterable[ujson.Value]): ujson.Value = ujson.Arr.from(values)
