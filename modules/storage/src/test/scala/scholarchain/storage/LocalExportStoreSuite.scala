package scholarchain.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The read-only store over a flat local export directory. */
class LocalExportStoreSuite extends munit.FunSuite:
  private val prefix = AwsReadModels.exportPrefix(Fixtures.runId)

  private def code(thunk: => Any): ErrorCode =
    intercept[StorageException](thunk).code

  private def fixture(): (Path, LocalExportStore) =
    val dir = ExportFixtures.newDir()
    Files.createDirectories(dir.resolve("nodes-work/window=2000-2009"))
    Files.writeString(
      dir.resolve("nodes-work/window=2000-2009/part-00000.jsonl"),
      "{\"a\":1}\n"
    )
    Files.writeString(dir.resolve("export-manifest.json"), "{}")
    (dir, new LocalExportStore(dir, prefix))

  test("get reads a nested part file and the manifest") {
    val (_, store) = fixture()
    val key = prefix + "nodes-work/window=2000-2009/part-00000.jsonl"
    assertEquals(
      store.get(key).map(new String(_, UTF_8)),
      Some("{\"a\":1}\n")
    )
    assertEquals(
      store.get(prefix + "export-manifest.json").map(new String(_, UTF_8)),
      Some("{}")
    )
    assertEquals(store.head(key).map(_.bytes), Some(8L))
  }

  test("list and listSizes return sorted keys under a prefix") {
    val (_, store) = fixture()
    val expected = Vector(
      prefix + "export-manifest.json",
      prefix + "nodes-work/window=2000-2009/part-00000.jsonl"
    )
    assertEquals(store.listSizes(prefix).map(_._1), expected)
    assertEquals(store.list(prefix).map(_.key), expected)
    assertEquals(
      store.listSizes(prefix + "nodes-work/").map(_._2),
      Vector(8L)
    )
    assertEquals(store.listSizes("other/"), Vector.empty)
  }

  test("a missing key, or a key outside the prefix, is absent") {
    val (_, store) = fixture()
    assertEquals(store.get(prefix + "nodes-work/nope.jsonl"), None)
    assertEquals(store.head(prefix + "nodes-work/nope.jsonl"), None)
    assertEquals(store.get("elsewhere/export-manifest.json"), None)
  }

  test("a key that escapes the root is refused") {
    val (dir, store) = fixture()
    val outside = Files.createTempFile(dir.getParent, "outside-", ".txt")
    assertEquals(
      code(store.get(prefix + "../" + outside.getFileName.toString)),
      ErrorCode.InvalidKey
    )
    assertEquals(code(store.get(prefix + "a/../../x")), ErrorCode.InvalidKey)
    assertEquals(code(store.head(prefix + "../x")), ErrorCode.InvalidKey)
  }

  test("a symbolic link out of the root is refused") {
    val (dir, store) = fixture()
    val outside = Files.createTempFile("outside-", ".txt")
    Files.createSymbolicLink(dir.resolve("link.jsonl"), outside)
    assertEquals(code(store.get(prefix + "link.jsonl")), ErrorCode.InvalidKey)
  }

  test("listSizes omits a file whose real path leaves the root") {
    val (dir, store) = fixture()
    val outside = Files.createTempFile("outside-", ".txt")
    Files.createSymbolicLink(dir.resolve("link.jsonl"), outside)
    val keys = store.listSizes(prefix).map(_._1)
    assert(!keys.contains(prefix + "link.jsonl"), keys.toString)
    assertEquals(
      keys,
      Vector(
        prefix + "export-manifest.json",
        prefix + "nodes-work/window=2000-2009/part-00000.jsonl"
      )
    )
    assertEquals(store.list(prefix).map(_.key), keys)
  }

  test("a link that stays inside the root is still listed") {
    val (dir, store) = fixture()
    Files.createSymbolicLink(
      dir.resolve("inner.jsonl"),
      dir.resolve("export-manifest.json")
    )
    assert(store.listSizes(prefix).map(_._1).contains(prefix + "inner.jsonl"))
  }

  test("an emptyObjects entry that names a real file is refused") {
    val (dir, _) = fixture()
    assertEquals(
      code(
        new LocalExportStore(
          dir,
          prefix,
          None,
          Vector("nodes-work/window=2000-2009/part-00000.jsonl")
        )
      ),
      ErrorCode.InvalidKey
    )
    val outside = Files.createTempFile("outside-", ".txt")
    Files.createSymbolicLink(dir.resolve("link.jsonl"), outside)
    assertEquals(
      code(new LocalExportStore(dir, prefix, None, Vector("link.jsonl"))),
      ErrorCode.InvalidKey
    )
  }

  test("an emptyObjects entry that leaves the root is refused") {
    val (dir, _) = fixture()
    val absolute = dir.getParent.resolve("x").toString
    val inRootAbsolute = dir.resolve("x").toString
    Vector(
      "../x",
      absolute,
      "/x",
      "a/../../x",
      "",
      ".",
      "a/../b",
      "./x",
      "x//y",
      "x/",
      inRootAbsolute
    ).foreach { name =>
      assertEquals(
        code(new LocalExportStore(dir, prefix, None, Vector(name))),
        ErrorCode.InvalidKey,
        name
      )
    }
    val ok = new LocalExportStore(dir, prefix, None, Vector("c/p.jsonl"))
    assertEquals(
      ok.list(prefix).map(_.key).contains(prefix + "c/p.jsonl"),
      true
    )
  }

  test("get and listSizes agree on the override and empty objects") {
    val dir = ExportFixtures.newDir()
    Files.createDirectories(dir.resolve("t"))
    Files.writeString(dir.resolve("t/part-00000.jsonl"), "{}\n")
    val store = new LocalExportStore(
      dir,
      prefix,
      Some("{\"k\":1}".getBytes(UTF_8)),
      Vector("c/part-00000.jsonl")
    )
    val sizes = store.listSizes(prefix)
    assertEquals(
      sizes.map(_._1),
      Vector(
        prefix + "c/part-00000.jsonl",
        prefix + "export-manifest.json",
        prefix + "t/part-00000.jsonl"
      )
    )
    sizes.foreach { case (key, size) =>
      assertEquals(store.get(key).map(_.length.toLong), Some(size), key)
    }
  }

  test("a write is rejected and the directory is unchanged") {
    val (dir, store) = fixture()
    val before = store.listSizes(prefix)
    intercept[UnsupportedOperationException] {
      store.putImmutable(prefix + "new.jsonl", Array[Byte](1), "test")
    }
    assertEquals(
      code(
        store.putImmutable(
          prefix + "export-manifest.json",
          Array[Byte](1),
          "test"
        )
      ),
      ErrorCode.ImmutableKeyConflict
    )
    assertEquals(store.listSizes(prefix), before)
    assert(!Files.exists(dir.resolve("new.jsonl")))
  }

  test("a manifest override and empty objects are served and listed") {
    val (dir, _) = fixture()
    val store = new LocalExportStore(
      dir,
      prefix,
      Some("{\"k\":1}".getBytes(UTF_8)),
      Vector("components/part-00000.jsonl")
    )
    assertEquals(
      store.get(prefix + "export-manifest.json").map(new String(_, UTF_8)),
      Some("{\"k\":1}")
    )
    assertEquals(
      store.get(prefix + "components/part-00000.jsonl").map(_.length),
      Some(0)
    )
    val sizes = store.listSizes(prefix).toMap
    assertEquals(sizes(prefix + "export-manifest.json"), 7L)
    assertEquals(sizes(prefix + "components/part-00000.jsonl"), 0L)
  }

  test("AwsReadModels.fromExport loads an export through the store") {
    val dir = ExportFixtures.newDir()
    ExportFixtures.write(
      dir,
      AwsReadModels.RequiredTables.map(_ -> Vector.empty[Row]).toMap
    )
    val loaded = AwsReadModels.fromExport(
      new LocalExportStore(dir, prefix),
      Fixtures.runId
    )
    assertEquals(loaded.summary.runId, Fixtures.runId)
    assertEquals(
      loaded.summary.tables.map(_.table).toSet,
      AwsReadModels.RequiredTables.toSet
    )
  }
