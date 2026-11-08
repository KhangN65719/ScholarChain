package scholarchain.er

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scholarchain.er.pairs.InputFiles

class InputFilesSuite extends munit.FunSuite:
  private def write(path: Path, text: String): Path =
    Files.createDirectories(path.getParent)
    Files.writeString(path, text, UTF_8)

  private def names(root: Path): Vector[String] =
    InputFiles.files(root).map(root.relativize(_).toString)

  test("part files are read in name order and Hadoop markers are skipped") {
    val root = Files.createTempDirectory("er-input")
    write(root.resolve("part-r-00001"), "b1\nb2\n")
    write(root.resolve("part-r-00000"), "a1\n")
    write(root.resolve("_SUCCESS"), "")
    write(root.resolve(".part-r-00000.crc"), "crc-bytes")
    write(root.resolve("_temporary/0/part-r-00009"), "pending")
    assertEquals(names(root), Vector("part-r-00000", "part-r-00001"))
    assertEquals(InputFiles.lines(root), Vector("a1", "b1", "b2"))
  }

  test("nested directories are read depth first in name order") {
    val root = Files.createTempDirectory("er-input")
    write(root.resolve("v1/part-r-00001"), "v1b\n")
    write(root.resolve("v1/part-r-00000"), "v1a\n")
    write(root.resolve("v2/part-r-00000"), "v2a\n")
    write(root.resolve("_SUCCESS"), "")
    assertEquals(
      names(root),
      Vector("v1/part-r-00000", "v1/part-r-00001", "v2/part-r-00000")
    )
    assertEquals(InputFiles.lines(root), Vector("v1a", "v1b", "v2a"))
  }

  test("a single file is read as itself") {
    val root = Files.createTempDirectory("er-input")
    val file = write(root.resolve("one.jsonl"), "x\ny\n")
    assertEquals(InputFiles.files(file), Vector(file))
    assertEquals(InputFiles.lines(file), Vector("x", "y"))
  }

  test("the directory digest changes with content and with part names") {
    val first = Files.createTempDirectory("er-input")
    write(first.resolve("part-r-00000"), "a\n")
    val same = Files.createTempDirectory("er-input")
    write(same.resolve("part-r-00000"), "a\n")
    val changed = Files.createTempDirectory("er-input")
    write(changed.resolve("part-r-00000"), "b\n")
    val nested = Files.createTempDirectory("er-input")
    write(nested.resolve("v1/part-r-00000"), "a\n")
    assertEquals(InputFiles.sha256(first), InputFiles.sha256(same))
    assertNotEquals(InputFiles.sha256(first), InputFiles.sha256(changed))
    assertNotEquals(InputFiles.sha256(first), InputFiles.sha256(nested))
  }

  test("a directory with no data files is an error, not an empty input") {
    val root = Files.createTempDirectory("er-input")
    write(root.resolve("_SUCCESS"), "")
    write(root.resolve(".part-r-00000.crc"), "crc")
    val error = intercept[IllegalArgumentException](InputFiles.dataFiles(root))
    assert(error.getMessage.contains(root.toString), error.getMessage)
    assert(error.getMessage.contains("No data files"), error.getMessage)
  }

  test("streamed lines match the eager lines") {
    val root = Files.createTempDirectory("er-input")
    write(root.resolve("part-r-00000"), "a\r\nb\n")
    write(root.resolve("part-r-00001"), "c\n\nd")
    val streamed = InputFiles.withLines(root)(_.map(_._1).toVector)
    assertEquals(streamed, InputFiles.lines(root))
  }
