package scholarchain.storage

import scala.jdk.CollectionConverters.*
import software.amazon.awssdk.awscore.exception.{
  AwsErrorDetails,
  AwsServiceException
}
import software.amazon.awssdk.services.s3vectors.model.{
  PutInputVector,
  PutVectorsRequest,
  PutVectorsResponse
}

/** The PutVectors handler of a stubbed S3 Vectors client that enforces the wire
  * rules the way the real service does: the whole request is checked with
  * `S3VectorsRules` before anything is kept, and one bad record refuses the
  * batch with an HTTP 400 `ValidationException` (S run 7).
  */
object S3VectorsWire:
  def validationException(message: String): AwsServiceException =
    AwsServiceException
      .builder()
      .statusCode(400)
      .message(message)
      .awsErrorDetails(
        AwsErrorDetails
          .builder()
          .errorCode("ValidationException")
          .errorMessage(message)
          .build()
      )
      .build()
      .asInstanceOf[AwsServiceException]

  /** `sink` sees (index, vector) of every accepted record. */
  def putHandler(
      sink: (String, PutInputVector) => Unit = (_, _) => ()
  ): AnyRef => AnyRef = request =>
    val put = request.asInstanceOf[PutVectorsRequest]
    val vectors = put.vectors.asScala.toVector
    vectors.foreach { vector =>
      try
        S3VectorsRules.check(
          vector.key,
          vector.metadata,
          AwsVectorBucket.NonFilterableKeys
        )
      catch
        case error: StorageException =>
          throw validationException(error.getMessage)
    }
    vectors.foreach(sink(put.indexName, _))
    PutVectorsResponse.builder().build()
