package scholarchain.services

/** The routes of the two services. A handler parses the request, calls one
  * service method and maps the result. Authentication and authorization happen
  * in the router before a handler runs. Nothing here touches a store or a
  * model.
  */
object Routes:
  private def readyResponse(ops: OpsService): Response =
    val (ok, body) = ops.ready()
    Response.json(if ok then 200 else 503, body)

  private def opsRoutes(ops: OpsService): Vector[Route] = Vector(
    Route("GET", "/healthz", None, (_, _) => Response.json(200, ops.live)),
    Route("GET", "/readyz", None, (_, _) => readyResponse(ops))
  )

  /** Evidence, graph, explain and operations. */
  def fast(
      config: ServiceConfig,
      evidence: EvidenceService,
      graph: GraphService,
      explain: ExplainService,
      ops: OpsService
  ): Vector[Route] =
    def deadline = new Deadline(config.fastDeadlineMillis)
    Vector(
      Route(
        "GET",
        "/v1/evidence/{id}",
        Some(Scope.Read),
        (_, p) => Response.json(200, evidence.get(Ids.pathId(p(0)), deadline))
      ),
      Route(
        "GET",
        "/v1/graph/nodes/{id}",
        Some(Scope.Read),
        (_, p) => Response.json(200, graph.node(Ids.pathId(p(0)), deadline))
      ),
      Route(
        "GET",
        "/v1/graph/nodes/{id}/neighbors",
        Some(Scope.Read),
        (req, p) =>
          Response.json(
            200,
            graph.neighbors(
              Ids.pathId(p(0)),
              NeighborQuery.parse(req.query),
              deadline
            )
          )
      ),
      Route(
        "GET",
        "/v1/explain/{id}",
        Some(Scope.Read),
        (_, p) => Response.json(200, explain.get(Ids.pathId(p(0)), deadline))
      ),
      Route(
        "GET",
        "/metrics",
        Some(Scope.Read),
        (_, _) =>
          if config.metricsEnabled then Response.text(200, ops.metricsText)
          else
            throw new ApiException(
              ErrorCode.NotFound,
              "Metrics are not enabled"
            )
      )
    ) ++ opsRoutes(ops)

  private def submitQuery(
      req: Request,
      config: ServiceConfig,
      queries: QueryService,
      jobs: JobService
  ): Response =
    req.requireJson()
    val parsed = QueryRequests.parse(req.body, config)
    val key = req.header("idempotency-key").map(Ids.idempotencyKey)
    val request = QueryRequests.resolve(
      parsed,
      QueryRequests.prefersAsync(req.header("prefer")),
      config
    )
    val fingerprint = QueryRequests.fingerprint(req.body, request.mode)
    queries.submit(
      request,
      key,
      fingerprint,
      new Deadline(config.slowDeadlineMillis)
    ) match
      case Answered(outcome)  => Response.json(200, outcome.toJson)
      case accepted: Accepted =>
        Response.json(
          202,
          QueryViews.accepted(accepted, jobs.stateOf(accepted)),
          Map("Location" -> s"/v1/jobs/${accepted.jobId}")
        )

  /** Queries, jobs and operations. */
  def slow(
      config: ServiceConfig,
      queries: QueryService,
      jobs: JobService,
      ops: OpsService
  ): Vector[Route] =
    Vector(
      Route(
        "POST",
        "/v1/query",
        Some(Scope.Query),
        (req, _) => submitQuery(req, config, queries, jobs)
      ),
      Route(
        "GET",
        "/v1/jobs/{id}",
        Some(Scope.Read),
        (_, p) =>
          Response.json(200, JobViews.status(jobs.get(Ids.pathId(p(0)))))
      ),
      Route(
        "GET",
        "/v1/jobs/{id}/result",
        Some(Scope.Read),
        (_, p) => Response.json(200, jobs.result(Ids.pathId(p(0))).toJson)
      )
    ) ++ opsRoutes(ops)
