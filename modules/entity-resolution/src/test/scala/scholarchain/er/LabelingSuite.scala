package scholarchain.er

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scholarchain.core.ContractSchema
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.{GoldFile, GoldLabel, Verdict}
import scholarchain.er.eval.labeling.*
import scholarchain.er.matching.{EntityKind, SideRecord}
import scholarchain.er.pairs.{
  AtomicFile,
  Hashing,
  PairItem,
  PairSide,
  PairsFile
}

class LabelingSuite extends munit.FunSuite:
  private val fixedClock = () => Instant.parse("2026-11-07T10:00:00Z")

  private def pairItem(
      i: Int,
      rightSnapshot: Option[String] = None
  ): PairItem =
    val a: SideRecord = mention(f"p$i%02da", "Jane Doe")
    val b0: SideRecord = mention(f"p$i%02db", "Jane Doe")
    val b = rightSnapshot.fold(b0)(s => b0.copy(snapshotId = s))
    PairItem(
      ContractSchema.currentVersion,
      PairItem.idOf(EntityKind.Researcher, a.id, b.id),
      EntityKind.Researcher,
      runId,
      PairItem.exportVersion,
      PairSide.of(a),
      PairSide.of(b)
    )

  private val items = (1 to 5).toVector.map(pairItem(_))

  private case class Workspace(dir: Path, pairs: Path, gold: Path):
    def csv(text: String, name: String = "sugg.csv"): Path =
      val path = dir.resolve(name)
      Files.writeString(path, text, UTF_8)
      path

  private def workspace(
      pairs: Vector[PairItem] = items
  ): Workspace =
    val dir = Files.createTempDirectory("er-label")
    val pairsPath = dir.resolve("pairs.jsonl")
    PairsFile.write(pairsPath, pairs)
    Workspace(dir, pairsPath, dir.resolve("gold.jsonl"))

  private def open(
      w: Workspace,
      suggestions: Option[Path] = None,
      reviewer: String = "Reviewer One"
  ): LabelSession =
    LabelSession
      .open(w.pairs, w.gold, suggestions, reviewer, fixedClock)
      .fold(message => fail(message), identity)

  private def suggestionCsv(rows: (PairItem, String, String)*): String =
    "pair_id,suggested_label,reason\n" +
      rows
        .map((p, label, why) => s"""${p.pairId},$label,"$why"""")
        .mkString("\n") +
      "\n"

  test("suggestions are hidden by default and shown only after reveal") {
    val w = workspace()
    val session =
      open(w, Some(w.csv(suggestionCsv((items(0), "same", "shared ORCID")))))
    val id = items(0).pairId
    assert(session.hasSuggestion(id))
    assertEquals(session.shownSuggestion(id), None)
    assertEquals(session.reveal(id).map(_.label), Some(Verdict.Match))
    assertEquals(
      session.shownSuggestion(id).flatMap(_.reason),
      Some("shared ORCID")
    )
    assertEquals(session.shownSuggestion(items(1).pairId), None)
  }

  test("accepting works only when a suggestion exists and is revealed") {
    val w = workspace()
    val session = open(w, Some(w.csv(suggestionCsv((items(0), "same", "r")))))
    assert(session.accept(items(1).pairId, None).isLeft, "no suggestion")
    assert(session.accept(items(0).pairId, None).isLeft, "not revealed")
    assertEquals(session.stats.labeled, 0)
    session.reveal(items(0).pairId)
    val entry = session.accept(items(0).pairId, None).toOption.get
    assertEquals(entry.label.verdict, Verdict.Match)
    assert(!entry.changedFromSuggestion)
    val bare = open(workspace())
    assert(bare.accept(items(0).pairId, None).isLeft, "no csv at all")
  }

  test("1, 2 and 3 override the suggestion and are counted as changes") {
    val w = workspace()
    val csv = suggestionCsv(
      (items(0), "same", "a"),
      (items(1), "same", "b"),
      (items(2), "different", "c")
    )
    val session = open(w, Some(w.csv(csv)))
    val overrides = Vector(
      (items(0), Verdict.NonMatch),
      (items(1), Verdict.Match),
      (items(2), Verdict.Unsure),
      (items(3), Verdict.Match)
    )
    overrides.foreach((p, v) => session.label(p.pairId, v, None).toOption.get)
    val stats = session.stats
    assertEquals(stats.changedFromSuggestion, 2)
    assertEquals(stats.withSuggestion, 3)
    assertEquals(
      (stats.labeled, stats.same, stats.different, stats.unsure),
      (4, 2, 1, 1)
    )
    assert(
      !session.entryOf(items(3).pairId).get.changedFromSuggestion,
      "no suggestion, nothing to change"
    )
  }

  test("a gold line records the suggestion, its source, reviewer and note") {
    val w = workspace()
    val csvPath = w.csv(suggestionCsv((items(0), "same", "why, with comma")))
    val session = open(w, Some(csvPath))
    session.label(items(0).pairId, Verdict.NonMatch, Some("  looks different "))
    val line = ujson.read(Files.readAllLines(w.gold).get(0))
    assertEquals(line("pair_id").str, items(0).pairId)
    assertEquals(line("suggested_label").str, "same")
    assertEquals(line("suggestion_reason").str, "why, with comma")
    assertEquals(line("suggestion_source_file").str, "sugg.csv")
    assertEquals(
      line("suggestion_source_sha256").str,
      Hashing.sha256Bytes(Files.readAllBytes(csvPath))
    )
    assertEquals(line("changed_from_suggestion").bool, true)
    assertEquals(line("reviewMethod").str, "human")
    assertEquals(line("reviewer").str, "Reviewer One")
    assertEquals(line("reviewedAt").str, "2026-11-07T10:00:00Z")
    assertEquals(line("left_snapshot_id").str, "snap-1")
  }

  test("the gold file is accepted by eval.GoldFile and GoldLabel") {
    val w = workspace()
    val session = open(w)
    session.label(items(0).pairId, Verdict.Match, None)
    session.label(items(1).pairId, Verdict.NonMatch, Some("n"))
    val labels = GoldFile.read(w.gold)
    assertEquals(labels.map(_.itemId), items.take(2).map(_.pairId))
    labels.foreach { label =>
      assertEquals(label.reviewMethod, GoldLabel.HumanMethod)
      assertEquals(label.reviewer, "Reviewer One")
      assert(label.leftSourceRecordId.nonEmpty && label.snapshotId.nonEmpty)
    }
    assertEquals(labels(1).notes, Some("n"))
  }

  test("differing snapshots are both kept and joined for GoldLabel") {
    val mixed = Vector(pairItem(1, Some("snap-2")))
    val w = workspace(mixed)
    open(w).label(mixed(0).pairId, Verdict.Match, None).toOption.get
    assertEquals(GoldFile.read(w.gold).head.snapshotId, "snap-1+snap-2")
    val line = ujson.read(Files.readAllLines(w.gold).get(0))
    assertEquals(line("right_snapshot_id").str, "snap-2")
  }

  test("every answer is saved at once and a reopened session resumes") {
    val w = workspace()
    val first = open(w)
    assertEquals(first.firstUnlabeled, 0)
    first.label(items(0).pairId, Verdict.Match, None)
    first.label(items(1).pairId, Verdict.NonMatch, None)
    assertEquals(Files.readAllLines(w.gold).size, 2)
    val resumed = open(w)
    assertEquals(resumed.firstUnlabeled, 2)
    assertEquals(resumed.stats.labeled, 2)
    resumed.label(items(0).pairId, Verdict.Unsure, Some("revised"))
    assertEquals(Files.readAllLines(w.gold).size, 2)
    assertEquals(
      GoldFile.read(w.gold).head.verdict,
      Verdict.Unsure
    )
    assertEquals(resumed.firstUnlabeled, 2)
  }

  test("a failed write leaves the previous gold file and no temp files") {
    val w = workspace()
    val session = open(w)
    session.label(items(0).pairId, Verdict.Match, None)
    val before = Files.readString(w.gold, UTF_8)
    val broken = Seq("a").view.map[String](_ => throw RuntimeException("boom"))
    intercept[RuntimeException](AtomicFile.writeLines(w.gold, broken))
    assertEquals(Files.readString(w.gold, UTF_8), before)
    val names = Files.list(w.dir).iterator().asScala.map(_.getFileName.toString)
    assert(!names.exists(_.endsWith(".tmp")), "temporary file left behind")
  }

  test("a label whose save fails is not kept in memory") {
    val w = workspace()
    val blocked = w.dir.resolve("not-a-dir")
    Files.writeString(blocked, "x")
    val session = LabelSession
      .open(w.pairs, blocked.resolve("gold.jsonl"), None, "R", fixedClock)
      .toOption
      .get
    assert(session.label(items(0).pairId, Verdict.Match, None).isLeft)
    assertEquals(session.stats.labeled, 0)
  }

  test("a corrupt or foreign gold file is refused and left untouched") {
    val w = workspace()
    Files.writeString(w.gold, "{not json\n")
    val refused = LabelSession.open(w.pairs, w.gold, None, "R", fixedClock)
    assert(refused.isLeft)
    assertEquals(Files.readString(w.gold), "{not json\n")

    val other = workspace(Vector(pairItem(9)))
    open(other).label(pairItem(9).pairId, Verdict.Match, None)
    val foreign = LabelSession.open(w.pairs, other.gold, None, "R", fixedClock)
    assert(foreign.isLeft)
  }

  test("a blank reviewer or an empty pairs file is refused") {
    val w = workspace()
    assert(LabelSession.open(w.pairs, w.gold, None, "  ").isLeft)
    val empty = workspace(Vector.empty)
    assert(LabelSession.open(empty.pairs, empty.gold, None, "R").isLeft)
  }

  test("an over-long note is refused") {
    val session = open(workspace())
    assert(
      session.label(items(0).pairId, Verdict.Match, Some("x" * 2001)).isLeft
    )
    assert(session.label("no-such-pair", Verdict.Match, None).isLeft)
  }

  private def problems(text: String, known: Set[String] = Set.empty) =
    Suggestions.parse(text, SuggestionSource("s.csv", "sha"), known)

  test("malformed suggestion rows are refused with line numbers") {
    val result = problems(
      "pair_id,suggested_label,reason\n" +
        "a,same,ok\n" +
        "b,maybe,why\n" +
        "c,same\n" +
        ",same,blank id\n" +
        "a,different,dup\n"
    )
    val messages = result.left.toOption.get
    assert(messages.exists(m => m.startsWith("line 3") && m.contains("maybe")))
    assert(
      messages.exists(m => m.startsWith("line 4") && m.contains("3 columns"))
    )
    assert(messages.exists(m => m.startsWith("line 5") && m.contains("blank")))
    assert(messages.exists(_.contains("'a' appears more than once")))
  }

  test("a wrong header, an empty file and an open quote are refused") {
    assert(problems("id,label,why\n").left.toOption.get.head.contains("header"))
    assert(problems("").isLeft)
    val open =
      problems("pair_id,suggested_label,reason\na,same,\"never closed\n")
    assert(open.left.toOption.get.head.contains("never closed"))
  }

  test("unknown pair IDs are reported, not silently dropped") {
    val set = problems(
      "pair_id,suggested_label,reason\nk,same,\nz,different,\ny,unsure,\n",
      Set("k")
    ).toOption.get
    assertEquals(set.byPair.keySet, Set("k"))
    assertEquals(set.unknownPairIds, Vector("y", "z"))
  }

  test("quoted fields, accepted aliases, a BOM and CRLF line ends are read") {
    val set = problems(
      "﻿pair_id,suggested_label,reason\r\n" +
        "k,MATCH,\"a, \"\"b\"\"\r\nsecond line\"\r\n" +
        "m,non-match,\r\n",
      Set("k", "m")
    ).toOption.get
    assertEquals(set.byPair("k").label, Verdict.Match)
    assertEquals(set.byPair("k").reason, Some("a, \"b\"\nsecond line"))
    assertEquals(set.byPair("m").label, Verdict.NonMatch)
    assertEquals(set.byPair("m").reason, None)
  }

  test("a malformed CSV stops the session from opening") {
    val w = workspace()
    val bad = w.csv("pair_id,suggested_label,reason\nx,bogus,r\n")
    val result = LabelSession.open(w.pairs, w.gold, Some(bad), "R", fixedClock)
    assert(result.left.toOption.get.contains("line 2"))
    assert(!Files.exists(w.gold))
  }

  private def call(
      client: HttpClient,
      server: LabelServer,
      path: String,
      body: Option[String] = None,
      token: Option[String] = None
  ): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort}$path"))
    token.foreach(builder.header("X-Label-Token", _))
    val request = body match
      case Some(text) =>
        builder.POST(HttpRequest.BodyPublishers.ofString(text)).build()
      case None => builder.GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  test("the server needs its token, hides suggestions and saves labels") {
    val w = workspace()
    val session =
      open(w, Some(w.csv(suggestionCsv((items(0), "same", "SECRETREASON")))))
    val server = new LabelServer(session, 0, "tok")
    server.start()
    try
      val client = HttpClient.newHttpClient()
      val t = Some("tok")
      assertEquals(call(client, server, "/api/pair?i=0")._1, 403)
      assertEquals(
        call(client, server, "/api/pair?i=0", None, Some("bad"))._1,
        403
      )
      assertEquals(call(client, server, "/")._1, 200)
      val (status, shown) = call(client, server, "/api/pair?i=0", None, t)
      assertEquals(status, 200)
      assert(!shown.contains("SECRETREASON"), shown)
      assertEquals(ujson.read(shown)("has_suggestion").bool, true)
      assert(ujson.read(shown)("suggestion") == ujson.Null)
      val early = call(
        client,
        server,
        "/api/accept",
        Some(s"""{"pair_id":"${items(0).pairId}"}"""),
        t
      )
      assertEquals(early._1, 400)
      call(
        client,
        server,
        "/api/reveal",
        Some(s"""{"pair_id":"${items(0).pairId}"}"""),
        t
      )
      val revealed = call(client, server, "/api/pair?i=0", None, t)._2
      assert(revealed.contains("SECRETREASON"))
      val accepted = call(
        client,
        server,
        "/api/accept",
        Some(s"""{"pair_id":"${items(0).pairId}"}"""),
        t
      )
      assertEquals(accepted._1, 200)
      val labeled = call(
        client,
        server,
        "/api/label",
        Some(
          s"""{"pair_id":"${items(1).pairId}","label":"different","note":"n"}"""
        ),
        t
      )
      assertEquals(labeled._1, 200)
      assertEquals(
        GoldFile.read(w.gold).map(_.verdict),
        Vector(Verdict.Match, Verdict.NonMatch)
      )
      assertEquals(
        call(
          client,
          server,
          "/api/label",
          Some("""{"pair_id":"x","label":"maybe"}"""),
          t
        )._1,
        400
      )
    finally server.stop()
  }
