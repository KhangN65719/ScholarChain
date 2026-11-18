package scholarchain.evaluation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scholarchain.evaluation.factory.*

class FactoryCountsSuite extends FunSuite:
  private val mappingText = Vector(
    "# test mapping",
    "severity\thigh\tblocker",
    "severity\tmedium\tmajor",
    "severity\tlow\tminor",
    "alias\ttest\ttest-engineer",
    "file\t.*\t(^|/)[^/]*work-order[^/]*\\.md$\twork-order\tnone\t-\t-",
    "file\t.*\t(^|/)review[^/]*\\.md$\treview\tauto\tauto\t-",
    "file\t.*\t(^|/)human-commands[^/]*\\.md$\thuman-commands\tnone\t-\t-",
    "file\t.*\t(^|/)decisions[^/]*\\.md$\tdecision\tnone\t-\t-",
    "file\t.*\t(^|/)[^/]*report[^/]*\\.md$\treport\tnone\t-\t-",
    "file\t.*\t\\.md$\tevidence\tnone\t-\t-"
  )

  private def put(root: Path, rel: String, lines: Vector[String]): Unit =
    val p = root.resolve(rel)
    Files.createDirectories(p.getParent)
    Files.write(p, lines.mkString("", "\n", "\n").getBytes(UTF_8))

  private def world(): Path =
    val root = Files.createTempDirectory("runs-")
    put(root, "phase-01/work-order-01-build-engineer-x.md", Vector("# WO"))
    put(root, "phase-01/work-order-02-infra-engineer-y.md", Vector("# WO"))
    put(
      root,
      "phase-01/report-01-build-engineer.md",
      Vector(
        "**Status: BLOCKED-ON** infra-engineer",
        "The role says to report BLOCKED-ON when stuck.",
        "ownership violation: build-engineer wrote outside its write set",
        "no ownership violations were found elsewhere"
      )
    )
    put(
      root,
      "phase-01/review-round-1.md",
      Vector(
        "# Review",
        "## Major",
        "- **M1** build-engineer: formatting",
        "## Minor",
        "- m1 infra-engineer: stale doc",
        "The guard refused the command."
      )
    )
    put(
      root,
      "phase-01/review-round-2.md",
      Vector("## Minor", "- m2 build-engineer: nit")
    )
    put(root, "phase-01/repair-1.md", Vector("# repair"))
    put(
      root,
      "phase-01/human-commands.md",
      Vector("```bash", "sbt check", "```", "```", "ls", "```")
    )
    put(
      root,
      "phase-01/decisions.md",
      Vector("| P1-1 | ratified |", "| other | x |", "## D2 title")
    )
    put(root, "phase-01/notes.txt", Vector("ignored"))
    root

  private def run(
      root: Path,
      rewrites: Vector[(String, String, String)] = Vector.empty
  ) =
    FactoryCounts.compute(
      root,
      Vector("phase-01"),
      Mapping.parse(mappingText),
      rewrites
    )

  private def count(r: FactoryReport, category: String): Int =
    r.evidence.count(_.category == category)

  test("BLOCKED-ON status lines are events and other mentions are separate"):
    val r = run(world())
    assertEquals(r.counts("blockedOnEvents").num.toInt, 0 + 1)
    assertEquals(r.counts("blockedOnMentions").num.toInt, 1)
    val e = r.evidence.find(_.category == "blocked-on-event").get
    assertEquals(e.file, "phase-01/report-01-build-engineer.md")
    assertEquals(e.line, 1)

  test("ownership violations are matched with negations left out"):
    val r = run(world())
    assertEquals(r.counts("ownershipViolationsCaught").num.toInt, 1)

  test("findings are normalised by severity and attributed to agents"):
    val r = run(world())
    val by = r.counts("reviewerFindingsBySeverity")
    assertEquals(by("major").num.toInt, 1)
    assertEquals(by("minor").num.toInt, 2)
    assert(
      r.evidence.exists(e =>
        e.category == "finding" && e.detail == "major;build-engineer"
      )
    )

  test("first-pass acceptance reads the first review round of the scope"):
    val r = run(world())
    val acc = r.counts("firstPassAcceptanceByAgent")
    assertEquals(acc("build-engineer")("acceptedNoFinding").num.toInt, 0)
    assertEquals(acc("build-engineer")("acceptedNoBlockerOrMajor").num.toInt, 0)
    assertEquals(acc("infra-engineer")("acceptedNoFinding").num.toInt, 0)
    assertEquals(acc("infra-engineer")("acceptedNoBlockerOrMajor").num.toInt, 1)

  test("review rounds, repair files, human commands and rulings are counted"):
    val r = run(world())
    assertEquals(r.counts("repairFiles").num.toInt, 1)
    assertEquals(r.counts("humanCommandBlocks").num.toInt, 2)
    assertEquals(r.counts("humanRulings").num.toInt, 2)
    assertEquals(r.counts("nonMarkdownSkipped").num.toInt, 1)
    assertEquals(r.counts("reviewRoundsByScope")("phase-01").arr.size, 2)

  test("guard refusals are found in prose and a rewrite list is counted"):
    val r = run(world(), Vector(("phase-01", "a.md", "rewritten")))
    assertEquals(count(r, "hook-refusal-prose"), 1)
    assertEquals(r.counts("humanRewrites").num.toInt, 1)

  test("every counted line carries its file and line"):
    val r = run(world())
    assert(
      r.evidence
        .filter(_.category == "finding")
        .forall(e => e.file.nonEmpty && e.line > 0)
    )

  private val aliasMapping = Mapping.parse(
    mappingText ++ Vector(
      "alias\tservice\tservice-engineer",
      "alias\thadoop\thadoop-engineer"
    )
  )

  private def aliasWorld(): Path =
    val root = Files.createTempDirectory("runs-alias-")
    put(root, "phase-02/work-order-01-hadoop-x.md", Vector("# WO"))
    put(root, "phase-02/work-order-02-build-engineer-y.md", Vector("# WO"))
    put(
      root,
      "phase-02/review-1.md",
      Vector(
        "## Major",
        "- M1 the test harness and the service wiring disagree about the hadoop output",
        "- M2 stale wording in the guide. Owner: test",
        "- M3 build-engineer: formatting",
        "- M4 the rag notes are old. Owners: service, rag-engineer",
        "- M5 a service sentence, owner of the test plan is unclear"
      )
    )
    root

  test("a sentence that mentions an alias word does not attribute a finding"):
    val docs =
      FactoryCounts.readDocs(aliasWorld(), Vector("phase-02"), aliasMapping)._1
    val review = docs.find(_.name == "review-1.md").get
    val found = FactoryCounts.findings(review, aliasMapping)
    assertEquals(found.size, 5)
    // M1: "test", "service" and "hadoop" are prose, not owners
    assertEquals(found(0).agents, Vector.empty[String])
    // M2: an explicit owner tag may use an alias
    assertEquals(found(1).agents, Vector("test-engineer"))
    // M3: a full role name
    assertEquals(found(2).agents, Vector("build-engineer"))
    // M4: a tag with an alias and a full name
    assertEquals(found(3).agents, Vector("service-engineer", "rag-engineer"))
    // M5: "owner of the test plan" is not a tag
    assertEquals(found(4).agents, Vector.empty[String])

  test("aliases still attribute a document by its file name"):
    val r = FactoryCounts.compute(
      aliasWorld(),
      Vector("phase-02"),
      aliasMapping,
      Vector.empty
    )
    val details = r.evidence
      .filter(_.category == "first-pass-acceptance")
      .map(_.detail.takeWhile(_ != ';'))
    assertEquals(details.toSet, Set("hadoop-engineer", "build-engineer"))
    // M1 mentions "hadoop" in prose: the former alias rule made it a finding on
    // hadoop-engineer, so this agent had no clean first pass. Now it has one.
    val acc = r.counts("firstPassAcceptanceByAgent")
    assertEquals(acc("hadoop-engineer")("acceptedNoFinding").num.toInt, 1)
    assertEquals(acc("build-engineer")("acceptedNoFinding").num.toInt, 0)
    // "test" and "service" in prose created no pair for those agents
    assert(!acc.obj.contains("test-engineer"))
    assert(!acc.obj.contains("service-engineer"))

  test("each (scope, agent) pair has one evidence row naming its documents"):
    val r = run(world())
    val rows = r.evidence.filter(_.category == "first-pass-acceptance")
    assertEquals(rows.size, 2)
    val build = rows.find(_.detail.startsWith("build-engineer;")).get
    assertEquals(build.scope, "phase-01")
    // the first-round review file, and the attributing documents in the detail
    assertEquals(build.file, "phase-01/review-round-1.md")
    assert(
      build.detail.contains(
        "attributedBy=phase-01/report-01-build-engineer.md|phase-01/work-order-01-build-engineer-x.md"
      ),
      build.detail
    )
    assert(
      build.detail.contains("reviewFiles=phase-01/review-round-1.md"),
      build.detail
    )
    assert(build.detail.contains("findings=1;blockerOrMajor=1"), build.detail)
    val infra = rows.find(_.detail.startsWith("infra-engineer;")).get
    assert(infra.detail.contains("findings=1;blockerOrMajor=0"), infra.detail)
    assertEquals(r.counts("firstPassPairs").num.toInt, 2)
    assertEquals(r.counts("firstPassPairsReviewed").num.toInt, 2)
    assertEquals(r.counts("firstPassPairsWithoutReview").num.toInt, 0)

  test("a delivered pair with no review round still has an evidence row"):
    val root = Files.createTempDirectory("runs-norev-")
    put(root, "phase-03/work-order-01-rag-engineer-x.md", Vector("# WO"))
    val r = FactoryCounts.compute(
      root,
      Vector("phase-03"),
      Mapping.parse(mappingText),
      Vector.empty
    )
    val rows = r.evidence.filter(_.category == "first-pass-no-review")
    assertEquals(rows.size, 1)
    assertEquals(rows.head.file, "phase-03/work-order-01-rag-engineer-x.md")
    assertEquals(r.counts("firstPassPairsWithoutReview").num.toInt, 1)

  private def copiesWorld(): Path =
    val root = Files.createTempDirectory("runs-copies-")
    val blocked = "**Status: BLOCKED-ON** infra-engineer"
    put(root, "phase-04/report-01-build-engineer.md", Vector(blocked))
    // the same event, archived as received in a second report and an evidence file
    put(root, "phase-04/report-02-copy.md", Vector(blocked))
    put(root, "phase-04/notes.md", Vector(blocked))
    val own =
      "- M1 ownership violation: build-engineer wrote outside its write set"
    put(root, "phase-04/review-1.md", Vector(own))
    put(root, "phase-04/report-03-repair.md", Vector(own.replace("- M1", "M1")))
    put(
      root,
      "phase-04/report-04-hooks.md",
      Vector(
        "- H1 the guard refused grep on the log file",
        "- H2 the guard refused a write to the config file",
        "- H3 the hook refused the command",
        "- H4 the guard refused grep and a write in one call"
      )
    )
    put(
      root,
      "phase-04/report-05-hooks-copy.md",
      Vector(
        "- H1 the guard refused grep on the log file, as received",
        "- H2 the guard refused a write to the config file, as received"
      )
    )
    root

  private def copies() =
    FactoryCounts.compute(
      copiesWorld(),
      Vector("phase-04"),
      Mapping.parse(mappingText),
      Vector.empty
    )

  test("one BLOCKED-ON event written in three files counts once"):
    val r = copies()
    assertEquals(r.counts("blockedOnEvents").num.toInt, 1)
    assertEquals(r.counts("blockedOnEventLines").num.toInt, 3)
    val rows = r.evidence.filter(_.category == "blocked-on-event")
    assertEquals(rows.size, 3)
    assertEquals(rows.count(_.detail.endsWith(";event")), 1)
    assertEquals(rows.count(_.detail.endsWith(";duplicate")), 2)

  test("a review finding quoted in a non-review file is a separate event"):
    // the review's M1 is keyed with its file, the report's copy is not, so the
    // two do not merge: a documented overcount, never a silent merge
    val r = copies()
    assertEquals(r.counts("ownershipViolationsCaught").num.toInt, 2)
    assertEquals(r.counts("ownershipViolationLines").num.toInt, 2)

  test("two reviews with the same finding id stay two events"):
    val root = Files.createTempDirectory("runs-m1-")
    val line =
      "- M1 ownership violation: build-engineer wrote outside its write set"
    put(root, "phase-05/review-1.md", Vector(line))
    put(root, "phase-05/review-2.md", Vector(line))
    val r = FactoryCounts.compute(
      root,
      Vector("phase-05"),
      Mapping.parse(mappingText),
      Vector.empty
    )
    assertEquals(r.counts("ownershipViolationsCaught").num.toInt, 2)
    assertEquals(r.counts("ownershipViolationLines").num.toInt, 2)

  test("the same WO-n BLOCKED-ON in different scopes stays separate"):
    val root = Files.createTempDirectory("runs-scopes-")
    val line = "**Status: BLOCKED-ON** WO-3 owner missing"
    put(root, "phase-06/wave-1/report-01.md", Vector(line))
    put(root, "phase-06/wave-2/report-01.md", Vector(line))
    val r = FactoryCounts.compute(
      root,
      Vector("phase-06"),
      Mapping.parse(mappingText),
      Vector.empty
    )
    assertEquals(r.counts("blockedOnEvents").num.toInt, 2)
    assertEquals(r.counts("blockedOnEventLines").num.toInt, 2)

  test("the same event quoted in two files of one scope stays one"):
    val root = Files.createTempDirectory("runs-onescope-")
    val line = "**Status: BLOCKED-ON** WO-3 owner missing"
    put(root, "phase-07/wave-1/report-01.md", Vector(line))
    put(root, "phase-07/wave-1/report-02-copy.md", Vector(line))
    val r = FactoryCounts.compute(
      root,
      Vector("phase-07"),
      Mapping.parse(mappingText),
      Vector.empty
    )
    assertEquals(r.counts("blockedOnEvents").num.toInt, 1)
    assertEquals(r.counts("blockedOnEventLines").num.toInt, 2)

  test("the prose basis calls event counts approximate and bounds refusals"):
    val r = copies()
    val basis = r.counts("proseCounterBasis").str
    assert(basis.contains("approximate"), basis)
    assert(basis.contains("lower bound"), basis)
    assert(
      r.summary.exists(l =>
        l.startsWith("BLOCKED-ON events") && l.contains("approximate")
      )
    )

  test("hook refusals are classified and the copies merged"):
    val r = copies()
    assertEquals(
      FactoryCounts.classifyRefusal("the guard refused grep"),
      FactoryCounts.HarmlessRead
    )
    assertEquals(
      FactoryCounts.classifyRefusal("the guard refused a write"),
      FactoryCounts.StateChanging
    )
    assertEquals(
      FactoryCounts.classifyRefusal("the hook refused the command"),
      FactoryCounts.Unclassified
    )
    assertEquals(r.counts("hookRefusalMentionsProse").num.toInt, 6)
    assertEquals(r.counts("hookRefusalEvents").num.toInt, 4)
    val by = r.counts("hookRefusalEventsByClass")
    assertEquals(by("harmless-read").num.toInt, 1)
    assertEquals(by("state-changing").num.toInt, 1)
    assertEquals(by("unclassified").num.toInt, 2)
    assertEquals(r.counts("hookRefusalStateChangingUpperBound").num.toInt, 3)
    assert(r.counts("proseCounterBasis").str.contains("upper bound"))

  test("a bad mapping row is refused"):
    intercept[IllegalArgumentException](Mapping.parse(Vector("bogus\tx")))

  test("the committed mapping table loads and has file rules"):
    val path = Paths.get("experiments/evaluation/factory-mapping.tsv")
    assume(Files.isRegularFile(path), "run from the repository root")
    val m = Mapping.load(path)
    assert(m.rules.size >= 10, m.rules.size.toString)
    assert(m.aliases.nonEmpty && m.severities.nonEmpty)
