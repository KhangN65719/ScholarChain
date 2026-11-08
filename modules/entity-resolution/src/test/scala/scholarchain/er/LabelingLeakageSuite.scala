package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scholarchain.er.matching.{Matcher, Policy}

/** The pairs export and the labeling tool live in their own packages. No
  * matching or adjudication code may reference them, and they may not read
  * matcher output. The scan fails if it finds no sources.
  */
class LabelingLeakageSuite extends munit.FunSuite:
  private val relative =
    "modules/entity-resolution/src/main/scala/scholarchain/er"

  private def sources(name: String): Vector[(Path, String)] =
    val dir = Vector(
      Paths.get(relative, name),
      Paths.get("src/main/scala/scholarchain/er", name)
    )
      .find(Files.isDirectory(_))
      .getOrElse(fail(s"cannot locate the $name package sources"))
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .toVector
      .sortBy(_.toString)
      .map(p => p -> Files.readString(p, UTF_8))

  private def mentions(name: String, forbidden: Vector[String]) =
    for
      (path, text) <- sources(name)
      word <- forbidden
      if text.contains(word)
    yield s"${path.getFileName} mentions '$word'"

  private val newPackages = Vector("pairs", "sampling", "eval/labeling")

  test("the new packages have sources to scan") {
    assertEquals(
      newPackages.map(sources(_).nonEmpty),
      Vector(true, true, true)
    )
  }

  test("matching and adjudication code never reference the new packages") {
    val forbidden =
      newPackages.map(p => "scholarchain.er." + p.replace('/', '.'))
    Vector("matching", "llm").foreach { name =>
      assertEquals(mentions(name, forbidden), Vector.empty[String])
    }
  }

  test("the new packages do not read matcher output") {
    val forbidden = Vector(
      "Matcher",
      "Policy",
      "ResolutionRecord",
      "MatchDecision",
      "MatchOutcome",
      "MatchPath",
      "scholarchain.er.llm",
      "scholarchain.er.review"
    )
    newPackages.foreach { name =>
      assertEquals(mentions(name, forbidden), Vector.empty[String])
    }
  }

  test("the compiled matcher signatures take no export or labeling types") {
    def typesOf(cls: Class[?]): Set[String] =
      val methods = cls.getDeclaredMethods.toVector.flatMap(m =>
        m.getReturnType +: m.getParameterTypes.toVector
      )
      (methods ++ cls.getDeclaredConstructors.toVector.flatMap(
        _.getParameterTypes
      )).map(_.getName).toSet
    val offending = Vector(classOf[Matcher], classOf[Policy])
      .flatMap(typesOf)
      .filter(name =>
        newPackages.exists(p =>
          name.startsWith("scholarchain.er." + p.replace('/', '.'))
        )
      )
    assertEquals(offending, Vector.empty[String])
  }
