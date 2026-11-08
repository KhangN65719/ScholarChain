package scholarchain.tests.adversarial

import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** The human labeling tool is a loopback-only page. It may use the JDK HTTP
  * server and `java.net` only to bind 127.0.0.1 and to decode query strings. It
  * must never open an outbound connection, bind a wildcard address or name a
  * remote host. The sampling exporter is a plain file tool with no network at
  * all.
  */
class AdversarialErLabelingSuite extends FunSuite:
  private val er =
    Paths.get("modules", "entity-resolution", "src", "main", "scala")
  private val erPkg = er.resolve(Paths.get("scholarchain", "er"))

  private def scala(dir: Path): List[Path] =
    if !Files.isDirectory(dir) then Nil
    else
      Files
        .walk(dir)
        .iterator
        .asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .toList

  private val labelingFiles = scala(
    erPkg.resolve(Paths.get("eval", "labeling"))
  )
  private val exporterFiles =
    scala(erPkg.resolve("sampling")) ++ scala(erPkg.resolve("pairs"))

  private val outbound = Vector(
    "HttpClient",
    "java.net.http",
    "openConnection",
    "URLConnection",
    "HttpURLConnection",
    "okhttp",
    "sttp",
    "requests.",
    "scalaj",
    "ServerSocket",
    "DatagramSocket",
    "SocketChannel",
    "new Socket",
    "InetAddress.getByName",
    "InetAddress.getAllByName",
    "sun.net.www",
    "XMLHttpRequest",
    "WebSocket",
    "sendBeacon"
  )

  private val allowedHosts = Set("127.0.0.1", "localhost")
  private val allowedJavaNet =
    Set("InetAddress", "InetSocketAddress", "URI", "URLDecoder")

  private val hostPattern = """(?i)(?:https?|wss?|ftp)://([^/:"'\s$?#\\)]+)""".r
  private val netPattern = """java\.net\.(?:\{([^}]*)\}|([A-Za-z_.*]+))""".r

  private[adversarial] def outboundHits(text: String): List[String] =
    outbound.filter(text.contains).toList

  private[adversarial] def foreignHosts(text: String): List[String] =
    hostPattern
      .findAllMatchIn(text)
      .map(_.group(1).toLowerCase)
      .filterNot(allowedHosts)
      .toList

  private[adversarial] def javaNetUses(text: String): List[String] =
    netPattern
      .findAllMatchIn(text)
      .flatMap { m =>
        Option(m.group(1)) match
          case Some(list) => list.split(",").map(_.trim).filter(_.nonEmpty)
          case None       => Array(m.group(2))
      }
      .map(_.takeWhile(_ != '.'))
      .toList

  private[adversarial] def unsafeBinds(text: String): List[String] =
    val marker = "InetSocketAddress("
    Iterator
      .iterate(text.indexOf(marker))(i => text.indexOf(marker, i + 1))
      .takeWhile(_ >= 0)
      .map(i => text.substring(i + marker.length).take(80))
      .filterNot { args =>
        args.contains("getLoopbackAddress") ||
        args.contains("\"127.0.0.1\"") || args.contains("\"localhost\"")
      }
      .toList

  private def wildcard(text: String): Boolean =
    text.contains("0.0.0.0") || text.contains("\"[::]\"") ||
      text.contains("getLocalHost") || text.contains("anyLocalAddress") ||
      text.contains("HttpServer.create()")

  test("scanner self-check: it flags what it must and passes loopback use") {
    assertEquals(outboundHits("val c = HttpClient.newHttpClient()").size, 1)
    assertEquals(outboundHits("u.openConnection()").size, 1)
    assertEquals(
      foreignHosts("fetch('https://evil.example/x')"),
      List("evil.example")
    )
    assertEquals(foreignHosts("http://127.0.0.1:$p/?t=$t"), Nil)
    assertEquals(foreignHosts("http://localhost:8080/"), Nil)
    assertEquals(
      javaNetUses("import java.net.{InetAddress, URI}"),
      List("InetAddress", "URI")
    )
    assertEquals(
      javaNetUses("java.net.URLDecoder.decode(a, b)"),
      List("URLDecoder")
    )
    assertEquals(unsafeBinds("new InetSocketAddress(8765)").size, 1)
    assertEquals(unsafeBinds("new InetSocketAddress(\"0.0.0.0\", 1)").size, 1)
    assertEquals(
      unsafeBinds("new InetSocketAddress(InetAddress.getLoopbackAddress, p)"),
      Nil
    )
    assert(wildcard("bind(\"0.0.0.0\")"))
    assert(!wildcard("InetAddress.getLoopbackAddress"))
  }

  test("the labeling and exporter sources were found") {
    assert(labelingFiles.exists(_.getFileName.toString == "LabelServer.scala"))
    assert(exporterFiles.nonEmpty)
  }

  test("the labeling tool opens no outbound connection") {
    val hits = for
      f <- labelingFiles
      w <- outboundHits(Files.readString(f))
    yield s"$f: $w"
    assertEquals(hits, Nil)
  }

  test("the labeling tool names no host other than loopback") {
    val hits = for
      f <- labelingFiles
      h <- foreignHosts(Files.readString(f))
    yield s"$f: $h"
    assertEquals(hits, Nil)
  }

  test(
    "the labeling tool uses java.net only for loopback binding and decoding"
  ) {
    val hits = for
      f <- labelingFiles
      n <- javaNetUses(Files.readString(f))
      if !allowedJavaNet.contains(n)
    yield s"$f: java.net.$n"
    assertEquals(hits, Nil)
  }

  test("every server bind in the labeling tool is the loopback address") {
    val all = labelingFiles.map(f => f -> Files.readString(f))
    val server = all.filter(_._2.contains("HttpServer"))
    assert(
      server.nonEmpty,
      "no HttpServer found; the bind check proved nothing"
    )
    server.foreach { (f, text) =>
      assert(
        text.contains("InetSocketAddress("),
        s"$f binds no explicit address"
      )
    }
    val hits = for
      (f, text) <- all
      b <- unsafeBinds(text)
    yield s"$f: $b"
    assertEquals(hits, Nil)
    val wild = all.collect { case (f, text) if wildcard(text) => f.toString }
    assertEquals(wild, Nil)
  }

  test("the labeling page loads no remote script, style, image or frame") {
    val page = labelingFiles.filter(_.getFileName.toString == "LabelPage.scala")
    assert(page.nonEmpty)
    page.foreach { f =>
      val text = Files.readString(f)
      assertEquals(foreignHosts(text), Nil)
      assert(!text.contains("src=\"//"), f.toString)
      assert(
        !"""(?i)<(script|link|img|iframe)[^>]+(src|href)=["']?https?:""".r
          .findFirstIn(text)
          .isDefined
      )
    }
  }

  test("the exporter reaches no network at all") {
    val hits = for
      f <- exporterFiles
      text = Files.readString(f)
      w <- outboundHits(
        text
      ) ++ (if text.contains("java.net") then List("java.net") else Nil)
    yield s"$f: $w"
    assertEquals(hits, Nil)
  }
