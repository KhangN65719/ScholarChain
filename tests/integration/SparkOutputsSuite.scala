package scholarchain.tests.analytics

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.HexFormat
import org.apache.spark.sql.Row
import scholarchain.core.{
  CoauthorEdge,
  ContractSchema,
  Provenance,
  ResearcherMetrics,
  SourceType,
  YearWindow
}

// Items 12 and 13 and the export gate: load manifests, provenance on every
// row, the mapping onto core contracts, and the run manifest.
class SparkOutputsSuite extends SparkBase:
  private def out: Output = done(Runs.a)

  private val tableNames = Vector(
    "nodes-researcher",
    "nodes-work",
    "nodes-venue",
    "nodes-topic",
    "nodes-institution",
    "edges-authored",
    "edges-published-in",
    "edges-coauthored",
    "edge-support",
    "researcher-metrics",
    "component-membership",
    "components",
    "component-summary",
    "community-membership",
    "communities",
    "community-cross-edges",
    "bridge",
    "coreness",
    "path-evidence",
    "path-length-estimates",
    "temporal-comparison",
    "temporal-window-summary",
    "semantic-summaries",
    "unresolved-mentions",
    "integrity-violations"
  )

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)))

  test("the run succeeds, writes its marker and the 25 tables") {
    val run = Runs.a
    assertEquals(run.code, 0, run.text)
    assert(run.text.contains("SPARK-PIPELINE RESULT: SUCCESS"), run.text)
    assert(Files.isRegularFile(run.runDir.resolve("_SUCCESS")))
    assertEquals(
      run.runDir.getFileName.toString,
      "run=" + run.runId.replace(':', '-')
    )
    tableNames.foreach { name =>
      assert(
        Files.isDirectory(run.runDir.resolve("tables").resolve(name)),
        name
      )
    }
    val manifest = out.json("run-manifest.json")
    val written = manifest("outputs").arr.map(_("table").str).toVector
    assertEquals(written.sorted, tableNames.sorted)
    manifest("invariants").arr.foreach { item =>
      assertEquals(item("status").str, "pass", item("name").str)
    }
    val names = manifest("invariants").arr.map(_("name").str).toSet
    assert(names.contains("authoredEdgesWithoutResearcherNode"))
    assert(names.contains("edgesNotInCanonicalOrder"))
    assert(names.contains("edgesOutsideTheirWindow"))
  }

  // ---- load manifests ----

  private val targets = Vector("neptune", "dynamodb", "s3-vectors", "s3")

  private def fileEntries(value: ujson.Value): Vector[ujson.Value] =
    value match
      case obj: ujson.Obj =>
        val self =
          if obj.value.contains("sha256") then Vector(obj) else Vector()
        self ++ obj.value.values.toVector.flatMap(fileEntries)
      case arr: ujson.Arr => arr.value.toVector.flatMap(fileEntries)
      case _              => Vector.empty

  private def keysOf(value: ujson.Value): Vector[String] =
    value match
      case obj: ujson.Obj =>
        obj.value.keys.toVector ++ obj.value.values.toVector.flatMap(keysOf)
      case arr: ujson.Arr => arr.value.toVector.flatMap(keysOf)
      case _              => Vector.empty

  test(
    "every load manifest is planned only and lists a real SHA-256 per file"
  ) {
    val run = Runs.a
    targets.foreach { target =>
      val manifest = out.json(s"load-manifests/$target.json")
      assertEquals(manifest("target").str, target)
      assertEquals(manifest("status").str, "planned-not-uploaded", target)
      assertEquals(manifest("runId").str, run.runId, target)
      val entries = fileEntries(manifest)
      assert(entries.nonEmpty, s"$target lists no files")
      entries.foreach { entry =>
        val relative = entry("path").str
        assert(!relative.startsWith("/"), s"$target: absolute path $relative")
        val file = run.runDir.resolve(relative)
        assert(Files.isRegularFile(file), s"$target: missing $relative")
        assertEquals(entry("sha256").str, sha256(file), s"$target $relative")
        assertEquals(entry("bytes").num.toLong, Files.size(file), relative)
      }
    }
  }

  test(
    "the S3 manifest covers all tables, with the row counts that were read"
  ) {
    val s3 = out.json("load-manifests/s3.json")("tables").arr.toVector
    assertEquals(s3.map(_("table").str).sorted, tableNames.sorted)
    s3.foreach { entry =>
      val name = entry("table").str
      assertEquals(entry("rows").num.toLong, out.table(name).size.toLong, name)
      assert(entry("files").arr.nonEmpty, s"$name lists no file")
      assert(entry("schema").arr.nonEmpty, name)
    }
    val windowed = Set("researcher-metrics", "bridge", "coreness", "components")
    s3.filter(e => windowed(e("table").str)).foreach { entry =>
      assertEquals(
        entry("partitionedBy").arr.map(_.str).toVector,
        Vector("window")
      )
    }
  }

  test("the target manifests name the tables each loader needs") {
    val neptune = out.json("load-manifests/neptune.json")
    val labels = neptune("vertices").arr.map(_("vertexLabel").str).toSet
    assertEquals(
      labels,
      Set("Researcher", "Work", "Venue", "Topic", "Institution")
    )
    val edges = neptune("edges").arr.map(_("edgeLabel").str).toSet
    assertEquals(edges, Set("AUTHORED", "COAUTHORED_WITH", "PUBLISHED_IN"))
    val dynamo = out.json("load-manifests/dynamodb.json")("itemTables").arr
    assert(dynamo.map(_("table").str).contains("researcher-metrics"))
    val vectors =
      out.json("load-manifests/s3-vectors.json")("vectorSources").arr
    assertEquals(
      vectors.map(_("table").str).toVector,
      Vector("semantic-summaries")
    )
    assertEquals(vectors.head("textColumn").str, "summaryText")
  }

  test("no manifest holds a bucket, an ARN, an account, a region or a host") {
    val run = Runs.a
    val forbiddenKey =
      "(?i).*(bucket|arn|endpoint|account|region|cluster|role).*"
    val forbiddenText = Vector(
      "arn:aws",
      "amazonaws",
      "s3://",
      "s3a://",
      "AKIA",
      "ASIA",
      "https://",
      "http://",
      run.runDir.getParent.toString,
      Harness.root.toString
    )
    val region = "\\b(us|eu|ap|sa|ca|me|af)-[a-z]+-\\d\\b".r
    val account = "(?<![0-9a-zA-Z])\\d{12}(?![0-9a-zA-Z])".r
    targets.foreach { target =>
      val path = run.runDir.resolve(s"load-manifests/$target.json")
      val text = Files.readString(path)
      forbiddenText.foreach { word =>
        assert(!text.contains(word), s"$target contains $word")
      }
      assertEquals(region.findFirstIn(text), None, target)
      assertEquals(account.findFirstIn(text), None, target)
      keysOf(ujson.read(text)).foreach { key =>
        assert(!key.matches(forbiddenKey), s"$target has a key named $key")
      }
    }
  }

  // ---- provenance ----

  test("every output row carries a complete derived provenance") {
    val run = Runs.a
    tableNames.foreach { name =>
      val rows = out.table(name)
      rows.foreach { row =>
        val p = row("provenance").asInstanceOf[Row]
        val clue = s"$name: $row"
        assertEquals(p.getAs[Int]("schemaVersion"), 1, clue)
        assertEquals(p.getAs[String]("source"), SourceType.Derived.wire, clue)
        assertEquals(
          p.getAs[String]("snapshotId"),
          s"hadoop-snapshot:${Fixtures.snapshot}"
        )
        assertEquals(
          p.getAs[String]("sourceUri"),
          s"scholarchain:spark-analytics/$name"
        )
        assertEquals(
          p.getAs[String]("transformVersion"),
          scholarchain.spark.RunContext.transformVersion,
          clue
        )
        assertEquals(p.getAs[String]("runId"), run.runId, clue)
        assert(p.getAs[String]("sourceRecordId").trim.nonEmpty, clue)
        // The core contract accepts the same fields.
        Provenance(
          ContractSchema.currentVersion,
          SourceType.Derived,
          p.getAs[String]("snapshotId"),
          p.getAs[String]("sourceRecordId"),
          p.getAs[String]("sourceUri"),
          p.getAs[String]("transformVersion"),
          p.getAs[String]("runId")
        )
      }
    }
  }

  private def provenanceOf(row: Rec): Provenance =
    val p = row("provenance").asInstanceOf[Row]
    Provenance(
      ContractSchema.currentVersion,
      SourceType.Derived,
      p.getAs[String]("snapshotId"),
      p.getAs[String]("sourceRecordId"),
      p.getAs[String]("sourceUri"),
      p.getAs[String]("transformVersion"),
      p.getAs[String]("runId")
    )

  test("edges between canonical researchers are valid core CoauthorEdges") {
    val edges = out.table("edges-coauthored")
    val canonical = edges.filter { e =>
      !e.flag("endpointAUnresolved") && !e.flag("endpointBUnresolved")
    }
    assert(canonical.nonEmpty)
    canonical.foreach { e =>
      CoauthorEdge(
        ContractSchema.currentVersion,
        e.text("edgeId"),
        e.text("researcherA"),
        e.text("researcherB"),
        YearWindow(e.long("windowStart").toInt, e.long("windowEnd").toInt),
        e.text("policyVersion"),
        e.long("jointPublicationCount"),
        e.long("firstCollaborationYear").toInt,
        e.long("lastCollaborationYear").toInt,
        e.strings("representativeWorkIds"),
        provenanceOf(e)
      )
    }
    // An edge with an unresolved endpoint has no core form, and says so.
    val flagged = edges.filter(e =>
      e.flag("endpointAUnresolved") || e.flag("endpointBUnresolved")
    )
    assertEquals(flagged.size + canonical.size, edges.size)
    assert(
      flagged.forall(e =>
        e.text("researcherA").startsWith("mention:") || e
          .text("researcherB")
          .startsWith("mention:")
      )
    )
  }

  test("metrics of canonical researchers are valid core ResearcherMetrics") {
    val rows = out.table("researcher-metrics")
    val canonical = rows.filter(_.text("nodeKind") == "canonical")
    assert(canonical.size > 50)
    canonical.foreach { m =>
      ResearcherMetrics(
        ContractSchema.currentVersion,
        m.text("researcherId"),
        YearWindow(m.long("windowStart").toInt, m.long("windowEnd").toInt),
        m.long("publicationCount"),
        m.long("distinctCoauthorCount"),
        m.long("totalCoauthorOccurrences"),
        m.num("averageCoauthorsPerPaper"),
        m.long("maximumCoauthorsOnOnePaper"),
        m.strings("maximumTeamWorkIds"),
        m.num("weightedDegree"),
        m.text("componentId"),
        m.long("componentSize"),
        Some(m.text("communityId")),
        Some(m.num("bridgeScore")),
        Some(m.long("coreness").toInt),
        provenanceOf(m)
      )
    }
  }

  // ---- run manifest, partitioning and shuffle ----

  test("the run manifest records inputs, configuration, parameters and code") {
    val run = Runs.a
    val manifest = out.json("run-manifest.json")
    assertEquals(manifest("runId").str, run.runId)
    val inputs = manifest("inputs")
    assertEquals(
      inputs("snapshot").str,
      s"hadoop-snapshot:${Fixtures.snapshot}"
    )
    assertEquals(
      inputs("erRunId").str,
      "run:v1:2f4cd132-afcb-4bca-83c5-1a0ddf001ace"
    )
    assertEquals(
      inputs("hadoopRunId").str,
      "run:v1:d4722bc7-0d02-42da-bdb6-088ee508a143"
    )
    assertEquals(inputs("erPolicyVersion").str, "scholarchain.er.policy/1.4.0")
    val erManifest = Runs.inputsA.erRun.resolve("manifest.json")
    assertEquals(inputs("erManifestSha256").str, sha256(erManifest))
    val datasets = inputs("datasets").arr.toVector
    assertEquals(datasets.size, 11)
    datasets.foreach(d => assert(d("sha256").str.matches("[0-9a-f]{64}")))
    val config = manifest("config")
    assertEquals(config("hopLimit").num.toInt, 3)
    assertEquals(config("partitions").num.toInt, 8)
    assertEquals(config("seed").num.toLong, 42L)
    assertEquals(config("windows").arr.size, 4)
    assertEquals(manifest("algorithms")("communities")("seed").num.toLong, 42L)
    assertEquals(manifest("algorithms")("paths")("hopLimit").num.toInt, 3)
    assertEquals(manifest("loadManifests").arr.size, 4)
    assert(manifest("versions")("spark").str.nonEmpty)
    assert(manifest("code")("gitHead").str.nonEmpty)
  }

  test("checkpoints were taken and removed, shuffles ran and were recorded") {
    val run = Runs.a
    val manifest = out.json("run-manifest.json")
    assert(manifest("checkpointing")("checkpointsTaken").num > 0)
    assertEquals(manifest("checkpointing")("removedAfterRun").bool, true)
    assert(!Files.exists(run.runDir.resolve("_checkpoints")))
    assertEquals(manifest("partitioning")("shufflePartitions").num.toInt, 8)
    assertEquals(manifest("partitioning")("adaptiveQueryExecution").bool, false)
    val metrics = out.json("stage-metrics.json")
    assert(metrics("totals")("tasks").num > 0)
    assert(metrics("totals")("shuffleWriteBytes").num > 0)
    assert(metrics("totals")("shuffleReadBytes").num > 0)
    val plans = out.files("plans").map(_.getFileName.toString)
    assert(plans.contains("components-pass.txt"), plans.toString)
    val components =
      Files.readString(run.runDir.resolve("plans/components-pass.txt"))
    assert(components.contains("Exchange"), "components plan shows no shuffle")
    // The module documents the stage DAG, partitioning and shuffle boundaries.
    val design =
      Files.readString(java.nio.file.Paths.get("modules/spark/DESIGN.md"))
    assert(design.contains("Stage DAG"))
    assert(design.contains("Partitioning, shuffles and joins"))
    assert(design.contains("Checkpointing"))
  }

  test("a decisions tree, a gold file and match candidates are never read") {
    val inputs = Runs.inputsA
    val planted = Vector(
      inputs.erRun.resolve("decisions/v1/part-00000"),
      inputs.erRun.resolve("integrity-failures/v1/part-00000"),
      inputs.erRun.resolve("gold/part-00000"),
      inputs.hadoopRoot.resolve(
        "stage=candidate-features/match-candidates/v1/part-r-00000"
      )
    )
    planted.foreach { file =>
      assertEquals(Files.readString(file), Fixtures.poison, file.toString)
    }
    // The run succeeded although those files are not valid JSON, and its
    // recorded inputs are the 11 datasets and nothing else.
    val datasets = out
      .json("run-manifest.json")("inputs")("datasets")
      .arr
      .map(_("path").str)
    assertEquals(datasets.size, 11)
    datasets.foreach { path =>
      Vector("decisions", "gold", "candidate-features", "integrity-failures")
        .foreach(word => assert(!path.contains(word), path))
    }
  }
