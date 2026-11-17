package scholarchain.tests.adversarial

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import scholarchain.storage.*

/** Phase 15 Wave 2, work order WO-12b: a message built by the real producer
  * (`experiments/c-embedding-backlog/message_schema.py`, used by Experiments C
  * and F) is judged by the real consumer rules (`EmbeddingWorkMessage.parse`
  * plus the checks the worker makes before it embeds: the embedding model, the
  * index and its summary type, the vector key).
  *
  * The producer is run as a process (`python3 -I
  * tests/scripts/w2/emit_messages.py`) and the Scala side never copies its
  * output into a fixture, so a change to either side is caught. The expected
  * verdict of every case (`OK` or the parser's refusal reason) is written by
  * hand in that script from the WO-7 schema, not read from either
  * implementation.
  *
  * Needs `python3` on the path and the repository root as the working directory
  * (as sbt runs the tests). Without `python3` the suite says so and asserts
  * nothing, like `P14JobSdkStagedLibSuite` does for a missing staged directory.
  * The Python half of the same cases runs in the script harness
  * (`tests/scripts/run.sh`, `--check`) with no sbt.
  */
class P15EmbeddingMessageCrossModuleSuite extends munit.FunSuite:
  private val script = "tests/scripts/w2/emit_messages.py"

  private final case class Emitted(id: String, want: String, body: String)

  private def info(message: String): Unit = println(s"[p15-schema] $message")

  private def run(): Either[String, Vector[Emitted]] =
    try
      val builder = new java.lang.ProcessBuilder("python3", "-I", script)
      builder.directory(new File("."))
      builder.redirectErrorStream(true)
      val process = builder.start()
      val output = new String(process.getInputStream.readAllBytes(), UTF_8)
      if !process.waitFor(60, TimeUnit.SECONDS) then
        process.destroyForcibly()
        Left("the emit script did not finish in 60 s")
      else if process.exitValue != 0 then
        Left(
          s"the emit script exited ${process.exitValue}: ${output.take(400)}"
        )
      else
        Right(
          output.linesIterator
            .filter(_.nonEmpty)
            .map { line =>
              line.split("\t", 3) match
                case Array(id, want, body) => Emitted(id, want, body)
                case other                 =>
                  throw new IllegalStateException(
                    s"a line has ${other.length} tab fields, expected 3"
                  )
            }
            .toVector
        )
    catch
      case e: java.io.IOException =>
        Left(s"python3 could not be started: ${e.getMessage}")

  private val emitted: Either[String, Vector[Emitted]] = run()

  private def sha256Hex(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** The checks `EmbeddingWorker.prepare` makes on a message the parser
    * accepted: the embedding model, the index, the summary type it takes and
    * the vector key.
    */
  private def workerAccepts(message: EmbeddingWorkMessage, id: String): Unit =
    val item = message.item
    assertEquals(
      item.embeddingModel,
      TitanEmbedder.ModelVersion,
      s"$id: the producer's embedding model is not the worker's"
    )
    val spec = VectorIndexes
      .all(TitanEmbedder.Dimension, "cosine")
      .find(_.name == item.index)
    assert(spec.isDefined, s"$id: no storage index named ${item.index}")
    assert(
      spec.exists(_.summaryType.contains(item.documentType)),
      s"$id: index ${item.index} does not take document type ${item.documentType}"
    )
    VectorKeys.validate(item.summaryId)
    assertEquals(
      item.textHash,
      sha256Hex(message.text),
      s"$id: the textHash is not the SHA-256 of the UTF-8 text"
    )
    assert(
      message.text.getBytes(UTF_8).length <= EmbeddingWorkMessage.MaxTextBytes,
      s"$id: text above the byte cap was accepted"
    )

  test("the producer runs and emits a case list of the expected size") {
    emitted match
      case Left(reason) if reason.startsWith("python3 could not be started") =>
        info(s"$reason: nothing asserted")
      case Left(reason) => fail(reason)
      case Right(cases) =>
        assert(cases.size >= 25, s"only ${cases.size} cases were emitted")
        assertEquals(cases.map(_.id).distinct.size, cases.size)
        assert(cases.count(_.want == "OK") >= 7, "too few accepted cases")
        assert(cases.count(_.want != "OK") >= 15, "too few refused cases")
  }

  emitted.foreach(_.foreach { c =>
    test(s"the storage parser agrees with the producer on ${c.id}") {
      EmbeddingWorkMessage.parse(c.body) match
        case Right(message) =>
          assertEquals(
            c.want,
            "OK",
            s"${c.id}: the storage parser accepted a message that the schema refuses"
          )
          workerAccepts(message, c.id)
          assertEquals(
            EmbeddingWorkMessage.parse(EmbeddingWorkMessage.encode(message)),
            Right(message),
            s"${c.id}: the Scala encoding of an accepted message is not accepted back"
          )
        case Left(EmbeddingWorkMessage.Refused(reason)) =>
          assertEquals(
            reason,
            c.want,
            s"${c.id}: the storage parser refused with a different reason (or refused what the schema accepts)"
          )
    }
  })
