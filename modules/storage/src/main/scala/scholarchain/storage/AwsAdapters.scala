package scholarchain.storage

/** What stays DEFERRED in the AWS adapter set.
  *
  * Implemented against the AWS SDK v2 (see AwsObjectStore, AwsVectorBucket,
  * TitanEmbedder, AwsNeptuneBulkLoader, AwsNeptuneQuery, AwsReadModels,
  * AwsDynamoTable and the AwsClients factory): S3 objects, S3 Vectors, the
  * Titan embedder, the Neptune bulk loader, the Neptune openCypher query port
  * and the export-backed read models, and DynamoDB. `DeferredAwsNeptuneQuery`
  * is the superseded placeholder; the runtime uses `AwsNeptuneQuery`.
  *
  * An adapter is constructed only with explicit arguments (client, bucket,
  * region, role ARN). This module hardcodes no endpoint, account, bucket or
  * region, reads no environment and holds no credential: credentials come from
  * the SDK default provider chain through AwsClients.
  */
final case class AwsProfile(name: String):
  Fail.unless(
    name.trim.nonEmpty,
    ErrorCode.DeferredAdapter,
    "An explicit AWS profile name is required"
  )

object AwsAdapters:
  /** The status of the part that stays deferred: the Neptune query port. */
  val Status = "DEFERRED"
  val IntegrationProfile = "aws-integration"

  def deferred(what: String): Nothing =
    Fail(
      ErrorCode.DeferredAdapter,
      s"$what is DEFERRED: no adapter exists for it yet"
    )

final class DeferredAwsNeptuneQuery(profile: AwsProfile)
    extends NeptuneQueryPort:
  def run(query: CypherQuery): Vector[Row] =
    AwsAdapters.deferred("Neptune openCypher query")
