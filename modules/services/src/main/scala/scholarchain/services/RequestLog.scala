package scholarchain.services

import java.time.Instant
import org.slf4j.LoggerFactory

/** One structured line per query request, for latency evidence. The line is one
  * JSON object: the UTC time, the request ID, the retrieval mode, the execution
  * mode (sync or async), the HTTP status, the total milliseconds and the rag
  * stage timings. A stage that was skipped is the string `skipped`, not 0. The
  * question text, the answer, tokens and header values never appear. A request
  * refused before its mode was known logs `-` for the modes and no stages.
  */
final class RequestLog(
    sink: String => Unit,
    clock: () => Instant = () => Instant.now()
):
  def query(
      requestId: String,
      retrievalMode: Option[String],
      executionMode: Option[String],
      status: Int,
      totalMillis: Long,
      stages: Vector[(String, Option[Long])]
  ): Unit =
    val stagesJson = ujson.Obj.from(stages.map { (name, millis) =>
      name -> millis.fold[ujson.Value](ujson.Str("skipped"))(m =>
        ujson.Num(m.toDouble)
      )
    })
    val line = ujson.Obj(
      "event" -> "query_request",
      "time" -> clock().toString,
      "requestId" -> requestId,
      "mode" -> retrievalMode.getOrElse("-"),
      "executionMode" -> executionMode.getOrElse("-"),
      "status" -> status,
      "totalMs" -> totalMillis.toDouble,
      "stagesMs" -> stagesJson
    )
    try sink(ujson.write(line))
    catch case _: Exception => ()

object RequestLog:
  val QueryRoute = "POST /v1/query"

  private val logger = LoggerFactory.getLogger("scholarchain.services.latency")

  val default: RequestLog = new RequestLog(line => logger.info(line))
