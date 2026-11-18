package scholarchain.evaluation

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import munit.FunSuite
import scholarchain.evaluation.io.RawAppender

class RawAppenderSuite extends FunSuite:
  test("every append is on disk before close") {
    val dir = Files.createTempDirectory("raw-appender-")
    val raw = new RawAppender(dir, "x.jsonl")
    try
      raw.append("""{"a":1}""")
      assertEquals(Files.readString(raw.path, UTF_8), "{\"a\":1}\n")
      raw.append("""{"a":2}""")
      assertEquals(Files.readString(raw.path, UTF_8), "{\"a\":1}\n{\"a\":2}\n")
    finally raw.close()
  }

  test("an existing file is an error") {
    val dir = Files.createTempDirectory("raw-appender-")
    val first = new RawAppender(dir, "x.jsonl")
    first.close()
    intercept[java.nio.file.FileAlreadyExistsException](
      new RawAppender(dir, "x.jsonl")
    )
  }
