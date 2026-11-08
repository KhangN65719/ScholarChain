package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import munit.FunSuite
import scholarchain.spark.InputLayout

// Item 11: the Spark module reads accepted links and Hadoop exports only. No
// gold labels, no ER decision tree and no ER evaluation, review or sampling
// code is reachable from its classes or its sources.
class SparkLeakageScanSuite extends FunSuite:
  import SparkClassFiles.*

  // Lowercase fragments of paths and packages that must never be named.
  private val needles = Vector(
    "gold",
    "decisions",
    "scholarchain/er",
    "scholarchain.er",
    "candidate-features",
    "match-candidates"
  )

  private def hits(text: String): Vector[String] =
    val lower = text.toLowerCase
    needles.filter(lower.contains)

  test("the scan flags every planted needle in class bytes and sources") {
    val planted = Vector(
      "Lscholarchain/er/eval/Gold;",
      "Lscholarchain/er/review/Queue;",
      "Lscholarchain/er/sampling/Sampler;",
      "Class.forName(\"scholarchain.er.eval.Evaluator\")",
      "gold/labels.jsonl",
      "accepted-links/../decisions/v1",
      "stage=candidate-features/match-candidates/v1"
    )
    planted.foreach { sample =>
      assert(hits(text(sample.getBytes(UTF_8))).nonEmpty, sample)
    }
    val clean = "Lscholarchain/core/Provenance;stage=graph-export/works/v1"
    assertEquals(hits(clean), Vector.empty[String])
  }

  test("the comment stripper keeps strings and drops comments (control)") {
    val source = "val a = 1 // gold\n/* decisions */ val b = \"gold/x\"\n"
    val stripped = withoutComments(source)
    assert(!stripped.contains("decisions"), stripped)
    assert(stripped.contains("\"gold/x\""), stripped)
    assertEquals(hits(stripped), Vector("gold"))
  }

  test("the compiled classes are found, so the scans are not empty") {
    val files = classFiles
    assert(files.size > 30, s"${files.size} classes under $classesRoot")
    val names = files.map(_.getFileName.toString)
    assert(names.contains("CollaborationPipeline$.class"))
    assert(names.contains("Canonical$.class"))
    assert(names.contains("DriverGuard$.class"))
    assert(scalaSources.size >= 10, scalaSources.toString)
  }

  test("main classes name no gold path, decision tree or ER package") {
    val found = classFiles.flatMap { file =>
      val bytes = Files.readAllBytes(file)
      hits(text(bytes)).map(h => s"${file.getFileName}: $h")
    }
    assertEquals(found, Vector.empty[String], found.mkString("\n"))
  }

  test("main classes reach no ER, Hadoop or review class at all") {
    val found = classFiles.flatMap { file =>
      val strings = pool(Files.readAllBytes(file)).utf8
      val er = strings.filter(s =>
        s.contains("scholarchain/er") || s.contains("scholarchain/hadoop")
      )
      er.map(s => s"${file.getFileName}: $s")
    }
    assertEquals(found, Vector.empty[String], found.mkString("\n"))
  }

  test("main sources name no gold path, decision tree or ER package") {
    val found = scalaSources.flatMap { file =>
      val code = withoutComments(Files.readString(file))
      hits(code).map(h => s"$file: $h")
    }
    assertEquals(found, Vector.empty[String], found.mkString("\n"))
  }

  test("the input layout reads only accepted links and Hadoop exports") {
    val layout =
      InputLayout(Paths.get("/er-snapshot"), Paths.get("/hadoop-snapshot"))
    val paths = layout.datasets.map((name, path) => (name, path.toString))
    val names = paths.map(_._1).toSet
    assertEquals(
      names,
      Set(
        "researcher-links",
        "work-links",
        "canonical-researchers",
        "works",
        "author-mentions",
        "institutions",
        "topics",
        "venues",
        "author-papers",
        "pair-candidates",
        "teams"
      )
    )
    paths.foreach { (name, path) =>
      assert(hits(path).isEmpty, s"$name reads $path")
      val fromEr = path.startsWith("/er-snapshot/accepted-links/v1/")
      val fromHadoop = path.startsWith("/hadoop-snapshot/stage=")
      assert(fromEr || fromHadoop, s"$name reads $path")
    }
    assertEquals(
      paths.count(_._2.startsWith("/er-snapshot")),
      3,
      "three ER artifacts"
    )
  }
