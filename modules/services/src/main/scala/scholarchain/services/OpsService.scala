package scholarchain.services

/** One dependency a service reports in its readiness. */
final case class Probe(name: String, call: Downstream, run: () => Unit)

/** Liveness, readiness and metrics. Liveness makes no dependency call at all.
  * Readiness asks each dependency through its port, under the readiness
  * deadline and its breaker, and reports component names and states only: no
  * endpoint, no secret and no text of a failure.
  */
final class OpsService(
    service: String,
    probes: Vector[Probe],
    config: ServiceConfig,
    metrics: Metrics
):
  def live: ujson.Value =
    ujson.Obj("status" -> "live", "service" -> service)

  private def state(probe: Probe): String =
    try
      probe.call.call(
        new Deadline(config.readyDeadlineMillis),
        idempotent = false
      )(
        probe.run()
      )
      "up"
    catch
      case e: ApiException =>
        e.code match
          case ErrorCode.Timeout     => "timeout"
          case ErrorCode.CircuitOpen => "circuit-open"
          case _                     => "down"

  def ready(): (Boolean, ujson.Value) =
    val states = probes.map(p => p.name -> state(p))
    val ok = states.forall(_._2 == "up")
    (
      ok,
      ujson.Obj(
        "status" -> (if ok then "ready" else "not-ready"),
        "service" -> service,
        "components" -> ujson.Obj.from(
          states.map((n, s) => n -> (ujson.Str(s): ujson.Value))
        )
      )
    )

  def metricsText: String =
    val gauges = probes.map { p =>
      val value = p.call.breaker.state match
        case "closed"    => 0
        case "half-open" => 1
        case _           => 2
      s"""breaker_state{port="${p.name}"} $value"""
    }
    metrics.render(gauges)
