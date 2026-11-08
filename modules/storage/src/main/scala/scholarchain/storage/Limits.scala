package scholarchain.storage

/** Service limits as named constants. Sources and capture date are in
  * docs/agent-runs/phase-07/service-limits.md (public AWS documentation pages,
  * captured 2026-11-08). Re-verify before any live run.
  */
object Limits:
  object S3Vectors:
    val MaxVectorKeyChars = 1024
    val MaxBatchVectors = 500
    val MaxGetBatch = 100
    val MaxFilterableMetadataBytes = 2048
    val MaxTotalMetadataBytes = 40 * 1024
    val MaxMetadataKeys = 50
    val MaxNonFilterableKeysPerIndex = 10
    val MinDimension = 1
    val MaxDimension = 4096
    val MaxPayloadBytes: Long = 20L * 1024 * 1024

    /** QueryVectors top-K: up to 10,000 (service-limits.md, S3 Vectors). */
    val MaxTopK = 10000

    /** QueryVectors results per page: up to 100. The local fake returns the
      * whole top-K in one call; the AWS adapter pages with this size.
      */
    val QueryPageSize = 100

    /** Filter constraints per query on an ENHANCED index: up to 100. */
    val MaxFilterConstraints = 100

    val DistanceMetrics = Set("cosine", "euclidean")

    /** Calendar-year bounds a filter or metadata year may take (the Spark
      * all-years window is 1 to 9999).
      */
    val MinYear = 1
    val MaxYear = 9999

    /** Project policy, not an AWS limit: a metadata string longer than this is
      * treated as text, and text belongs in S3.
      */
    val MaxMetadataStringChars = 200

    /** Project policy: references per metadata list. */
    val MaxMetadataListItems = 5

  object DynamoDb:
    val MaxItemBytes: Long = 400L * 1024
    val MaxPartitionKeyBytes = 2048
    val MaxSortKeyBytes = 1024
    val MaxBatchWrite = 25
    val MaxBatchGet = 100
    val MinTableNameChars = 3
    val MaxTableNameChars = 255
    val TableNamePattern = "[A-Za-z0-9_.-]+"

  object Neptune:
    val MaxPropertyBytes: Long = 55L * 1024 * 1024
    val MaxQueuedLoadJobs = 64

  object S3:
    /** The documented 1,024-byte ordinary S3 key limit was NOT captured in
      * service-limits.md ("Not captured"). It is used here as a conservative
      * guard and stays UNVERIFIED against the current page.
      */
    val MaxKeyBytes = 1024
