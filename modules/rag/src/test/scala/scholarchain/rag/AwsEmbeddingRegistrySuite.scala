package scholarchain.rag

import scholarchain.storage.*

/** The AWS registry profile (decision D19): Titan-256 is generation 1, so its
  * indexes are the Terraform `-v1` indexes at 256 dimensions, while
  * `hash-embed-v1` (384) stays the local entry.
  */
class AwsEmbeddingRegistrySuite extends munit.FunSuite:
  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private val terraformIndexes = Vector(
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1"
  )

  test("Titan-256 is generation 1 and carries the adapter's version string") {
    val titan = EmbeddingModels.TitanTextV2D256
    assertEquals(titan.version, TitanEmbedder.ModelVersion)
    assertEquals(titan.version, "amazon.titan-embed-text-v2:0-d256-norm")
    assertEquals(titan.dimension, 256)
    assertEquals(titan.dimension, TitanEmbedder.Dimension)
    assertEquals(titan.generation, 1)
    assertEquals(titan.indexSuffix, "-v1")
    assertEquals(
      terraformIndexes.map(name => titan.indexName(name.stripSuffix("-v1"))),
      terraformIndexes
    )
  }

  test("each profile is its own valid registry; the local one is unchanged") {
    assertEquals(
      EmbeddingModels.profile("aws"),
      Vector(EmbeddingModels.TitanTextV2D256)
    )
    assertEquals(
      EmbeddingModels.profile("local"),
      Vector(EmbeddingModels.HashEmbedV1)
    )
    assertEquals(EmbeddingModels.registry, Vector(EmbeddingModels.HashEmbedV1))
    assertEquals(EmbeddingModels.HashEmbedV1.dimension, 384)
    assertEquals(EmbeddingModels.HashEmbedV1.generation, 1)
    EmbeddingModels.validateRegistry(EmbeddingModels.awsRegistry)
    assertEquals(
      code(EmbeddingModels.profile("gcp")),
      ErrorCode.EmbeddingInvalid
    )
    assertEquals(
      EmbeddingModels
        .lookupIn(EmbeddingModels.awsRegistry, TitanEmbedder.ModelVersion),
      Some(EmbeddingModels.TitanTextV2D256)
    )
    assertEquals(EmbeddingModels.lookup(TitanEmbedder.ModelVersion), None)
  }

  test("the Titan entry matches the five 256-dimension index specs") {
    val specs = VectorIndexes.all(256, "cosine")
    assertEquals(specs.map(_.name), terraformIndexes)
    assert(specs.forall(_.dimension == 256))
    EmbeddingModels.requireIndexSpecs(EmbeddingModels.TitanTextV2D256, specs)
  }

  test("a 384-dimension embedder is refused for the Titan entry") {
    val error = intercept[StorageException](
      EmbeddingModels.requireMatches(
        new HashEmbedder,
        EmbeddingModels.TitanTextV2D256
      )
    )
    assertEquals(error.code, ErrorCode.EmbeddingInvalid)
    assert(error.getMessage.contains("384"), error.getMessage)
    assert(error.getMessage.contains("256"), error.getMessage)
  }

  test("384-dimension index specs are refused for the Titan entry") {
    val error = intercept[StorageException](
      EmbeddingModels.requireIndexSpecs(
        EmbeddingModels.TitanTextV2D256,
        VectorIndexes.all(384, "cosine")
      )
    )
    assertEquals(error.code, ErrorCode.EmbeddingInvalid)
    assert(error.getMessage.contains("dimension 384"), error.getMessage)
    assert(error.getMessage.contains("dimension 256"), error.getMessage)
  }

  test("a 256-dimension index spec is refused for hash-embed-v1") {
    assertEquals(
      code(
        EmbeddingModels.requireIndexSpecs(
          EmbeddingModels.HashEmbedV1,
          VectorIndexes.all(256, "cosine")
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
  }

  test("a retrieval built for Titan refuses the local embedder") {
    assertEquals(
      code(
        new SemanticRetrieval(
          new HashEmbedder,
          new InMemoryVectorBucket,
          new InMemoryObjectStore,
          EmbeddingModels.TitanTextV2D256
        )
      ),
      ErrorCode.EmbeddingInvalid
    )
  }

  test("an explicit generation override is refused until indexes carry it") {
    val v2 = EmbeddingModels.awsWithGeneration(2)
    assertEquals(v2.indexSuffix, "-v2")
    assertEquals(v2.version, TitanEmbedder.ModelVersion)
    val error = intercept[StorageException](
      EmbeddingModels.requireIndexSpecs(v2, VectorIndexes.all(256, "cosine"))
    )
    assertEquals(error.code, ErrorCode.EmbeddingInvalid)
    assert(error.getMessage.contains("-v2"), error.getMessage)
    assertEquals(
      EmbeddingModels.awsWithGeneration(1),
      EmbeddingModels.TitanTextV2D256
    )
  }
