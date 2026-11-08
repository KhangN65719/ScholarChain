package scholarchain.rag

import scholarchain.storage.{
  Embedder,
  EmbeddingIngest,
  ErrorCode,
  Fail,
  MetaValue,
  ObjectStore,
  VectorBucketPort,
  VectorFilter,
  VectorQuery
}

/** Semantic retrieval over the local fakes: embed the query text with the same
  * model that built the index, query one index with an optional metadata
  * filter, and follow each hit's `textRef` to its verified text. A hit made by
  * another embedding model is refused. Retrieval answers "which summaries are
  * lexically near this text"; it returns no graph metric of its own, and the
  * context built from it says so.
  */
final class SemanticRetrieval(
    embedder: Embedder,
    bucket: VectorBucketPort,
    store: ObjectStore,
    expected: EmbeddingModelSpec = EmbeddingModels.HashEmbedV1
):
  EmbeddingModels.requireMatches(embedder, expected)

  /** `topK` is at most `SemanticContext.MaxItems`. Any such `topK` fits a
    * context: `SemanticContext.fromHits` scales each snippet to its share of
    * the total snippet cap.
    */
  def search(
      index: String,
      queryText: String,
      topK: Int,
      filter: VectorFilter = VectorFilter.empty
  ): Vector[RetrievedHit] =
    Fail.unless(
      index.endsWith(expected.indexSuffix),
      ErrorCode.InvalidQuery,
      s"Index $index is not an index of ${expected.version} " +
        s"(suffix ${expected.indexSuffix})"
    )
    Fail.unless(
      topK <= SemanticContext.MaxItems,
      ErrorCode.BoundExceeded,
      s"topK $topK is above the context cap ${SemanticContext.MaxItems}"
    )
    bucket
      .query(VectorQuery(index, embedder.embed(queryText), topK, filter))
      .map { hit =>
        Fail.unless(
          hit.metadata.get("embeddingModel") ==
            Some(MetaValue.Str(embedder.modelVersion)),
          ErrorCode.EmbeddingInvalid,
          s"Vector ${hit.key} was not made by ${embedder.modelVersion}"
        )
        RetrievedHit(index, hit, EmbeddingIngest.fetchText(store, hit))
      }

  def context(queryText: String, hits: Vector[RetrievedHit]): SemanticContext =
    SemanticContext.fromHits(queryText, embedder.modelVersion, hits)
