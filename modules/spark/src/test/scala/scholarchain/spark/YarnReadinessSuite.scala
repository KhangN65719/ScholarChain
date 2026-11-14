package scholarchain.spark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

class YarnReadinessSuite extends SparkSuite:
  test("a master given as an argument wins over spark-submit") {
    assertEquals(
      SparkMaster.resolve(Some("local[1]"), Some("yarn")),
      Some("local[1]")
    )
    assertEquals(SparkMaster.resolve(Some("local[1]"), None), Some("local[1]"))
  }

  test("a master from spark-submit is not overridden") {
    assertEquals(SparkMaster.resolve(None, Some("yarn")), None)
    assertEquals(SparkMaster.resolve(None, Some("local[8]")), None)
  }

  test("local[2] is the default only when nothing supplies a master") {
    assertEquals(SparkMaster.resolve(None, None), Some("local[2]"))
    assertEquals(SparkMaster.resolve(None, Some("")), Some("local[2]"))
    assertEquals(SparkMaster.resolve(Some(""), None), Some("local[2]"))
  }

  private val required = Vector(
    "--er-run",
    "/data/er/snapshot=abc",
    "--hadoop-root",
    "/data/hadoop/snapshot=abc",
    "--out-root",
    "/data/out"
  )

  test("the master argument is optional and keeps its local default") {
    val none = PipelineArgs.parse(required).toOption.get
    assertEquals(none.explicitMaster, None)
    assertEquals(none.master, "local[2]")
    val named =
      PipelineArgs
        .parse(required ++ Vector("--master", "local[3]"))
        .toOption
        .get
    assertEquals(named.explicitMaster, Some("local[3]"))
    assertEquals(named.master, "local[3]")
    assert(PipelineArgs.parse(required ++ Vector("--master", "")).isLeft)
  }

  test("the master is not part of the configuration that seeds the run ID") {
    val none = PipelineArgs.parse(required).toOption.get
    val named =
      PipelineArgs.parse(required ++ Vector("--master", "yarn")).toOption.get
    assertEquals(
      CollaborationPipeline.configJson(none),
      CollaborationPipeline.configJson(named)
    )
    assert(
      !ujson.write(CollaborationPipeline.configJson(none)).contains("yarn")
    )
  }

  test("file URIs are explicit, absolute and decode to the same path") {
    val dir = Files.createTempDirectory("scholarchain-uri-")
    val odd = dir.resolve("with space%20and%percent")
    Files.createDirectories(odd)
    val uri = Inputs.fileUri(odd)
    assert(uri.startsWith("file:///"), uri)
    assertEquals(Paths.get(uri.stripPrefix("file://")), odd.toAbsolutePath)
    assert(uri.endsWith("with space%20and%percent"), uri)
    val relative = Inputs.fileUri(Paths.get("some/relative/dir"))
    assert(relative.startsWith("file:///"), relative)
    assert(!relative.contains("//some"), relative)
  }

  test("an input directory with unusual characters is read through its URI") {
    val dir = Files.createTempDirectory("scholarchain-uri-read-")
    val odd = dir.resolve("a b%25c")
    Files.createDirectories(odd)
    Files.writeString(
      odd.resolve("part-00000"),
      "{\"workId\":\"w1\",\"year\":2001}\n{\"workId\":\"w2\",\"year\":2002}\n",
      UTF_8
    )
    val data = Inputs.json(session, odd, "workId STRING, year INT")
    assertEquals(
      rows(data.orderBy("workId")).map(r => (r.getString(0), r.getInt(1))),
      Vector(("w1", 2001), ("w2", 2002))
    )
  }
