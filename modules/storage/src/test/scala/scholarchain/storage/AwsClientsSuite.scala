package scholarchain.storage

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region

/** The client factory is the only source of SDK clients. No call reaches AWS:
  * building a client does not resolve credentials or open a connection.
  */
class AwsClientsSuite extends munit.FunSuite:
  test(
    "the factory takes a region and nothing else: no key, secret or profile"
  ) {
    val constructors = classOf[AwsClients].getConstructors.toVector
    assertEquals(constructors.size, 1)
    assertEquals(
      constructors.head.getParameterTypes.toVector,
      Vector[Class[?]](classOf[Region])
    )
    val publicMembers = classOf[AwsClients].getMethods.toVector.map(_.getName)
    val keyLike =
      publicMembers.filter(n =>
        n.toLowerCase.contains("key") || n.toLowerCase.contains("secret") ||
          n.toLowerCase.contains("token") || n.toLowerCase.contains("profile")
      )
    assertEquals(keyLike, Vector.empty)
  }

  test("credentials come from the default provider chain") {
    val clients = new AwsClients(Region.US_EAST_2)
    assert(clients.credentials.isInstanceOf[DefaultCredentialsProvider])
    assertEquals(clients.region, Region.US_EAST_2)
  }

  test("the one HTTP client is the URL connection client") {
    val clients = new AwsClients(Region.US_EAST_2)
    assert(clients.httpClient.isInstanceOf[UrlConnectionHttpClient])
    assertEquals(clients.httpClient.clientName, "UrlConnection")
  }

  test("every client builds from the factory without contacting AWS") {
    val clients = new AwsClients(Region.US_EAST_2)
    val built = Vector(
      clients.s3(),
      clients.s3Vectors(),
      clients.bedrockRuntime(),
      clients.neptune(java.net.URI.create("https://neptune.invalid:8182")),
      clients.dynamo()
    )
    assertEquals(built.size, 5)
    built.foreach(_.close())
  }

  test("forRegion needs an explicit region") {
    assertEquals(
      intercept[StorageException](AwsClients.forRegion(" ")).code,
      ErrorCode.AwsRejected
    )
    assertEquals(AwsClients.forRegion("us-east-2").region, Region.US_EAST_2)
  }
