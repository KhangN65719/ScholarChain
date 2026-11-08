package scholarchain.rag

import scholarchain.storage.*

class EmbeddingModelsSuite extends munit.FunSuite:
  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private final class FakeEmbedder(val modelVersion: String, val dimension: Int)
      extends Embedder:
    def embed(text: String): Vector[Float] = Vector.fill(dimension)(1.0f)

  test("hash-embed-v1 is registered with its dimension and generation") {
    val model = EmbeddingModels.HashEmbedV1
    assertEquals(model.version, "hash-embed-v1")
    assertEquals(model.dimension, 384)
    assertEquals(model.generation, 1)
    assertEquals(model.indexSuffix, "-v1")
    assertEquals(model.indexName("researcher-profile"), "researcher-profile-v1")
    assertEquals(EmbeddingModels.lookup("hash-embed-v1"), Some(model))
    assertEquals(EmbeddingModels.lookup("other"), None)
    val embedder = new HashEmbedder
    assertEquals(embedder.modelVersion, model.version)
    assertEquals(embedder.dimension, model.dimension)
  }

  test("the real embedder matches, a different one fails loudly") {
    EmbeddingModels.requireMatches(
      new HashEmbedder,
      EmbeddingModels.HashEmbedV1
    )
    assertEquals(
      code(
        EmbeddingModels.requireMatches(
          new FakeEmbedder("minilm-v1", 384),
          EmbeddingModels.HashEmbedV1
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
    val wrongDimension = intercept[StorageException](
      EmbeddingModels.requireMatches(
        new FakeEmbedder("hash-embed-v1", 768),
        EmbeddingModels.HashEmbedV1
      )
    )
    assertEquals(wrongDimension.code, ErrorCode.EmbeddingInvalid)
    assert(wrongDimension.getMessage.contains("768"))
    assert(wrongDimension.getMessage.contains("384"))
  }

  test("a mismatched embedder cannot build the retrieval") {
    val bucket = new InMemoryVectorBucket
    val store = new InMemoryObjectStore
    assertEquals(
      code(
        new SemanticRetrieval(new FakeEmbedder("minilm-v1", 384), bucket, store)
      ),
      ErrorCode.EmbeddingInvalid
    )
  }

  test("the five index specs fit the model; others do not") {
    val specs = VectorIndexes.all(384, "cosine")
    assertEquals(specs.size, 5)
    EmbeddingModels.requireIndexSpecs(EmbeddingModels.HashEmbedV1, specs)
    val wrongDimension = VectorIndexes.all(768, "cosine")
    assertEquals(
      code(
        EmbeddingModels.requireIndexSpecs(
          EmbeddingModels.HashEmbedV1,
          wrongDimension
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
    val next = EmbeddingModelSpec("hash-embed-v2", 384, 2)
    val error = intercept[StorageException](
      EmbeddingModels.requireIndexSpecs(next, specs)
    )
    assertEquals(error.code, ErrorCode.EmbeddingInvalid)
    assert(error.getMessage.contains("-v2"))
    assert(error.getMessage.contains("never an in-place change"))
  }

  test("a new model is a new generation with new index names") {
    val v2 = EmbeddingModelSpec("minilm-v1", 384, 2)
    assertEquals(v2.indexName("researcher-profile"), "researcher-profile-v2")
    EmbeddingModels.validateRegistry(EmbeddingModels.registry :+ v2)
  }

  test(
    "a registry with a repeated version or a skipped generation is refused"
  ) {
    val first = EmbeddingModels.HashEmbedV1
    assertEquals(
      code(
        EmbeddingModels.validateRegistry(
          Vector(first, EmbeddingModelSpec("hash-embed-v1", 768, 2))
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(
        EmbeddingModels.validateRegistry(
          Vector(first, EmbeddingModelSpec("minilm-v1", 384, 3))
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      code(
        EmbeddingModels.validateRegistry(
          Vector(first, EmbeddingModelSpec("minilm-v1", 384, 1))
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
  }

  test("a model spec refuses a blank version, dimension or generation") {
    intercept[IllegalArgumentException](EmbeddingModelSpec("", 384, 1))
    intercept[IllegalArgumentException](EmbeddingModelSpec("m", 0, 1))
    intercept[IllegalArgumentException](EmbeddingModelSpec("m", 384, 0))
  }
