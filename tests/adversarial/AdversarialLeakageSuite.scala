package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Scans that keep evaluation labels, review tooling and every network client
  * out of the matching path. All of them are independent of the entity
  * resolution API: they read source text and compiled class bytes only.
  */
object LeakageScans:
  val erRoot: Path = Paths.get(
    "modules",
    "entity-resolution",
    "src",
    "main",
    "scala",
    "scholarchain",
    "er"
  )
  val mainRoot: Path = Paths.get("modules", "entity-resolution", "src", "main")
  val moduleTarget: Path = Paths.get("modules", "entity-resolution", "target")

  /** Packages that matching and llm must never reach. */
  val protectedPackages: Vector[String] =
    Vector("eval", "review", "sampling", "pairs")

  private val names = protectedPackages.mkString("(?:", "|", ")")

  private def p(regex: String): Pattern = Pattern.compile(regex)

  private def collapse(text: String): String = text.replaceAll("\\s+", " ")

  // ---- recursive file listing ----

  def scalaFiles(dir: Path, recursive: Boolean = true): List[Path] =
    if !Files.isDirectory(dir) then Nil
    else
      val stream = Files.walk(dir, if recursive then Int.MaxValue else 1)
      try
        stream.iterator.asScala
          .filter(f => Files.isRegularFile(f) && f.toString.endsWith(".scala"))
          .toList
          .sortBy(_.toString)
      finally stream.close()

  def subdirectories(dir: Path): List[String] =
    val stream = Files.list(dir)
    try
      stream.iterator.asScala
        .filter(Files.isDirectory(_))
        .map(_.getFileName.toString)
        .toList
        .sorted
    finally stream.close()

  // ---- source reference scan ----

  /** Import statements with continuation lines joined, whitespace collapsed. */
  def importStatements(text: String): List[String] =
    val lines = text.linesIterator.toVector
    val out = List.newBuilder[String]
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

  private val importRules: List[(String, Pattern)] = List(
    "brace import naming a protected package" ->
      p("\\ber ?\\. ?\\{.*\\b" + names + "\\b"),
    "wildcard import of the er package" ->
      p("\\ber ?\\. ?(?:\\*|_(?!\\w))"),
    "brace wildcard import of the er package" ->
      p("\\ber ?\\. ?\\{(?:[^{}]*,)? ?(?:\\*|_) ?[,}]"),
    "brace import from scholarchain including er" ->
      p("\\bscholarchain ?\\. ?\\{.*\\ber\\b"),
    "import of the bare er package" ->
      p("\\bscholarchain ?\\. ?er ?$"),
    "rename of the er package" -> p("\\ber (?:as|=>) "),
    "wildcard import of scholarchain" ->
      p("\\bscholarchain ?\\. ?(?:\\*|_(?!\\w))")
  )

  private val bodyRules: List[(String, Pattern)] = List(
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
  def sourceReferences(text: String): List[String] =
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

  // ---- gold, ground-truth and suggestion vocabulary ----

  private val vocabulary =
    "(?i)gold|ground[_ ]?truth|humanlabel|reviewedlabel|suggestion".r

  def offences(name: String, text: String): List[String] =
    text.linesIterator.zipWithIndex.flatMap { case (line, i) =>
      vocabulary
        .findFirstIn(line)
        .map(m => s"$name:${i + 1}: '$m' in: ${line.trim}")
    }.toList

  private val evalPackage: Path =
    Paths.get("scala", "scholarchain", "er", "eval")

  /** Exempt only the exact evaluation package, never a nested `eval`. */
  def isExemptEval(relativeToMain: Path): Boolean =
    relativeToMain.normalize.startsWith(evalPackage)

  def vocabularyOffences(main: Path): List[String] =
    scalaFiles(main).filterNot(f => isExemptEval(main.relativize(f))).flatMap {
      f => offences(f.toString, Files.readString(f))
    }

  /** Source references under the given decision packages of an `er` root,
    * walking every nested directory.
    */
  def decisionReferences(er: Path, dirs: Seq[String]): List[String] =
    dirs.toList.flatMap(d => scalaFiles(er.resolve(d))).flatMap { f =>
      sourceReferences(Files.readString(f)).map(r => s"$f: $r")
    }

  // ---- compiled class scan ----

  val classNeedles: Vector[String] =
    protectedPackages.flatMap(n =>
      Vector("scholarchain/er/" + n, "scholarchain.er." + n)
    )

  def classBytesHits(bytes: Array[Byte]): List[String] =
    val text = new String(bytes, StandardCharsets.ISO_8859_1)
    classNeedles.filter(text.contains).toList

  private def loudly(message: String): Nothing =
    throw new AssertionError(message)

  /** The compiled `scholarchain/er` directory of the module. Fails loudly. */
  def compiledEr(target: Path): Path =
    if !Files.isDirectory(target) then loudly(s"missing target $target")
    val candidates = subdirectories(target)
      .filter(_.startsWith("scala-"))
      .map(d => target.resolve(d).resolve("classes").resolve("scholarchain"))
      .map(_.resolve("er"))
      .filter(Files.isDirectory(_))
    candidates match
      case one :: Nil => one
      case Nil        => loudly(s"no compiled classes under $target")
      case many       => loudly(s"ambiguous class directories: $many")

  def classFiles(dir: Path): List[Path] =
    if !Files.isDirectory(dir) then loudly(s"missing class directory $dir")
    val stream = Files.walk(dir)
    val files =
      try
        stream.iterator.asScala
          .filter(f => Files.isRegularFile(f) && f.toString.endsWith(".class"))
          .toList
      finally stream.close()
    if files.isEmpty then loudly(s"no class files under $dir")
    files

  def classReferences(er: Path, dirs: Seq[String]): List[String] =
    dirs.toList.flatMap(d => classFiles(er.resolve(d))).flatMap { f =>
      classBytesHits(Files.readAllBytes(f)).map(h => s"$f: $h")
    }

  // ---- network scans ----

  private val strictWords = Vector(
    "java.net",
    "com.sun.net",
    "HttpClient",
    "HttpServer",
    "InetAddress",
    "Socket",
    "URLConnection",
    "okhttp",
    "sttp",
    "scalaj"
  )
  private val requestsClient = p("\\brequests\\.\\w")

  /** Any client, server or socket API at all. */
  def strictNetworkHits(text: String): List[String] =
    val words = strictWords.filter(text.contains).toList
    val requests =
      if requestsClient.matcher(text).find() then List("requests.") else Nil
    val url = if jvmUrl.matcher(text).find() then List("java.net.URL") else Nil
    words ++ requests ++ url

  private val looseWords =
    Vector("HttpClient", "URLConnection", "okhttp", "sttp", "scalaj")
  // The JVM URL class, not the browser's `new URL(...)` inside the page script.
  private val jvmUrl = p(
    "java\\.net\\.(?:URL(?![A-Za-z])|\\{[^}]*\\bURL\\b)|\\.openConnection\\(|\\.openStream\\("
  )
  private val rawSocket = p("(?<!Inet)Socket(?!Address)")
  private val resolution =
    p("InetAddress\\s*\\.\\s*(?:getByName|getAllByName|getLocalHost|ofLiteral)")
  private val urlHost = p("https?://([^/:?\\s\"'\\\\$]+)")
  private val loopbackHosts = Set("127.0.0.1", "localhost", "[::1]")

  private val socketAddressCall = p("InetSocketAddress\\s*\\(")
  private val loopbackMarks =
    Vector("getLoopbackAddress", "127.0.0.1", "localhost")

  /** The argument text of the call whose opening parenthesis ends at `start`,
    * across lines and nested parentheses. An unclosed call runs to the end.
    */
  private def argumentList(text: String, start: Int): String =
    var depth = 1
    var i = start
    while i < text.length && depth > 0 do
      text.charAt(i) match
        case '(' => depth += 1
        case ')' => depth -= 1
        case _   => ()
      i += 1
    text.substring(start, if depth == 0 then i - 1 else i)

  /** One finding per `InetSocketAddress(` call whose own argument list does not
    * bind loopback. A loopback mention elsewhere in the file does not count.
    */
  def unboundSocketAddresses(text: String): List[String] =
    val m = socketAddressCall.matcher(text)
    val found = List.newBuilder[String]
    while m.find() do
      val args = argumentList(text, m.end())
      if !loopbackMarks.exists(args.contains) then
        found += s"InetSocketAddress without loopback: (${collapse(args)})"
    found.result()

  /** The loopback allowance: a server bound to loopback is fine, an outbound
    * client or any non-loopback host is not.
    */
  def loopbackOnlyHits(text: String): List[String] =
    val words = looseWords.filter(text.contains).toList
    val requests =
      if requestsClient.matcher(text).find() then List("requests.") else Nil
    val socket = if rawSocket.matcher(text).find() then List("Socket") else Nil
    val url = if jvmUrl.matcher(text).find() then List("java.net.URL") else Nil
    val resolve =
      if resolution.matcher(text).find() then List("InetAddress resolution")
      else Nil
    val wildcard = if text.contains("0.0.0.0") then List("0.0.0.0") else Nil
    val tls = if text.contains("https://") then List("https://") else Nil
    val hosts =
      val m = urlHost.matcher(text)
      val found = List.newBuilder[String]
      while m.find() do
        if !loopbackHosts.contains(m.group(1)) then
          found += s"host ${m.group(1)}"
      found.result()
    val unbound = unboundSocketAddresses(text)
    words ++ requests ++ socket ++ url ++ resolve ++ wildcard ++ tls ++ hosts ++
      unbound

  def networkHits(
      files: List[Path],
      scan: String => List[String]
  ): List[String] =
    files.flatMap(f => scan(Files.readString(f)).map(h => s"$f: $h"))

class AdversarialLeakageSuite extends FunSuite:
  import LeakageScans.*

  private val decisionDirs = Vector("matching", "llm")

  private def decisionSources: List[Path] =
    decisionDirs.toList.flatMap(d => scalaFiles(erRoot.resolve(d)))

  private def tempDir(): Path = Files.createTempDirectory("leakage-scan")

  private def write(base: Path, relative: String, content: String): Path =
    val file = base.resolve(relative)
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  // ---- positive controls: the source reference scan ----

  private val violations = Vector(
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

  private val clean = Vector(
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

  test("source scan flags every planted reference form (positive control)") {
    violations.foreach { text =>
      assert(
        sourceReferences(text).nonEmpty,
        s"missed: ${text.replace("\n", "\\n")}"
      )
    }
  }

  test("source scan passes ordinary matching code (negative control)") {
    clean.foreach { text =>
      assertEquals(sourceReferences(text), Nil, text)
    }
  }

  test("brace, rename, wildcard and qualified forms are named distinctly") {
    val kinds = Map(
      "import scholarchain.er.{eval as e}" -> "brace import",
      "import scholarchain.er.eval.*" -> "fully qualified",
      "import scholarchain.er.*" -> "wildcard import of the er",
      "import scholarchain.{er => x}" -> "brace import from scholarchain",
      "val x = scholarchain.er.pairs.Io" -> "fully qualified"
    )
    kinds.foreach { case (text, label) =>
      assert(
        sourceReferences(text).exists(_.startsWith(label)),
        s"$text -> ${sourceReferences(text)}"
      )
    }
  }

  test("the scan walks nested directories (recursion control)") {
    val base = tempDir()
    write(base, "matching/Top.scala", "package scholarchain.er.matching\n")
    write(
      base,
      "matching/a/b/c/Deep.scala",
      "import scholarchain.er.{eval as e}\n"
    )
    write(
      base,
      "llm/x/y/Nested.scala",
      "val g = scholarchain.er.review.ReviewQueue\n"
    )
    val found = decisionReferences(base, decisionDirs)
    assertEquals(found.count(_.contains("Deep.scala")), 1, found.toString)
    assertEquals(found.count(_.contains("Nested.scala")), 1, found.toString)
    assert(!found.exists(_.contains("Top.scala")))
    assertEquals(
      scalaFiles(base.resolve("matching"), recursive = false).size,
      1
    )
    assertEquals(scalaFiles(base.resolve("matching")).size, 2)
  }

  // ---- the real matching and llm sources ----

  test("matching and llm sources exist, so the reference scan is not empty") {
    assert(decisionSources.exists(_.toString.contains("matching")))
    assert(decisionSources.exists(_.toString.contains("llm")))
    decisionDirs.foreach(d => assert(Files.isDirectory(erRoot.resolve(d)), d))
  }

  test("matching and llm reach no eval, review, sampling or pairs package") {
    val found = decisionReferences(erRoot, decisionDirs)
    assertEquals(found, Nil, found.mkString("\n"))
  }

  private val labelingTypes = Vector(
    "scholarchain.er.eval",
    "scholarchain.er.labeling",
    "scholarchain.er.sampling",
    "scholarchain.er.review",
    "GoldLabel",
    "GoldEntry",
    "GoldSet",
    "LabelSession",
    "LabelServer",
    "LabelPage",
    "LabelNames",
    "SuggestionSet",
    "PairsExport",
    "Stratifier",
    "ReviewQueue",
    "Evaluator"
  )

  test("matching and llm never name eval, labeling or exporter types") {
    val found = for
      p <- decisionSources
      text = Files.readString(p)
      word <- labelingTypes if text.contains(word)
    yield s"$p: $word"
    assertEquals(found, Nil, found.mkString("\n"))
  }

  test("the package layout is the one the scans know about") {
    assertEquals(
      subdirectories(erRoot),
      List("eval", "llm", "matching", "pairs", "review", "sampling")
    )
    assertEquals(
      subdirectories(erRoot.resolve("eval")),
      List("assess", "labeling", "validation")
    )
  }

  // ---- vocabulary scan: exemption is the exact eval package only ----

  test("only scholarchain/er/eval is exempt, never a nested eval (control)") {
    val ok =
      Paths.get("scala", "scholarchain", "er", "eval", "labeling", "A.scala")
    assert(isExemptEval(ok))
    Vector(
      Paths.get("scala", "scholarchain", "er", "matching", "eval", "A.scala"),
      Paths.get("scala", "scholarchain", "er", "llm", "eval", "A.scala"),
      Paths.get("scala", "scholarchain", "er", "evaluation", "A.scala"),
      Paths.get("scala", "scholarchain", "er", "eval2", "A.scala"),
      Paths.get("scala", "scholarchain", "er", "matching", "A.scala"),
      Paths.get("scala", "other", "eval", "A.scala")
    ).foreach(rel => assert(!isExemptEval(rel), rel.toString))
  }

  test("vocabulary scan flags a planted file in a nested eval (control)") {
    val base = tempDir()
    write(
      base,
      "scala/scholarchain/er/matching/eval/Leak.scala",
      "val g = loadGoldLabels()\n"
    )
    write(
      base,
      "scala/scholarchain/er/eval/Ok.scala",
      "val g = loadGoldLabels()\n"
    )
    write(
      base,
      "scala/scholarchain/er/matching/Clean.scala",
      "val score = weight * overlap\n"
    )
    val found = vocabularyOffences(base)
    assertEquals(found.size, 1, found.toString)
    assert(found.head.contains("Leak.scala"))
  }

  test("vocabulary scanner flags gold-set and suggestion references") {
    assertEquals(offences("x", "val g: GoldSet = ???").size, 1)
    assertEquals(offences("x", "import a.SuggestionRecord").size, 1)
    assertEquals(offences("x", "val ground_truth = 1").size, 1)
    assertEquals(offences("x", "val score = weight * overlap").size, 0)
  }

  test("matching sources reference no gold-set or suggestion vocabulary") {
    assert(scalaFiles(mainRoot).nonEmpty)
    val found = vocabularyOffences(mainRoot)
    assertEquals(found, Nil, found.mkString("\n"))
  }

  // ---- compiled class scan ----

  test("class scan flags planted constant-pool strings (control)") {
    def synthetic(ref: String): Array[Byte] =
      Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte, 0, 0) ++
        s"Lscholarchain/er/$ref/Thing;".getBytes(StandardCharsets.UTF_8)
    protectedPackages.foreach { n =>
      assertEquals(classBytesHits(synthetic(n + "/Gold")).size, 1, n)
    }
    assertEquals(
      classBytesHits(
        "Class.forName(\"scholarchain.er.eval.Gold\")".getBytes
      ).size,
      1
    )
    assertEquals(
      classBytesHits(
        "Lscholarchain/er/matching/Policy;Lscholarchain/core/Provenance;".getBytes
      ),
      Nil
    )
  }

  test("class scan walks nested directories and finds a planted class") {
    val base = tempDir()
    val bad = Array[Byte](1, 2, 3) ++
      "Lscholarchain/er/review/ReviewQueue;".getBytes(StandardCharsets.UTF_8)
    Files.createDirectories(base.resolve("matching/sub/dir"))
    Files.write(base.resolve("matching/sub/dir/Bad.class"), bad)
    Files.write(base.resolve("matching/Good.class"), Array[Byte](1, 2, 3))
    Files.createDirectories(base.resolve("llm"))
    Files.write(base.resolve("llm/Good.class"), Array[Byte](4, 5, 6))
    val found = classReferences(base, decisionDirs)
    assertEquals(found.size, 1, found.toString)
    assert(found.head.contains("Bad.class"))
  }

  test("class scan fails loudly on a missing or empty directory (control)") {
    val base = tempDir()
    intercept[AssertionError](classFiles(base.resolve("absent")))
    Files.createDirectories(base.resolve("empty"))
    intercept[AssertionError](classFiles(base.resolve("empty")))
    Files.createDirectories(base.resolve("noclasses"))
    Files.writeString(base.resolve("noclasses/A.scala"), "x")
    intercept[AssertionError](classFiles(base.resolve("noclasses")))
    intercept[AssertionError](compiledEr(base.resolve("no-target")))
    Files.createDirectories(base.resolve("target"))
    intercept[AssertionError](compiledEr(base.resolve("target")))
  }

  test("compiled matching and llm classes reference no protected package") {
    val er = compiledEr(moduleTarget)
    decisionDirs.foreach { d =>
      val classes = classFiles(er.resolve(d))
      val sources = scalaFiles(erRoot.resolve(d))
      assert(
        classes.size >= sources.size,
        s"$d: ${classes.size} classes for ${sources.size} sources, stale build?"
      )
    }
    val found = classReferences(er, decisionDirs)
    assertEquals(found, Nil, found.mkString("\n"))
  }

  test("the compiled scan can see the protected packages it guards") {
    val er = compiledEr(moduleTarget)
    protectedPackages.foreach { n =>
      assert(classFiles(er.resolve(n)).nonEmpty, n)
    }
  }

  // ---- network scans ----

  test("strict network scan flags every planted client (control)") {
    Vector(
      "import java.net.URI",
      "val c = HttpClient.newHttpClient()",
      "new java.net.Socket(host, 80)",
      "val s = new ServerSocket(0)",
      "import okhttp3.OkHttpClient",
      "import sttp.client3.*",
      "val r = requests.get(url)",
      "val c = url.openConnection().asInstanceOf[HttpURLConnection]; URLConnection",
      "val a = InetAddress.getLoopbackAddress",
      "val s = source.openStream()"
    ).foreach(text => assert(strictNetworkHits(text).nonEmpty, text))
    Vector("val x = 1", "// serves requests. Done", "val review = Vector.empty")
      .foreach(text => assertEquals(strictNetworkHits(text), Nil, text))
  }

  test("loopback scan allows a loopback server and flags the rest (control)") {
    val allowed =
      """val server = HttpServer.create(
        |  new InetSocketAddress(InetAddress.getLoopbackAddress, port), 0)
        |def url = s"http://127.0.0.1:$port/?t=$token"
        |val ok = host == s"localhost:$port"""".stripMargin
    assertEquals(loopbackOnlyHits(allowed), Nil)
    assertEquals(
      loopbackOnlyHits("const u = new URL(location.href); u.search"),
      Nil
    )
    Vector(
      "val c = HttpClient.newHttpClient()",
      "val u = new java.net.URL(\"http://127.0.0.1\")",
      "import java.net.{URI, URL}",
      "u.openStream()",
      "new Socket(\"127.0.0.1\", 80)",
      "new java.net.Socket()",
      "InetAddress.getByName(\"example.org\")",
      "val u = \"http://example.org/x\"",
      "val u = \"https://127.0.0.1/x\"",
      "new InetSocketAddress(8080)",
      "new InetSocketAddress(\"0.0.0.0\", 80)",
      "val r = requests.get(u)",
      "import okhttp3.*",
      "import sttp.client3.*",
      "val c: URLConnection = ???"
    ).foreach(text => assert(loopbackOnlyHits(text).nonEmpty, text))
  }

  test("loopback binding is checked per call, not per file (control)") {
    val mention = "val label = \"127.0.0.1\"\n"
    val wildcard = Vector(
      mention + "val a = new InetSocketAddress(8080)",
      mention + "val a = new InetSocketAddress(port)",
      mention + "val a = InetSocketAddress(host, port)",
      mention + "val a = new InetSocketAddress(\n  port\n)",
      mention + "val a = new InetSocketAddress(\n  hostName,\n  port)",
      mention + "val a = new InetSocketAddress ( InetAddress.anyLocal, 80)",
      "val a = new InetSocketAddress(InetAddress.getLoopbackAddress, 1)\n" +
        "val b = new InetSocketAddress(8080)",
      "val a = new InetSocketAddress(\"127.0.0.1\", 1)\n" +
        "val b = new InetSocketAddress(\n  \"example.org\",\n  80\n)"
    )
    wildcard.foreach { text =>
      assert(
        loopbackOnlyHits(text).exists(_.startsWith("InetSocketAddress")),
        text
      )
    }
    assertEquals(
      unboundSocketAddresses(wildcard.last).size,
      1,
      unboundSocketAddresses(wildcard.last).toString
    )
    assertEquals(
      unboundSocketAddresses(wildcard(wildcard.size - 2)).size,
      1
    )
    val bound = Vector(
      "new InetSocketAddress(InetAddress.getLoopbackAddress, port)",
      "new InetSocketAddress(\"127.0.0.1\", 0)",
      "new InetSocketAddress(\"localhost\", port)",
      "new InetSocketAddress(\n  InetAddress.getLoopbackAddress,\n  port\n)",
      "InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0)",
      "val a = new InetSocketAddress(\"127.0.0.1\", 1)\n" +
        "val b = new InetSocketAddress(InetAddress.getLoopbackAddress, 2)"
    )
    bound.foreach(text => assertEquals(unboundSocketAddresses(text), Nil, text))
  }

  private def networkSources(subdir: String, recursive: Boolean): List[Path] =
    val files = scalaFiles(erRoot.resolve(subdir), recursive)
    assert(files.nonEmpty, s"no sources under $subdir")
    files

  test(
    "matching, llm, eval/assess, eval/validation, review, pairs and sampling reach no network"
  ) {
    val groups = Vector(
      "matching" -> networkSources("matching", true),
      "llm" -> networkSources("llm", true),
      "eval/assess" -> networkSources("eval/assess", true),
      "eval/validation" -> networkSources("eval/validation", true),
      "review" -> networkSources("review", true),
      "pairs" -> networkSources("pairs", true),
      "sampling" -> networkSources("sampling", true)
    )
    val hits = groups.toList.flatMap { case (name, files) =>
      networkHits(files, strictNetworkHits).map(h => s"[$name] $h")
    }
    assertEquals(hits, Nil, hits.mkString("\n"))
  }

  test("the top level of eval reaches no network") {
    val files = networkSources("eval", false)
    assert(files.exists(_.getFileName.toString == "Evaluator.scala"))
    val hits = networkHits(files, strictNetworkHits)
    assertEquals(hits, Nil, hits.mkString("\n"))
  }

  test("eval/labeling is the only loopback exception and stays loopback-only") {
    val files = networkSources("eval/labeling", true)
    assert(files.exists(_.getFileName.toString == "LabelServer.scala"))
    val hits = networkHits(files, loopbackOnlyHits)
    assertEquals(hits, Nil, hits.mkString("\n"))
    val strict = networkHits(files, strictNetworkHits)
    assert(
      strict.forall(_.contains("LabelServer.scala")),
      s"network API outside LabelServer: ${strict.mkString("\n")}"
    )
  }
