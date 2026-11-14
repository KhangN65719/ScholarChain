package scholarchain.tests.adversarial

import java.nio.file.{Files, Path, Paths}
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*

/** Work order 35, item 3: no staged job artifact of hadoop, entityResolution or
  * sparkAnalytics carries the AWS SDK. `jobSdkGuard` checks the Runtime
  * classpath at build time; this checks the staged `target/job` directory
  * itself (a different artifact, which can be stale or hand-assembled), by jar
  * name and by jar content. A module without a staged directory asserts nothing
  * and says so.
  */
class P14JobSdkStagedLibSuite extends munit.FunSuite:
  private val modules = Vector("hadoop", "entity-resolution", "spark")
  private val sdkEntry = "software/amazon/awssdk/"

  private def info(message: String): Unit = println(s"[sdk-guard] $message")

  private def jars(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val stream = Files.walk(dir)
      try
        stream.iterator.asScala
          .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".jar"))
          .toVector
      finally stream.close()

  private def holdsSdk(jar: Path): Boolean =
    val zip = new ZipFile(jar.toFile)
    try zip.entries.asScala.exists(_.getName.startsWith(sdkEntry))
    finally zip.close()

  modules.foreach { module =>
    test(s"modules/$module staged job holds no AWS SDK jar") {
      val job = Paths.get("modules", module, "target", "job").toAbsolutePath
      if !Files.isDirectory(job) then
        info(s"modules/$module/target/job is absent: nothing asserted")
      else
        val all = jars(job)
        info(s"modules/$module/target/job: ${all.size} jar(s) inspected")
        val byName = all.filter { p =>
          val n = p.getFileName.toString
          n.startsWith("aws-core-") || n.startsWith("sdk-core-") ||
          n.startsWith("bedrockruntime-") || n.startsWith("s3-") ||
          n.startsWith("s3vectors-") || n.startsWith("neptunedata-") ||
          n.startsWith("dynamodb-") || n.startsWith("url-connection-client-")
        }
        assertEquals(byName.map(_.getFileName.toString), Vector.empty[String])
        val byContent = all.filter(holdsSdk)
        assertEquals(
          byContent.map(_.getFileName.toString),
          Vector.empty[String]
        )
    }
  }
