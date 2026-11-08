package scholarchain.rag

import scholarchain.storage.{
  Embedder,
  ErrorCode,
  Fail,
  HashEmbedder,
  VectorIndexSpec
}

/** One embedding model as the pipeline expects it: the version string stored in
  * every vector's `embeddingModel` metadata, the vector dimension, and the
  * index generation. The generation is the `N` of the `-vN` index-name suffix.
  */
final case class EmbeddingModelSpec(
    version: String,
    dimension: Int,
    generation: Int
):
  require(version.trim == version && version.nonEmpty, "Blank model version")
  require(dimension >= 1, "Dimension must be positive")
  require(generation >= 1, "Generation must be positive")

  def indexSuffix: String = s"-v$generation"

  /** `researcher-profile` becomes `researcher-profile-v1`. */
  def indexName(base: String): String = base + indexSuffix

/** The registry of embedding models. Rules:
  *
  *   - A model version string names one model. `hash-embed-v1` is
  *     `HashEmbedder` (`modules/storage`), 384 dimensions, generation 1.
  *   - A new model version or a new dimension is a new registry entry with the
  *     next generation, and its indexes are named with the new `-vN` suffix. An
  *     index is never changed in place (Phase 7 D7): the same key with another
  *     text hash or model is refused by the ingest path.
  *   - Generations are 1, 2, 3 and so on, one per entry, and no two entries
  *     share a model version.
  *   - Before any ingest or query the embedder is checked against the entry the
  *     configuration expects, and the index specs against its suffix and
  *     dimension. A mismatch fails at once with `EmbeddingInvalid`.
  */
object EmbeddingModels:
  val HashEmbedV1: EmbeddingModelSpec =
    EmbeddingModelSpec(HashEmbedder.ModelVersion, HashEmbedder.Dimension, 1)

  val registry: Vector[EmbeddingModelSpec] = Vector(HashEmbedV1)

  def validateRegistry(entries: Vector[EmbeddingModelSpec]): Unit =
    Fail.unless(
      entries.map(_.version).distinct.size == entries.size,
      ErrorCode.EmbeddingInvalid,
      "Two registry entries share a model version"
    )
    Fail.unless(
      entries.map(_.generation) == (1 to entries.size).toVector,
      ErrorCode.EmbeddingInvalid,
      "Registry generations must be 1, 2, 3 and so on, one per model"
    )

  validateRegistry(registry)

  def lookup(version: String): Option[EmbeddingModelSpec] =
    registry.find(_.version == version)

  /** Fails when the embedder is not the expected model and dimension. */
  def requireMatches(
      embedder: Embedder,
      expected: EmbeddingModelSpec
  ): Unit =
    Fail.unless(
      embedder.modelVersion == expected.version &&
        embedder.dimension == expected.dimension,
      ErrorCode.EmbeddingInvalid,
      s"Embedder is ${embedder.modelVersion} with dimension " +
        s"${embedder.dimension}, but the ingest expects ${expected.version} " +
        s"with dimension ${expected.dimension}"
    )

  /** Fails when an index spec does not carry the model's suffix and dimension.
    */
  def requireIndexSpecs(
      expected: EmbeddingModelSpec,
      specs: Vector[VectorIndexSpec]
  ): Unit =
    specs.foreach { spec =>
      Fail.unless(
        spec.name.endsWith(expected.indexSuffix) &&
          spec.dimension == expected.dimension,
        ErrorCode.EmbeddingInvalid,
        s"Index ${spec.name} (dimension ${spec.dimension}) does not fit " +
          s"${expected.version}: expected suffix ${expected.indexSuffix} and " +
          s"dimension ${expected.dimension}. A new model or dimension needs " +
          "new index names, never an in-place change"
      )
    }
