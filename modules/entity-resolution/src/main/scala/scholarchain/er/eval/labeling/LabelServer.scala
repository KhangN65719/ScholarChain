package scholarchain.er.eval.labeling

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import scala.util.control.NonFatal
import scholarchain.er.pairs.{Cli, Locations, PairSide}

/** A local labeling page on the JDK's built-in HTTP server.
  *
  * The server binds to the loopback address only, rejects any request whose
  * Host header is not the loopback address, and requires a random per-run token
  * on every API call, so another page open in the same browser cannot label
  * pairs. It makes no network call and needs no credential. It serves the pairs
  * file and writes the gold file; it never reads matcher output.
  */
final class LabelServer(session: LabelSession, port: Int, val token: String):
  private val server =
    HttpServer.create(
      new InetSocketAddress(InetAddress.getLoopbackAddress, port),
      0
    )

  def boundPort: Int = server.getAddress.getPort
  def url: String = s"http://127.0.0.1:$boundPort/?t=$token"

  def start(): Unit =
    server.createContext("/", exchange => handle(exchange))
    server.start()

  def stop(): Unit = server.stop(0)

  private def send(
      exchange: HttpExchange,
      status: Int,
      contentType: String,
      body: String
  ): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", contentType)
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()

  private def json(exchange: HttpExchange, status: Int, body: ujson.Value) =
    send(exchange, status, "application/json; charset=utf-8", ujson.write(body))

  private def fail(exchange: HttpExchange, status: Int, message: String) =
    json(exchange, status, ujson.Obj("error" -> ujson.Str(message)))

  private def hostAllowed(exchange: HttpExchange): Boolean =
    Option(exchange.getRequestHeaders.getFirst("Host")).exists { host =>
      host == s"127.0.0.1:$boundPort" || host == s"localhost:$boundPort"
    }

  private def query(uri: URI): Map[String, String] =
    Option(uri.getRawQuery).toVector
      .flatMap(_.split("&").toVector)
      .flatMap { part =>
        part.split("=", 2) match
          case Array(key, value) =>
            Some(key -> java.net.URLDecoder.decode(value, UTF_8))
          case _ => None
      }
      .toMap

  private def body(exchange: HttpExchange): ujson.Value =
    val in = exchange.getRequestBody
    val bytes =
      try in.readNBytes(65536)
      finally in.close()
    ujson.read(new String(bytes, UTF_8))

  private def noteOf(input: ujson.Value): Option[String] =
    input.obj.get("note").collect { case ujson.Str(text) => text }

  private def handle(exchange: HttpExchange): Unit =
    try
      val path = exchange.getRequestURI.getPath
      val method = exchange.getRequestMethod
      if !hostAllowed(exchange) then fail(exchange, 403, "Host not allowed")
      else if method == "GET" && path == "/" then
        send(exchange, 200, "text/html; charset=utf-8", LabelPage.html)
      else if exchange.getRequestHeaders.getFirst("X-Label-Token") != token then
        fail(exchange, 403, "Missing or wrong token")
      else
        (method, path) match
          case ("GET", "/api/info") => json(exchange, 200, info)
          case ("GET", "/api/pair") =>
            val requested = query(exchange.getRequestURI)
              .get("i")
              .flatMap(_.toIntOption)
              .getOrElse(0)
            json(exchange, 200, view(requested))
          case ("POST", "/api/reveal") =>
            session.reveal(body(exchange)("pair_id").str)
            json(exchange, 200, ujson.Obj("ok" -> ujson.True))
          case ("POST", "/api/label") =>
            val input = body(exchange)
            LabelNames.parse(input("label").str) match
              case None =>
                fail(exchange, 400, s"label must be ${LabelNames.accepted}")
              case Some(verdict) =>
                reply(
                  exchange,
                  session.label(input("pair_id").str, verdict, noteOf(input))
                )
          case ("POST", "/api/accept") =>
            val input = body(exchange)
            reply(exchange, session.accept(input("pair_id").str, noteOf(input)))
          case _ => fail(exchange, 404, "Not found")
    catch
      case NonFatal(error) =>
        Console.err.println(s"request failed: $error")
        try fail(exchange, 400, "Bad request")
        catch case NonFatal(_) => ()

  private def reply(
      exchange: HttpExchange,
      result: Either[String, GoldEntry]
  ): Unit =
    result match
      case Left(message) => fail(exchange, 400, message)
      case Right(entry)  =>
        json(
          exchange,
          200,
          ujson.Obj(
            "ok" -> ujson.True,
            "label" -> ujson.Str(LabelNames.name(entry.label.verdict))
          )
        )

  private def optional(value: Option[String]): ujson.Value =
    value.map(v => ujson.Str(v): ujson.Value).getOrElse(ujson.Null)

  private def statsJson: ujson.Value =
    val s = session.stats
    ujson.Obj(
      "total" -> ujson.Num(s.total.toDouble),
      "labeled" -> ujson.Num(s.labeled.toDouble),
      "same" -> ujson.Num(s.same.toDouble),
      "different" -> ujson.Num(s.different.toDouble),
      "unsure" -> ujson.Num(s.unsure.toDouble),
      "with_suggestion" -> ujson.Num(s.withSuggestion.toDouble),
      "changed_from_suggestion" -> ujson.Num(s.changedFromSuggestion.toDouble)
    )

  private def info: ujson.Value =
    ujson.Obj(
      "reviewer" -> ujson.Str(session.reviewer),
      "subset" -> ujson.Bool(session.isSubset),
      "relabel_pass" -> ujson.Num(session.relabelPass.toDouble),
      "total" -> ujson.Num(session.size.toDouble),
      "first_unlabeled" -> ujson.Num(session.firstUnlabeled.toDouble),
      "suggestions_file" -> optional(session.suggestions.map(_.source.file)),
      "suggestions_sha256" -> optional(
        session.suggestions.map(_.source.sha256)
      ),
      "unknown_suggestion_pair_ids" -> ujson.Arr.from(
        session.suggestions.toVector
          .flatMap(_.unknownPairIds)
          .map(id => ujson.Str(id))
      ),
      "stats" -> statsJson
    )

  private def sideJson(side: PairSide): ujson.Value =
    ujson.Obj(
      "identifiers" -> ujson.Arr.from(
        side.identifiers.map(i =>
          ujson.Obj(
            "namespace" -> ujson.Str(i.namespace),
            "value" -> ujson.Str(i.value)
          )
        )
      ),
      "name" -> ujson.Str(side.name),
      "coauthors" -> ujson.Arr.from(side.coauthors.map(ujson.Str(_))),
      "venue" -> optional(side.venue),
      "year" -> side.year
        .map(y => ujson.Num(y.toDouble): ujson.Value)
        .getOrElse(ujson.Null),
      "affiliations" -> ujson.Arr.from(side.affiliations.map(ujson.Str(_))),
      "title" -> ujson.Str(side.title),
      "record_id" -> ujson.Str(side.recordId),
      "source" -> ujson.Str(side.source),
      "source_record_id" -> ujson.Str(side.sourceRecordId),
      "source_uri" -> ujson.Str(side.sourceUri),
      "snapshot_id" -> ujson.Str(side.snapshotId)
    )

  private def view(requested: Int): ujson.Value =
    val index = math.max(0, requested)
    session.pairAt(index) match
      case None =>
        ujson.Obj(
          "done" -> ujson.True,
          "total" -> ujson.Num(session.size.toDouble),
          "stats" -> statsJson
        )
      case Some(pair) =>
        val entry = session.entryOf(pair.pairId)
        val shown = session.shownSuggestion(pair.pairId)
        ujson.Obj(
          "done" -> ujson.False,
          "index" -> ujson.Num(index.toDouble),
          "total" -> ujson.Num(session.size.toDouble),
          "pair_id" -> ujson.Str(pair.pairId),
          "kind" -> ujson.Str(pair.kind.wire),
          "left" -> sideJson(pair.left),
          "right" -> sideJson(pair.right),
          "label" -> optional(entry.map(e => LabelNames.name(e.label.verdict))),
          "note" -> optional(entry.flatMap(_.label.notes)),
          "has_suggestion" -> ujson.Bool(session.hasSuggestion(pair.pairId)),
          "suggestion" -> shown
            .map(s =>
              ujson.Obj(
                "label" -> ujson.Str(LabelNames.name(s.label)),
                "reason" -> optional(s.reason)
              ): ujson.Value
            )
            .getOrElse(ujson.Null),
          "first_unlabeled" -> ujson.Num(session.firstUnlabeled.toDouble),
          "stats" -> statsJson
        )

object LabelServer:
  private val usage =
    "Usage: LabelServer --pairs <pairs.jsonl> --gold <gold.jsonl> " +
      "--reviewer <name> [--suggestions <file.csv>] [--port <n>]\n" +
      "       [--only <subset.txt> --carry-from <first-pass gold.jsonl>]\n" +
      "       [--relabel-pass 3 (with --carry-from <second-pass gold-v2.jsonl>)]\n" +
      "--only and --carry-from go together and start a blind second pass\n" +
      "over the subset; they exclude --suggestions. With --relabel-pass 3 the\n" +
      "carry file is a second-pass file: its pass 2 rows are carried and the\n" +
      "--only pairs are relabeled blind as pass 3.\n" +
      "The reviewer may also come from SCHOLARCHAIN_REVIEWER."

  private def newToken(): String =
    val bytes = new Array[Byte](16)
    new SecureRandom().nextBytes(bytes)
    HexFormat.of().formatHex(bytes)

  /** Blocks until the thread is interrupted, then stops the server. Under sbt,
    * Ctrl-C interrupts the thread that runs `main` and the JVM keeps living, so
    * without this the port would stay bound until sbt exited.
    */
  def serveUntilInterrupted(server: LabelServer): Unit =
    try new CountDownLatch(1).await()
    catch case _: InterruptedException => ()
    finally server.stop()

  private def outsideRepo(
      name: String,
      path: String
  ): Either[String, String] =
    Locations
      .check(Paths.get(path))
      .left
      .map(why => s"$name: $why")
      .map(_ => path)

  private def relabelPass(
      options: Map[String, String]
  ): Either[String, Int] =
    options.get("--relabel-pass") match
      case None        => Right(2)
      case Some("2")   => Right(2)
      case Some("3")   => Right(3)
      case Some(other) =>
        Left(s"--relabel-pass must be 2 or 3, not $other")

  /** `--only` and `--carry-from` together, never with `--suggestions`.
    * `--relabel-pass 3` blind-relabels the pairs of a second-pass file; without
    * it the run is the blind second pass, exactly as before.
    */
  def secondPass(
      options: Map[String, String]
  ): Either[String, Option[SecondPass]] =
    (options.get("--only"), options.get("--carry-from")) match
      case (None, None) if options.contains("--relabel-pass") =>
        Left("--relabel-pass needs --only and --carry-from")
      case (None, None)    => Right(None)
      case (Some(_), None) => Left("--only needs --carry-from")
      case (None, Some(_)) => Left("--carry-from needs --only")
      case _ if options.contains("--suggestions") =>
        Left("--only is blind and cannot be combined with --suggestions")
      case (Some(only), Some(carry)) =>
        for
          pass <- relabelPass(options)
          subsetPath <- outsideRepo("--only", only)
          carryPath <- outsideRepo("--carry-from", carry)
          ids <- SubsetFile.read(Paths.get(subsetPath))
        yield Some(SecondPass(ids, Paths.get(carryPath), pass))

  def main(args: Array[String]): Unit =
    val parsed = for
      options <- Cli.parse(args.toSeq)
      _ <- Cli
        .unknown(
          options,
          Set(
            "--pairs",
            "--gold",
            "--reviewer",
            "--suggestions",
            "--port",
            "--only",
            "--carry-from",
            "--relabel-pass"
          )
        )
        .toLeft(())
        .left
        .map(name => s"Unknown option $name")
      second <- secondPass(options)
      pairsArg <- options.get("--pairs").toRight("--pairs missing")
      goldArg <- options.get("--gold").toRight("--gold missing")
      pairs <- outsideRepo("--pairs", pairsArg)
      gold <- outsideRepo("--gold", goldArg)
      reviewer <- options
        .get("--reviewer")
        .orElse(sys.env.get("SCHOLARCHAIN_REVIEWER"))
        .toRight("--reviewer missing (or set SCHOLARCHAIN_REVIEWER)")
      port <- options
        .getOrElse("--port", "8765")
        .toIntOption
        .filter(p => p >= 0 && p <= 65535)
        .toRight("--port must be 0 to 65535")
      session <- LabelSession.open(
        Paths.get(pairs),
        Paths.get(gold),
        options.get("--suggestions").map(Paths.get(_)),
        reviewer,
        second = second
      )
    yield (session, port)
    parsed match
      case Left(message) =>
        Console.err.println(s"$message\n$usage")
        sys.exit(2)
      case Right((session, port)) =>
        val server = new LabelServer(session, port, newToken())
        server.start()
        session.suggestions.foreach { set =>
          println(
            s"Suggestions: ${set.source.file} (sha256 ${set.source.sha256}), " +
              s"${set.byPair.size} loaded"
          )
          if set.unknownPairIds.nonEmpty then
            println(
              s"WARNING: ${set.unknownPairIds.size} suggestion pair_id(s) are " +
                s"not in the pairs file and were not loaded: " +
                set.unknownPairIds.mkString(", ")
            )
        }
        session.carriedFrom.foreach { (file, sha) =>
          val kind =
            if session.relabelPass == 3 then "Blind pass 3"
            else "Blind second pass"
          println(
            s"$kind: ${session.size} subset pairs to label, " +
              s"${session.carriedCount} other pairs carried unchanged from " +
              s"$file (sha256 $sha). No suggestion, stratum or first-pass " +
              "label is shown."
          )
        }
        if session.suggestions.isEmpty && !session.isSubset then
          println("No suggestions loaded: rows will record no suggestion.")
        val of = if session.isSubset then " (subset)" else ""
        println(
          s"Resuming at pair ${session.firstUnlabeled + 1} of ${session.size}$of"
        )
        println(s"Open this address in a browser: ${server.url}")
        println("Press Ctrl-C to stop. Labels are saved after every answer.")
        Runtime.getRuntime.addShutdownHook(
          new Thread(() => println(session.stats.summary))
        )
        serveUntilInterrupted(server)
        println(session.stats.summary)
