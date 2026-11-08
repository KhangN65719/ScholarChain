package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scholarchain.er.llm.LlmAdjudicator
import scholarchain.er.matching.{Matcher, Policy}

/** Scans that keep gold labels, suggestions and evaluation tooling out of the
  * matching and adjudication packages. Three layers, each with planted
  * violations so a scan that stops seeing a form fails here:
  *
  *   - a structural scan of source text: every nested directory, import
  *     statements joined across lines, brace, rename and wildcard imports of
  *     `scholarchain.er`, relative references through `er`, chained package
  *     clauses and bare sibling members;
  *   - a vocabulary scan: any mention of gold or suggestion words;
  *   - a compiled-signature scan of the matcher, policy and adjudicator.
  *
  * Limits: the source scans read text, so a reference built by reflection from
  * string pieces would not be seen; the class-byte scan in the independent
  * `tests/` suite covers constant-pool strings. The scans fail if they find no
  * sources, so they cannot pass vacuously.
  */
class LeakageSuite extends munit.FunSuite:
  private val relative =
    "modules/entity-resolution/src/main/scala/scholarchain/er"

  private val protectedPackages = Vector("eval", "review", "sampling", "pairs")
  private val names = protectedPackages.mkString("(?:", "|", ")")
  private val decisionDirs = Vector("matching", "llm")

  private def p(regex: String): Pattern = Pattern.compile(regex)
  private def collapse(text: String): String = text.replaceAll("\\s+", " ")

  private def erRoot: Path =
    Vector(Paths.get(relative), Paths.get("src/main/scala/scholarchain/er"))
      .find(Files.isDirectory(_))
      .getOrElse(fail("cannot locate the entity-resolution sources"))

  /** Every Scala file under the directory, however deeply nested. */
  private def scalaFiles(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else
      val stream = Files.walk(dir)
      try
        stream.iterator.asScala
          .filter(f => Files.isRegularFile(f) && f.toString.endsWith(".scala"))
          .toVector
          .sortBy(_.toString)
      finally stream.close()

  private def sources(name: String): Vector[(Path, String)] =
    scalaFiles(erRoot.resolve(name)).map(f => f -> Files.readString(f, UTF_8))

  // ---- structural scan ----

  /** Import statements with continuation lines joined, whitespace collapsed. */
  private def importStatements(text: String): Vector[String] =
    val lines = text.linesIterator.toVector
    val out = Vector.newBuilder[String]
    var i = 0
    while i < lines.size do
      if lines(i).trim.startsWith("import ") then
        var statement = lines(i).trim
        def depth: Int = statement.count(_ == '{') - statement.count(_ == '}')
        var j = i
        while depth > 0 && j + 1 < lines.size do
          j += 1
          statement = statement + " " + lines(j).trim
        out += collapse(statement)
        i = j + 1
      else i += 1
    out.result()

  private val importRules: Vector[(String, Pattern)] = Vector(
    "brace import naming a protected package" ->
      p("\\ber ?\\. ?\\{.*\\b" + names + "\\b"),
    "wildcard import of the er package" ->
      p("\\ber ?\\. ?(?:\\*|_(?!\\w))"),
    "brace wildcard import of the er package" ->
      p("\\ber ?\\. ?\\{(?:[^{}]*,)? ?(?:\\*|_) ?[,}]"),
    "brace import from scholarchain including er" ->
      p("\\bscholarchain ?\\. ?\\{.*\\ber\\b"),
    "import of the bare er package" -> p("\\bscholarchain ?\\. ?er ?$"),
    "rename of the er package" -> p("\\ber (?:as|=>) "),
    "wildcard import of scholarchain" ->
      p("\\bscholarchain ?\\. ?(?:\\*|_(?!\\w))")
  )

  private val bodyRules: Vector[(String, Pattern)] = Vector(
    "fully qualified reference" ->
      p("(?<!\\w)scholarchain ?\\. ?er ?\\. ?" + names + "\\b"),
    "relative reference through er" ->
      p("(?<![\\w.])er ?\\. ?" + names + "\\b"),
    "chained package clause exposing siblings" ->
      p("\\bpackage scholarchain(?: ?\\. ?er)?\\b(?! ?\\.)"),
    "bare sibling package member" ->
      p("(?<![\\w.])" + names + "\\.(?:[A-Z{*]|_(?!\\w))")
  )

  /** Every way the text reaches a protected package, import or not. */
  private def sourceReferences(text: String): Vector[String] =
    val fromImports = for
      statement <- importStatements(text)
      (label, rule) <- importRules if rule.matcher(statement).find()
    yield s"$label: $statement"
    val whole = collapse(text)
    val fromBody = for
      (label, rule) <- bodyRules
      m = rule.matcher(whole) if m.find()
    yield s"$label: ${m.group()}"
    (fromImports ++ fromBody).distinct

  private def decisionReferences(root: Path): Vector[String] =
    decisionDirs.flatMap(d => scalaFiles(root.resolve(d))).flatMap { f =>
      sourceReferences(Files.readString(f, UTF_8)).map(r => s"$f: $r")
    }

  private val planted = Vector(
    "import scholarchain.er.eval.Gold",
    "import scholarchain.er.{eval as e}",
    "import scholarchain.er.{matching, review => r}",
    "import scholarchain.er.{\n  matching.Policy,\n  sampling.Stratifier\n}",
    "import scholarchain.er.eval.*",
    "import scholarchain.er.pairs.*",
    "import scholarchain.er.*",
    "import scholarchain.er.{*}",
    "import scholarchain.{er => x}",
    "import scholarchain.{core, er}",
    "import scholarchain.er as e",
    "import scholarchain.er",
    "import scholarchain.*",
    "import scholarchain._",
    "import _root_.scholarchain.er.review.ReviewQueue",
    "val x = scholarchain.er.pairs.Io.read(p)",
    "val x = _root_.scholarchain.er.sampling.Stratifier",
    "type G = scholarchain . er . eval . Gold",
    "package scholarchain.er\npackage matching",
    "val q = er.review.ReviewQueue()",
    "val g = eval.Gold.load(p)",
    "val s = sampling.Stratifier"
  )

  private val ordinary = Vector(
    "import scholarchain.er.matching.*",
    "import scholarchain.er.matching.{Policy, Names}",
    "import scholarchain.er.matching as m",
    "import scholarchain.core.{MatchDecision, Provenance}",
    "import scholarchain.core.pairing.MatchCandidate",
    "package scholarchain.er.matching",
    "package scholarchain.er.llm",
    "val review = 1",
    "val pairs = Vector(1)\nval n = pairs.map(_ + 1)",
    "val evaluate = pairs.size",
    "val reviewed = true"
  )

  test("the structural scan flags every planted reference form (control)") {
    planted.foreach { text =>
      assert(
        sourceReferences(text).nonEmpty,
        s"missed: ${text.replace("\n", "\\n")}"
      )
    }
  }

  test("the structural scan passes ordinary matching code (control)") {
    ordinary.foreach(text => assertEquals(sourceReferences(text), Vector.empty))
  }

  test("brace, rename, wildcard and qualified forms are named distinctly") {
    Map(
      "import scholarchain.er.{eval as e}" -> "brace import",
      "import scholarchain.er.eval.*" -> "fully qualified",
      "import scholarchain.er.*" -> "wildcard import of the er",
      "import scholarchain.{er => x}" -> "brace import from scholarchain",
      "val x = scholarchain.er.pairs.Io" -> "fully qualified"
    ).foreach { (text, label) =>
      assert(
        sourceReferences(text).exists(_.startsWith(label)),
        s"$text -> ${sourceReferences(text)}"
      )
    }
  }

  test("the scan walks nested directories (recursion control)") {
    val base = Files.createTempDirectory("er-leak")
    def write(rel: String, content: String): Unit =
      val file = base.resolve(rel)
      Files.createDirectories(file.getParent)
      Files.writeString(file, content)
    write("matching/Top.scala", "package scholarchain.er.matching\n")
    write("matching/a/b/Deep.scala", "import scholarchain.er.{eval as e}\n")
    write("llm/x/y/Nested.scala", "val g = scholarchain.er.review.Q\n")
    val found = decisionReferences(base)
    assertEquals(found.count(_.contains("Deep.scala")), 1, found.toString)
    assertEquals(found.count(_.contains("Nested.scala")), 1, found.toString)
    assert(!found.exists(_.contains("Top.scala")))
    assertEquals(scalaFiles(base.resolve("matching")).size, 2)
  }

  // ---- the real sources ----

  test("the matching and llm packages have sources to scan") {
    assert(
      sources("matching").size >= 7,
      sources("matching").map(_._1).toString
    )
    assert(sources("llm").nonEmpty)
  }

  test("matching and llm reach no eval, review, sampling or pairs package") {
    val found = decisionReferences(erRoot)
    assertEquals(found, Vector.empty[String], found.mkString("\n"))
  }

  // ---- vocabulary ----

  private def references(name: String, forbidden: Vector[String]) =
    for
      (path, text) <- sources(name)
      lowered = text.toLowerCase
      word <- forbidden
      if lowered.contains(word)
    yield s"${path.getFileName} mentions '$word'"

  private val forbiddenWords =
    Vector("gold", "suggest", "scholarchain.er.eval", "scholarchain.er.review")

  test(
    "the vocabulary scan sees a planted mention in a nested file (control)"
  ) {
    val base = Files.createTempDirectory("er-vocab")
    val nested = base.resolve("matching/deep/Leak.scala")
    Files.createDirectories(nested.getParent)
    Files.writeString(nested, "val g = loadGoldLabels()\n")
    val hit = scalaFiles(base.resolve("matching")).filter { f =>
      val text = Files.readString(f, UTF_8).toLowerCase
      forbiddenWords.exists(text.contains)
    }
    assertEquals(hit.map(_.getFileName.toString), Vector("Leak.scala"))
  }

  test("matching code does not reference gold labels or suggestions") {
    assertEquals(
      references("matching", forbiddenWords),
      Vector.empty[String]
    )
  }

  test("matching code does not depend on adjudication, which sits above it") {
    assertEquals(
      references("matching", Vector("scholarchain.er.llm")),
      Vector.empty[String]
    )
  }

  test(
    "adjudication code does not reference gold labels, suggestions or evaluation"
  ) {
    assertEquals(references("llm", forbiddenWords), Vector.empty[String])
  }

  // ---- compiled signatures ----

  test(
    "the compiled matcher and adjudicator signatures take no evaluation types"
  ) {
    def typesOf(cls: Class[?]): Set[String] =
      val methods = cls.getDeclaredMethods.toVector.flatMap(m =>
        m.getReturnType +: m.getParameterTypes.toVector
      )
      val constructors =
        cls.getDeclaredConstructors.toVector.flatMap(_.getParameterTypes)
      (methods ++ constructors).map(_.getName).toSet
    val offending =
      Vector(classOf[Matcher], classOf[Policy], classOf[LlmAdjudicator])
        .flatMap(typesOf)
        .filter(name =>
          protectedPackages
            .exists(pkg => name.startsWith(s"scholarchain.er.$pkg"))
        )
    assertEquals(offending, Vector.empty[String])
  }
