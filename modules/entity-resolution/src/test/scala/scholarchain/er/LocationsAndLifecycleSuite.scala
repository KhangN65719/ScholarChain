package scholarchain.er

import java.net.{InetAddress, InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Instant
import scholarchain.core.ContractSchema
import scholarchain.er.Fixtures.*
import scholarchain.er.eval.labeling.{LabelServer, LabelSession}
import scholarchain.er.matching.EntityKind
import scholarchain.er.pairs.{Locations, PairItem, PairSide, PairsFile}

class LocationsAndLifecycleSuite extends munit.FunSuite:
  private def repo(): Path =
    val root = Files.createTempDirectory("er-repo")
    Files.writeString(root.resolve("build.sbt"), "", UTF_8)
    Files.createDirectories(root.resolve("modules"))
    root

  test("a path inside the repository is refused") {
    val root = repo()
    val message = Locations.violation(root.resolve("gold/pairs.jsonl"), root)
    assert(message.exists(_.contains("repository")), message.toString)
  }

  test("the repository is found from a subdirectory of the working directory") {
    val root = repo()
    val inside = root.resolve("modules")
    assert(Locations.violation(root.resolve("gold"), inside).isDefined)
    assertEquals(Locations.repoRoot(inside), Some(root.toAbsolutePath))
  }

  test("a path under a target directory is refused wherever it is") {
    val root = repo()
    val elsewhere = Files.createTempDirectory("er-else")
    val message =
      Locations.violation(elsewhere.resolve("target/gold.jsonl"), root)
    assert(message.exists(_.contains("target")), message.toString)
  }

  test("a path outside the repository and target is allowed") {
    val root = repo()
    val outside = Files.createTempDirectory("er-data")
    assertEquals(Locations.violation(outside.resolve("gold.jsonl"), root), None)
  }

  test("a sibling whose name only starts like the repository is allowed") {
    val parent = Files.createTempDirectory("er-parent")
    val root = parent.resolve("repo")
    Files.createDirectories(root)
    Files.writeString(root.resolve("build.sbt"), "", UTF_8)
    assertEquals(
      Locations.violation(parent.resolve("repo-data/gold.jsonl"), root),
      None
    )
  }

  test("a path inside a protected input directory is refused") {
    val root = repo()
    val outside = Files.createTempDirectory("er-data")
    val candidates = outside.resolve("match-candidates")
    val message = Locations.violation(
      candidates.resolve("v1/new.jsonl"),
      root,
      Vector(candidates)
    )
    assert(message.exists(_.contains("input")), message.toString)
    assertEquals(
      Locations.violation(outside.resolve("out"), root, Vector(candidates)),
      None
    )
  }

  test("interrupting the labeling server's wait stops it and frees the port") {
    val dir = Files.createTempDirectory("er-serve")
    val a = mention("p01a", "Jane Doe")
    val b = mention("p01b", "Jane Doe")
    PairsFile.write(
      dir.resolve("pairs.jsonl"),
      Vector(
        PairItem(
          ContractSchema.currentVersion,
          PairItem.idOf(EntityKind.Researcher, a.id, b.id),
          EntityKind.Researcher,
          runId,
          PairItem.exportVersion,
          PairSide.of(a),
          PairSide.of(b)
        )
      )
    )
    val session = LabelSession
      .open(
        dir.resolve("pairs.jsonl"),
        dir.resolve("gold.jsonl"),
        None,
        "Reviewer One",
        () => Instant.parse("2026-11-07T10:00:00Z")
      )
      .fold(message => fail(message), identity)
    val server = new LabelServer(session, 0, "token")
    server.start()
    val port = server.boundPort
    val waiter = new Thread(() => LabelServer.serveUntilInterrupted(server))
    waiter.start()
    waiter.interrupt()
    waiter.join(10000)
    assert(!waiter.isAlive, "the wait did not end")
    val socket = new Socket()
    try
      intercept[java.io.IOException](
        socket.connect(
          new InetSocketAddress(InetAddress.getLoopbackAddress, port),
          1000
        )
      )
    finally socket.close()
  }
