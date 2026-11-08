package scholarchain.storage

/** DEFERRED. The AWS-backed adapters are not implemented in this phase: the
  * human scoped Phase 7 to local ports, fakes and contract tests, and the build
  * has no AWS SDK dependency (build-engineer owns pins; no dependency was
  * added). Each class below is the seam where the SDK adapter goes. Every call
  * fails with DeferredAdapter.
  *
  * An adapter is constructed only with an explicit profile. The profile names
  * the target by configuration; this module hardcodes no endpoint, account,
  * bucket or region, and reads no credential.
  *
  * Exact SDK need, for build-engineer when the human lifts the deferral: AWS
  * SDK v2 modules `s3`, `dynamodb` and `s3vectors`, plus the Neptune data API
  * or an openCypher HTTPS client for the VPC endpoint (no SDK module is assumed
  * for Neptune queries). Versions go through the approved ledger.
  */
final case class AwsProfile(name: String):
  Fail.unless(
    name.trim.nonEmpty,
    ErrorCode.DeferredAdapter,
    "An explicit AWS profile name is required"
  )

object AwsAdapters:
  val Status = "DEFERRED"
  val IntegrationProfile = "aws-integration"

  def deferred(what: String): Nothing =
    Fail(
      ErrorCode.DeferredAdapter,
      s"$what is DEFERRED: no AWS SDK dependency and no live access in Phase 7"
    )

final class DeferredAwsObjectStore(profile: AwsProfile) extends ObjectStore:
  protected def write(
      key: String,
      bytes: Array[Byte],
      sourceRef: String
  ): Unit = AwsAdapters.deferred("S3 write")
  def head(key: String): Option[ObjectMeta] = AwsAdapters.deferred("S3 head")
  def get(key: String): Option[Array[Byte]] = AwsAdapters.deferred("S3 get")
  def list(prefix: String): Vector[ObjectMeta] = AwsAdapters.deferred("S3 list")

final class DeferredAwsVectorBucket(profile: AwsProfile)
    extends VectorBucketPort:
  def ensureIndex(spec: VectorIndexSpec): Unit =
    AwsAdapters.deferred("S3 Vectors CreateIndex")
  def putVectors(index: String, batch: Vector[VectorRecord]): Unit =
    AwsAdapters.deferred("S3 Vectors PutVectors")
  def getVectors(index: String, keys: Vector[String]): Vector[VectorRecord] =
    AwsAdapters.deferred("S3 Vectors GetVectors")
  def count(index: String): Int = AwsAdapters.deferred("S3 Vectors count")
  def query(request: VectorQuery): Vector[VectorMatch] =
    AwsAdapters.deferred("S3 Vectors QueryVectors")

final class DeferredAwsNeptune(profile: AwsProfile)
    extends NeptuneBulkLoader
    with NeptuneQueryPort:
  def load(manifestKey: String): BulkLoadResult =
    AwsAdapters.deferred("Neptune bulk load")
  def run(query: CypherQuery): Vector[Row] =
    AwsAdapters.deferred("Neptune openCypher query")
