package scholarchain.storage

/** DEFERRED bounded AWS integration suite.
  *
  * Phase 7 is local only: no AWS call, credential or SDK dependency. This suite
  * documents what the bounded live check will cover. It is skipped unless the
  * environment variable `SCHOLARCHAIN_AWS_IT=1` is set (see AwsIntegrationGate
  * and modules/storage/README.md), so it is inert in `sbt quick` and `sbt
  * check` by default. With the variable set the cases still skip with "AWS
  * adapters deferred": the adapters are stubs and no AWS call is made. Real
  * cases need the SDK pins (build-engineer) and named cost and size ceilings.
  *
  * Planned cases, each bounded to a handful of objects, rows or vectors:
  *   - S3: immutable put, head, list under one test prefix, cleanup of that
  *     prefix only.
  *   - DynamoDB: conditional put and versioned update against a throwaway
  *     table, ConditionalCheckFailedException mapped to ConditionalCheckFailed.
  *   - S3 Vectors: create index, one batch of at most 500, ThrottlingException
  *     mapped to TransientStorageException.
  *   - Neptune: one parameterized openCypher read against a small loaded graph.
  */
class AwsIntegrationSuite extends munit.FunSuite:
  override def munitIgnore: Boolean =
    !AwsIntegrationGate.enabled(sys.env)

  private def deferred(): Unit =
    assume(false, "AWS adapters deferred")

  test("DEFERRED: S3 immutable put/head/list against a test prefix") {
    deferred()
  }

  test("DEFERRED: DynamoDB conditional writes against a throwaway table") {
    deferred()
  }

  test("DEFERRED: S3 Vectors one bounded PutVectors batch") {
    deferred()
  }

  test("DEFERRED: Neptune parameterized read") {
    deferred()
  }

/** The opt-in gate (decision D6): the suite runs only when the environment
  * variable `SCHOLARCHAIN_AWS_IT` is exactly "1".
  */
object AwsIntegrationGate:
  val Variable = "SCHOLARCHAIN_AWS_IT"
  def enabled(env: Map[String, String]): Boolean =
    env.get(Variable).contains("1")

class AwsIntegrationGateSuite extends munit.FunSuite:
  test(
    "the AWS integration suite is skipped unless the variable is exactly 1"
  ) {
    assert(!AwsIntegrationGate.enabled(Map.empty))
    Vector("0", "true", "", "2", " 1", "1 ").foreach { v =>
      assert(
        !AwsIntegrationGate.enabled(Map("SCHOLARCHAIN_AWS_IT" -> v)),
        v
      )
    }
    assert(!AwsIntegrationGate.enabled(Map("OTHER" -> "1")))
    assert(AwsIntegrationGate.enabled(Map("SCHOLARCHAIN_AWS_IT" -> "1")))
  }

/** What stays deferred keeps failing closed: the Neptune query port has no
  * adapter yet. The S3, S3 Vectors, Titan, bulk-loader and DynamoDB adapters
  * are implemented and have their own suites.
  */
class DeferredAdapterSuite extends munit.FunSuite:
  private val profile = AwsProfile("aws-integration")

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  test("the Neptune query adapter is DEFERRED and refuses every call") {
    assertEquals(AwsAdapters.Status, "DEFERRED")
    val query = CypherCatalog.coauthorsOf("r", "all", 1)
    assertEquals(
      code(new DeferredAwsNeptuneQuery(profile).run(query)),
      ErrorCode.DeferredAdapter
    )
  }

  test("an adapter needs an explicit non-blank profile") {
    assertEquals(code(AwsProfile(" ")), ErrorCode.DeferredAdapter)
  }
