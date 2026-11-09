package scholarchain.services

import scholarchain.core.EvidenceType
import scholarchain.rag.Prompts

object EvidenceViews:
  /** Provenance, source record, source URI, graph path or text pointer, and the
    * stored fact cut to a compact size.
    */
  def view(stored: StoredEvidence, maxContentChars: Int): ujson.Value =
    val item = stored.item
    val (content, truncated) = item.text match
      case Some(t) if t.length > maxContentChars =>
        (Some(t.take(maxContentChars)), true)
      case other => (other, false)
    ujson.Obj(
      "evidenceId" -> item.evidenceId,
      "evidenceType" -> item.evidenceType.wire,
      "sourceUri" -> item.sourceUri,
      "workId" -> J.opt(item.workId),
      "chunkId" -> J.opt(item.chunkId),
      "graphPath" -> item.graphPath.fold[ujson.Value](ujson.Null)(J.strs),
      "textPointer" -> (if item.evidenceType == EvidenceType.TextChunk then
                          ujson.Str(item.sourceUri)
                        else ujson.Null),
      "score" -> item.score,
      "confidence" -> J.optNum(item.confidence),
      "uncertainty" -> J.opt(item.uncertainty),
      "content" -> J.opt(content),
      "contentTruncated" -> truncated,
      "sourceRecord" -> ujson.Obj(
        "sourceRecordId" -> item.provenance.sourceRecordId,
        "snapshotId" -> item.provenance.snapshotId,
        "source" -> item.provenance.source.wire
      ),
      "provenance" -> upickle.default.writeJs(item.provenance),
      "queryId" -> stored.queryId,
      "bundleId" -> stored.bundleId
    )

final class EvidenceService(
    port: EvidenceReadPort,
    call: Downstream,
    config: ServiceConfig
):
  def get(evidenceId: String, deadline: Deadline): ujson.Value =
    call
      .call(deadline, idempotent = true)(port.find(evidenceId))
      .map(EvidenceViews.view(_, config.evidenceMaxContentChars))
      .getOrElse(
        throw new ApiException(ErrorCode.NotFound, "No such evidence")
      )

object ExplainViews:
  private def field(payload: ujson.Value, key: String): ujson.Value =
    payload.objOpt.flatMap(_.value.get(key)).getOrElse(ujson.Null)

  private def nested(value: ujson.Value, key: String): ujson.Value =
    value.objOpt.flatMap(_.value.get(key)).getOrElse(ujson.Null)

  private def promptIds(prompts: ujson.Value): Set[String] =
    prompts.arrOpt
      .map(_.value.flatMap(p => nested(p, "id").strOpt).toSet)
      .getOrElse(Set.empty)

  /** What the audit trail stored about one query, shaped for a reader. It holds
    * the plan, the stages, prompt and model versions, evidence IDs, score
    * components, counters and verification status. The model returns no
    * reasoning and none is stored, so none is shown.
    */
  def view(stored: StoredExplain): ujson.Value =
    val p = stored.payload
    val items = stored.bundle.map(_.items).getOrElse(Vector.empty)
    val called = promptIds(field(p, "prompts"))
    val tokens = field(p, "tokens")
    val verification = field(p, "verification")
    val abstained = field(p, "abstained").boolOpt.getOrElse(false)
    val overall = nested(verification, "overall").strOpt
    ujson.Obj(
      "queryId" -> stored.queryId,
      "status" -> (if abstained then "abstained" else "answered"),
      "abstained" -> abstained,
      "abstentionReason" -> field(p, "abstentionReason"),
      "queryPlan" -> field(p, "queryPlan"),
      "planDetails" -> field(p, "planDetails"),
      "resolutions" -> field(p, "resolutions"),
      "stages" -> field(p, "stages"),
      "prompts" -> J.arr(Prompts.all.map { spec =>
        ujson.Obj(
          "id" -> spec.id,
          "version" -> spec.version,
          "hash" -> spec.hash,
          "schemaId" -> spec.schemaId,
          "calledInThisQuery" -> called.contains(spec.id)
        ): ujson.Value
      }),
      "models" -> field(p, "models"),
      "evidence" -> ujson.Obj(
        "bundleId" -> field(p, "bundleId"),
        "evidenceIds" -> field(p, "evidenceIds")
      ),
      "scoreComponents" -> J.arr(items.map { item =>
        ujson.Obj(
          "evidenceId" -> item.evidenceId,
          "evidenceType" -> item.evidenceType.wire,
          "score" -> item.score,
          "confidence" -> J.optNum(item.confidence)
        ): ujson.Value
      }),
      "counters" -> ujson.Obj(
        "modelCalls" -> field(p, "modelCalls"),
        "promptTokens" -> nested(tokens, "promptTokens"),
        "completionTokens" -> nested(tokens, "completionTokens"),
        "tokensEstimated" -> nested(tokens, "estimated"),
        "latencyMillis" -> field(p, "latencyMillis"),
        "evidenceItems" -> items.size,
        "textChunkItems" -> items.count(
          _.evidenceType == EvidenceType.TextChunk
        )
      ),
      "verification" -> ujson.Obj(
        "status" -> overall.getOrElse(
          if abstained then "abstained" else "unreported"
        ),
        "report" -> verification
      ),
      "flags" -> field(p, "flags"),
      "versions" -> ujson.Obj(
        "graphRunId" -> field(p, "graphRunId"),
        "queryRunId" -> field(p, "queryRunId"),
        "metricVersions" -> field(p, "metricVersions"),
        "evidenceRunIds" -> field(p, "evidenceRunIds"),
        "schemaHashes" -> field(p, "schemaHashes")
      )
    )

final class ExplainService(port: ExplainReadPort, call: Downstream):
  def get(queryId: String, deadline: Deadline): ujson.Value =
    call
      .call(deadline, idempotent = true)(port.find(queryId))
      .map(ExplainViews.view)
      .getOrElse(
        throw new ApiException(ErrorCode.NotFound, "No such query")
      )
