package scholarchain.storage

// Fixture for tests/scripts/w2/tfcheck.py. Not compiled.
object EmbeddingWorkerMain:
  val EnvRegion = "AWS_REGION"
  val EnvQueueUrl = "SCHOLARCHAIN_EMBEDDING_QUEUE_URL"
  val EnvDataBucket = "SCHOLARCHAIN_DATA_BUCKET"
  val EnvVectorBucket = "SCHOLARCHAIN_VECTOR_BUCKET"
  val EnvTablePrefix = "SCHOLARCHAIN_TABLE_PREFIX"

  def main(args: Array[String]): Unit = ()
